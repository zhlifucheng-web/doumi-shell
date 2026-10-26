package com.doumiot.sshconsole.service;

import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshSftpReadChunkTest {

    private final SshSftpService service = new SshSftpService();
    private final SSHClient client = mock(SSHClient.class);
    private final SFTPClient sftp = mock(SFTPClient.class);
    private final RemoteFile remoteFile = mock(RemoteFile.class);
    private final SshSessionRegistry registry = mock(SshSessionRegistry.class);

    private final String mockFileContent = "Line 1: Hello World!\nLine 2: Server running smoothly.\nLine 3: 2026-10-26 Tail log entry.\n";
    private final byte[] fileBytes = mockFileContent.getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setup() throws Exception {
        SshSessionRegistry.SessionEntry entry = new SshSessionRegistry.SessionEntry();
        entry.setClient(client);
        entry.setHomeDir("/root");

        when(registry.get("valid-token")).thenReturn(entry);
        when(client.isConnected()).thenReturn(true);
        when(client.newSFTPClient()).thenReturn(sftp);
        when(sftp.type("/var/log/app.log")).thenReturn(FileMode.Type.REGULAR);
        when(sftp.open("/var/log/app.log")).thenReturn(remoteFile);
        when(remoteFile.length()).thenReturn((long) fileBytes.length);

        // Mock RemoteFile.read(long fileOffset, byte[] to, int offset, int len)
        when(remoteFile.read(anyLong(), any(byte[].class), anyInt(), anyInt())).thenAnswer(invocation -> {
            long fileOffset = invocation.getArgument(0);
            byte[] targetBuf = invocation.getArgument(1);
            int bufOffset = invocation.getArgument(2);
            int len = invocation.getArgument(3);

            if (fileOffset >= fileBytes.length) {
                return -1;
            }
            int available = (int) Math.min((long) len, fileBytes.length - fileOffset);
            System.arraycopy(fileBytes, (int) fileOffset, targetBuf, bufOffset, available);
            return available;
        });

        ReflectionTestUtils.setField(service, "sessionRegistry", registry);
    }

    @Test
    void testReadFromHead() throws Exception {
        SshSftpService.ChunkResult chunk = service.readChunk("valid-token", "/var/log/app.log", 0, 20, false);
        assertNotNull(chunk);
        assertEquals(fileBytes.length, chunk.getTotalSize());
        assertEquals(0, chunk.getOffset());
        assertEquals(20, chunk.getLength());
        assertFalse(chunk.isHasMoreBefore());
        assertTrue(chunk.isHasMoreAfter());

        byte[] decoded = Base64.getDecoder().decode(chunk.getContentBase64());
        String text = new String(decoded, StandardCharsets.UTF_8);
        assertEquals("Line 1: Hello World!", text);
    }

    @Test
    void testReadFromOffset() throws Exception {
        SshSftpService.ChunkResult chunk = service.readChunk("valid-token", "/var/log/app.log", 21, 33, false);
        assertNotNull(chunk);
        assertEquals(21, chunk.getOffset());
        assertTrue(chunk.isHasMoreBefore());

        byte[] decoded = Base64.getDecoder().decode(chunk.getContentBase64());
        String text = new String(decoded, StandardCharsets.UTF_8);
        assertEquals("Line 2: Server running smoothly.\n", text);
    }

    @Test
    void testTailModeReadFromEnd() throws Exception {
        int tailLength = 35;
        SshSftpService.ChunkResult chunk = service.readChunk("valid-token", "/var/log/app.log", 0, tailLength, true);
        assertNotNull(chunk);
        assertEquals(fileBytes.length - tailLength, chunk.getOffset());
        assertEquals(tailLength, chunk.getLength());
        assertTrue(chunk.isHasMoreBefore());
        assertFalse(chunk.isHasMoreAfter());

        byte[] decoded = Base64.getDecoder().decode(chunk.getContentBase64());
        String text = new String(decoded, StandardCharsets.UTF_8);
        assertTrue(text.endsWith("Tail log entry.\n"));
    }

    @Test
    void testRejectDirectory() throws Exception {
        when(sftp.type("/var/log")).thenReturn(FileMode.Type.DIRECTORY);
        assertThrows(BusinessRuntimeException.class, () ->
                service.readChunk("valid-token", "/var/log", 0, 100, false));
    }
}
