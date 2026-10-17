package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LargeFileTransferTest {
    SshSftpService service = new SshSftpService();
    SFTPClient sftp = mock(SFTPClient.class);
    RemoteFile remote = mock(RemoteFile.class);
    @BeforeEach void setup() throws Exception {
        SshSessionRegistry registry = mock(SshSessionRegistry.class);
        SshSessionRegistry.SessionEntry entry = new SshSessionRegistry.SessionEntry();
        SSHClient client = mock(SSHClient.class);
        entry.setClient(client);
        when(client.isConnected()).thenReturn(true);
        when(client.newSFTPClient()).thenReturn(sftp);
        when(registry.get("token")).thenReturn(entry);
        when(sftp.open(anyString(), anySet())).thenReturn(remote);
        ReflectionTestUtils.setField(service, "sessionRegistry", registry);
    }
    @Test void streamsLargeDownloadAndReleasesRemoteResources() throws Exception {
        when(sftp.type("/tmp/big.bin")).thenReturn(FileMode.Type.REGULAR);
        when(sftp.open("/tmp/big.bin")).thenReturn(remote);
        when(remote.length()).thenReturn(3L * 1024 * 1024 * 1024);
        org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource> response =
            service.streamDownload("token", "/tmp/big.bin");
        assertEquals(3L * 1024 * 1024 * 1024, response.getHeaders().getContentLength());
        verify(sftp, never()).close();
        java.io.InputStream body = response.getBody().getInputStream();
        body.close(); body.close();
        verify(sftp).close();
        verify(remote).close();
    }
    @Test void rejectsDirectoryDownloadsAndClosesClient() throws Exception {
        when(sftp.type("/tmp/dir")).thenReturn(FileMode.Type.DIRECTORY);
        assertThrows(IOException.class, () -> service.streamDownload("token", "/tmp/dir"));
        verify(sftp).close();
    }
    @Test void failedDownloadSetupReleasesHandlesAndPreservesOriginalError() throws Exception {
        when(sftp.type("/tmp/file")).thenReturn(FileMode.Type.REGULAR);
        when(sftp.open("/tmp/file")).thenReturn(remote);
        when(remote.length()).thenThrow(new IOException("stat failed"));
        doThrow(new IOException("close failed")).when(remote).close();
        IOException error = assertThrows(IOException.class, () -> service.streamDownload("token", "/tmp/file"));
        assertEquals("stat failed", error.getMessage());
        assertEquals(1, error.getSuppressed().length);
        verify(sftp).close();
    }
}
