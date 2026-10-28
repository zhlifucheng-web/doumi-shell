package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import com.doumiot.sshconsole.config.SshConsoleProperties;
import org.springframework.mock.web.MockMultipartFile;
import com.doumiot.sshconsole.sftp.SftpStreams.UploadResult;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SshUploadOwnershipTest {
    SshUploadService service = new SshUploadService();
    SshSessionRegistry registry = mock(SshSessionRegistry.class);
    SshSessionRegistry.SessionEntry entry = new SshSessionRegistry.SessionEntry();
    SFTPClient sftp = mock(SFTPClient.class);
    @BeforeEach void setup() throws Exception {
        SSHClient client = mock(SSHClient.class);
        entry.setClient(client); entry.setHomeDir("/original");
        when(registry.get("token")).thenReturn(entry);
        when(client.newSFTPClient()).thenReturn(sftp);
        when(sftp.open(anyString(), anySet(), any(FileAttributes.class))).thenReturn(mock(RemoteFile.class));
        when(sftp.version()).thenReturn(5);
        when(sftp.stat(anyString())).thenReturn(new FileAttributes.Builder()
                .withType(FileMode.Type.REGULAR).withPermissions(06750).withUIDGID(0, 0).build());
        ReflectionTestUtils.setField(service, "sessionRegistry", registry);
    }
    String start(String path, Long uid, Long gid) throws Exception {
        return service.start("token", path, "file", 0L, uid, gid);
    }
    UploadResult complete(String id) throws Exception { return service.completeResult("token", id); }
    @Test void snapshotsDestinationAndOwnerThenAppliesAfterRenameWithoutPrivilegedBits() throws Exception {
        String id = start(null, 1001L, 1002L);
        entry.setHomeDir("/changed");
        UploadResult result = complete(id);
        assertTrue(result.isUploaded());
        assertTrue(result.isOwnershipApplied());
        assertNull(result.getWarning());
        org.mockito.InOrder order = inOrder(sftp);
        order.verify(sftp).rename(anyString(), eq("/original/file"), anySet());
        order.verify(sftp).setattr(eq("/original/file"), argThat(a -> a.getUID() == 1001 && a.getGID() == 1002));
        order.verify(sftp).chmod("/original/file", 0750);
    }
    @Test void chownFailureReturnsSuccessfulUploadWithWarningAndEndsTask() throws Exception {
        String id = start("/tmp", 1001L, 1002L);
        doThrow(new IOException("permission denied")).when(sftp).setattr(eq("/tmp/file"), any(FileAttributes.class));
        UploadResult result = complete(id);
        assertTrue(result.isUploaded());
        assertFalse(result.isOwnershipApplied());
        assertNotNull(result.getWarning());
        assertThrows(IOException.class, () -> service.complete("token", id));
        verify(sftp, never()).rm("/tmp/file");
    }
    @Test void defaultOwnershipNeverChangesCommittedFileOwner() throws Exception {
        String id = start("/tmp", null, null);
        UploadResult result = complete(id);
        assertTrue(result.isUploaded());
        assertFalse(result.isOwnershipApplied());
        assertNull(result.getWarning());
        verify(sftp, never()).setattr(eq("/tmp/file"), any(FileAttributes.class));
    }
    @Test void rejectsIncompleteNegativeAndOversizedOwnersBeforeOpeningSession() {
        Long[][] invalid = {{1L, null}, {null, 2L}, {-1L, 2L}, {1L, -2L}, {2147483648L, 1L}, {1L, 2147483648L}};
        for (Long[] pair : invalid) assertThrows(IOException.class, () -> start("/tmp", pair[0], pair[1]));
        verify(registry, never()).get(anyString());
    }
    @Test void incompleteExistingMetadataCannotReplaceOriginal() throws Exception {
        String id = service.start("token", "/tmp", "file", 0);
        when(sftp.statExistence("/tmp/file")).thenReturn(new FileAttributes.Builder()
                .withType(FileMode.Type.REGULAR).withPermissions(0640).build());
        assertThrows(IOException.class, () -> service.complete("token", id));
        verify(sftp, never()).rename(anyString(), anyString(), anySet());
    }
    @Test void acceptsRootAndMaximumSupportedNumericOwner() throws Exception {
        String id = start("/tmp", 0L, 2147483647L);
        assertTrue(complete(id).isOwnershipApplied());
        verify(sftp).setattr(eq("/tmp/file"), argThat(a -> a.getUID() == 0 && a.getGID() == 2147483647));
    }
    @Test void finalPermissionRestoreFailureCannotReplaceOriginal() throws Exception {
        String id = service.start("token", "/tmp", "file", 0);
        when(sftp.statExistence("/tmp/file")).thenReturn(new FileAttributes.Builder()
                .withType(FileMode.Type.REGULAR).withPermissions(06750).withUIDGID(1001, 1002).build());
        doThrow(new IOException("chmod denied")).when(sftp).chmod(anyString(), eq(06750));
        assertThrows(IOException.class, () -> service.complete("token", id));
        verify(sftp, never()).rename(anyString(), anyString(), anySet());
        verify(sftp, never()).rm("/tmp/file");
    }
    @Test void directUploadUsesOriginalSftpConnectionAndReturnsOwnershipWarning() throws Exception {
        SshSftpService direct = new SshSftpService();
        ReflectionTestUtils.setField(direct, "sessionRegistry", registry);
        ReflectionTestUtils.setField(direct, "properties", new SshConsoleProperties());
        when(entry.getClient().isConnected()).thenReturn(true);
        doThrow(new IOException("chown denied")).when(sftp).setattr(eq("/tmp/file"), any(FileAttributes.class));
        UploadResult result = direct.upload("token", "/tmp", new MockMultipartFile("file", "file", "application/octet-stream", new byte[0]), 1001L, 1002L);
        assertTrue(result.isUploaded());
        assertFalse(result.isOwnershipApplied());
        assertNotNull(result.getWarning());
        verify(sftp).rename(anyString(), eq("/tmp/file"), anySet());
        verify(entry.getClient(), never()).startSession();
    }
}
