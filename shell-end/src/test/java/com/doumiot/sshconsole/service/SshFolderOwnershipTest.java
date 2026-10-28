package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.SshConsoleProperties;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import com.doumiot.sshconsole.sftp.SftpStreams.UploadResult;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SshFolderOwnershipTest {
    SshFolderTransferService service = new SshFolderTransferService();
    SFTPClient sftp = mock(SFTPClient.class);
    Map<String, FileAttributes> remote = new HashMap<>();
    FileAttributes directory = new FileAttributes.Builder().withType(FileMode.Type.DIRECTORY)
            .withPermissions(0755).withUIDGID(0, 0).build();
    @BeforeEach void setup() throws Exception {
        SshSessionRegistry sessions = mock(SshSessionRegistry.class);
        SshSessionRegistry.SessionEntry entry = new SshSessionRegistry.SessionEntry();
        SSHClient client = mock(SSHClient.class); entry.setClient(client);
        when(sessions.get("token")).thenReturn(entry);
        when(client.newSFTPClient()).thenReturn(sftp);
        ReflectionTestUtils.setField(service, "sessions", sessions);
        ReflectionTestUtils.setField(service, "properties", new SshConsoleProperties());
        when(sftp.canonicalize("/base")).thenReturn("/base");
        remote.put("/base/existing", directory);
        when(sftp.lstat(anyString())).thenAnswer(i -> {
            FileAttributes attrs = remote.get(i.getArgument(0));
            if (attrs == null) throw new SFTPException(Response.StatusCode.NO_SUCH_FILE, "missing");
            return attrs;
        });
        when(sftp.stat(anyString())).thenAnswer(i -> remote.get(i.getArgument(0)));
        doAnswer(i -> { remote.put(i.getArgument(0), directory); return null; }).when(sftp).mkdir(anyString());
        when(sftp.open(anyString(), anySet(), any(FileAttributes.class))).thenReturn(mock(RemoteFile.class));
        when(sftp.version()).thenReturn(5);
        doAnswer(i -> {remote.put(i.getArgument(1), new FileAttributes.Builder().withType(FileMode.Type.REGULAR)
                .withPermissions(0600).withUIDGID(0, 0).build()); return null;})
                .when(sftp).rename(anyString(), anyString(), anySet());
    }
    UploadResult mkdir(String relative, Long uid, Long gid) throws Exception {
        return service.mkdir("token", "/base", relative, uid, gid);
    }
    @Test void onlyNewDirectoriesReceiveCapturedOwnership() throws Exception {
        UploadResult result = mkdir("existing/new/deep", 1001L, 1002L);
        assertTrue(result.isOwnershipApplied());
        verify(sftp, never()).setattr(eq("/base/existing"), any(FileAttributes.class));
        verify(sftp).setattr(eq("/base/existing/new"), argThat(a -> a.getUID() == 1001 && a.getGID() == 1002));
        verify(sftp).setattr(eq("/base/existing/new/deep"), argThat(a -> a.getUID() == 1001 && a.getGID() == 1002));
    }
    @Test void directoryOwnershipDenialReportsWarningWithoutLosingCreatedDirectory() throws Exception {
        doThrow(new IOException("denied")).when(sftp).setattr(eq("/base/new"), any(FileAttributes.class));
        UploadResult result = mkdir("new", 1001L, 1002L);
        assertTrue(remote.containsKey("/base/new"));
        assertTrue(result.isUploaded());
        assertNotNull(result.getWarning());
    }
    @Test void fileAndParentDirectoriesShareOwnershipPair() throws Exception {
        UploadResult result = service.uploadFile("token", "/base", "existing/new/file",
                new MockMultipartFile("file", new byte[0]), 1001L, 1002L);
        assertTrue(result.isUploaded());
        verify(sftp).setattr(eq("/base/existing/new"), argThat(a -> a.getUID() == 1001 && a.getGID() == 1002));
        verify(sftp).setattr(eq("/base/existing/new/file"), argThat(a -> a.getUID() == 1001 && a.getGID() == 1002));
        verify(sftp, never()).setattr(eq("/base/existing"), any(FileAttributes.class));
    }
    @Test void archiveFilesAndDirectoriesReceiveSamePairAndRetainEarlierWarning() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("new/")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("new/file")); zip.closeEntry();
        }
        doThrow(new IOException("directory chown denied")).when(sftp).setattr(eq("/base/new"), any(FileAttributes.class));
        UploadResult result = service.uploadArchive("token", "/base",
                new MockMultipartFile("file", bytes.toByteArray()), 1001L, 1002L);
        assertTrue(result.isUploaded());
        assertFalse(result.isOwnershipApplied());
        assertNotNull(result.getWarning());
        verify(sftp).setattr(eq("/base/new"), argThat(a -> a.getUID() == 1001 && a.getGID() == 1002));
        verify(sftp).setattr(eq("/base/new/file"), argThat(a -> a.getUID() == 1001 && a.getGID() == 1002));
    }
    @Test void defaultDirectoryUploadDoesNotChangeAnyOwner() throws Exception {
        UploadResult result = service.mkdir("token", "/base", "existing/new", null, null);
        assertFalse(result.isOwnershipApplied());
        assertNull(result.getWarning());
        verify(sftp, never()).setattr(anyString(), any(FileAttributes.class));
        verify(sftp).mkdir("/base/existing/new");
    }
}
