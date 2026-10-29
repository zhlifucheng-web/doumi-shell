package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SshUploadServiceTest {
    SshUploadService service = new SshUploadService();
    SFTPClient sftp = mock(SFTPClient.class);
    RemoteFile remote = mock(RemoteFile.class);
    @BeforeEach void setup() throws Exception {
        SshSessionRegistry registry = mock(SshSessionRegistry.class);
        SshSessionRegistry.SessionEntry entry = new SshSessionRegistry.SessionEntry();
        SSHClient client = mock(SSHClient.class);
        entry.setClient(client);
        when(client.newSFTPClient()).thenReturn(sftp);
        when(registry.get("token")).thenReturn(entry);
        when(sftp.open(anyString(), anySet())).thenReturn(remote);
        when(sftp.open(anyString(), anySet(), any(FileAttributes.class))).thenReturn(remote);
        when(sftp.version()).thenReturn(5);
        when(sftp.statExistence(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).endsWith(".part") ? FileAttributes.EMPTY : null);
        ReflectionTestUtils.setField(service, "sessionRegistry", registry);
    }
    MockMultipartFile bytes(int count) { return new MockMultipartFile("file", new byte[count]); }
    String temp(String id) { return "/tmp/.doumi-upload-" + id + ".part"; }
    @Test void onlyCompleteUploadReplacesOriginal() throws Exception {
        String id = service.start("token", "/tmp", "中文.bin", 3);
        verify(sftp).open(eq(temp(id)), eq(EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)),
                argThat(attrs -> attrs.getMode().getPermissionsMask() == 0600));
        service.chunk("token", id, 0, bytes(3));
        verify(sftp, never()).open(eq("/tmp/中文.bin"), anySet());
        assertThrows(IOException.class, () -> service.complete("token", id));
        verify(sftp, never()).rename(anyString(), anyString(), anySet());
        when(sftp.size(temp(id))).thenReturn(3L);
        service.complete("token", id);
        verify(sftp).rename(temp(id), "/tmp/中文.bin", EnumSet.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC));
        service.cancel("token", id);
        verify(sftp, never()).rm(anyString());
    }
    @Test void rejectsIncompleteOutOfOrderForeignAndOversizedChunks() throws Exception {
        String id = service.start("token", "/tmp", "file", 10);
        assertThrows(IOException.class, () -> service.complete("token", id));
        assertThrows(IOException.class, () -> service.chunk("token", id, 1, bytes(1)));
        assertThrows(IOException.class, () -> service.chunk("other", id, 0, bytes(1)));
        assertThrows(IOException.class, () -> service.cancel("other", id));
        assertThrows(IOException.class, () -> service.chunk("token", id, 0, bytes(11)));
        assertThrows(IOException.class, () -> service.chunk("token", id, 0, bytes(8 * 1024 * 1024 + 1)));
        verify(remote, never()).write(anyLong(), any(byte[].class), anyInt(), anyInt());
    }
    @Test void failedWriteAndCancellationNeverTouchOriginal() throws Exception {
        String id = service.start("token", "/tmp", "file", 3);
        doThrow(new IOException("disk full")).when(remote).write(anyLong(), any(byte[].class), anyInt(), anyInt());
        assertThrows(IOException.class, () -> service.chunk("token", id, 0, bytes(3)));
        assertThrows(IOException.class, () -> service.chunk("token", id, 0, bytes(3)));
        service.cancel("token", id);
        service.cancel("token", id);
        verify(sftp).rm(temp(id));
        verify(sftp, never()).rename(anyString(), anyString(), anySet());
        verify(sftp, never()).rm("/tmp/file");
    }
    @Test void sameDestinationHasIndependentTemporaryFiles() throws Exception {
        String first = service.start("token", "/tmp", "file", 1);
        String second = service.start("token", "/tmp", "file", 1);
        assertNotEquals(first, second);
        service.cancel("token", first);
        service.chunk("token", second, 0, bytes(1));
        when(sftp.size(temp(second))).thenReturn(1L);
        service.complete("token", second);
        verify(sftp).rm(temp(first));
        verify(sftp).rename(eq(temp(second)), eq("/tmp/file"), anySet());
    }
    @Test void emptyFilesCanCommitWithoutChunks() throws Exception {
        String id = service.start("token", "/tmp", "empty", 0);
        service.complete("token", id);
        verify(sftp).rename(eq(temp(id)), eq("/tmp/empty"), anySet());
    }
    @Test void preservesExistingExecutablePermissionsBeforeReplacement() throws Exception {
        String id = service.start("token", "/tmp", "script", 0);
        when(sftp.statExistence("/tmp/script")).thenReturn(new FileAttributes.Builder()
                .withType(FileMode.Type.REGULAR).withPermissions(0750).withUIDGID(1001, 1002).build());
        service.complete("token", id);
        org.mockito.InOrder order = inOrder(sftp);
        order.verify(sftp).chmod(temp(id), 0750);
        order.verify(sftp).rename(eq(temp(id)), eq("/tmp/script"), anySet());
    }
    @Test void preservesExistingUidGidAndPermissionsBeforeReplacement() throws Exception {
        String id = service.start("token", "/tmp", "service.conf", 0);
        when(sftp.statExistence("/tmp/service.conf")).thenReturn(new FileAttributes.Builder()
                .withType(FileMode.Type.REGULAR).withPermissions(0640).withUIDGID(1001, 1002).build());
        service.complete("token", id);
        verify(sftp).setattr(eq(temp(id)), argThat(attrs ->
                attrs.getUID() == 1001
                        && attrs.getGID() == 1002));
        verify(sftp).rename(eq(temp(id)), eq("/tmp/service.conf"), anySet());
    }
    @Test void failedOwnershipRestoreLeavesOriginalUntouched() throws Exception {
        String id = service.start("token", "/tmp", "protected", 0);
        when(sftp.statExistence("/tmp/protected")).thenReturn(new FileAttributes.Builder()
                .withType(FileMode.Type.REGULAR).withPermissions(06750).withUIDGID(1001, 1002).build());
        doThrow(new IOException("chown denied")).when(sftp).setattr(eq(temp(id)), any(FileAttributes.class));
        assertThrows(IOException.class, () -> service.complete("token", id));
        verify(sftp, never()).rename(anyString(), anyString(), anySet());
        verify(sftp, never()).rm("/tmp/protected");
    }
    @Test void restoresOwnershipBeforeFinalPermissions() throws Exception {
        String id = service.start("token", "/tmp", "protected", 0);
        when(sftp.statExistence("/tmp/protected")).thenReturn(new FileAttributes.Builder()
                .withType(FileMode.Type.REGULAR).withPermissions(06750).withUIDGID(1001, 1002).build());
        service.complete("token", id);
        org.mockito.InOrder order = inOrder(sftp);
        order.verify(sftp).setattr(eq(temp(id)), argThat(attrs -> attrs.has(FileAttributes.Flag.UIDGID)
                && !attrs.has(FileAttributes.Flag.MODE) && attrs.getUID() == 1001 && attrs.getGID() == 1002));
        order.verify(sftp).chmod(temp(id), 06750);
        order.verify(sftp).rename(eq(temp(id)), eq("/tmp/protected"), anySet());
    }
    @Test void unsupportedOverwriteNeverDeletesOriginal() throws Exception {
        String id = service.start("token", "/tmp", "file", 0);
        when(sftp.statExistence("/tmp/file")).thenReturn(new FileAttributes.Builder()
                .withType(FileMode.Type.REGULAR).withPermissions(0644).withUIDGID(0, 0).build());
        when(sftp.version()).thenReturn(3);
        when(sftp.getSFTPEngine()).thenReturn(mock(SFTPEngine.class));
        IOException error = assertThrows(IOException.class, () -> service.complete("token", id));
        assertTrue(error.getMessage().contains("不支持安全覆盖"));
        service.cancel("token", id);
        verify(sftp, never()).rename(anyString(), anyString());
        verify(sftp, never()).rm("/tmp/file");
    }
    @Test void oldServersCanStillCreateNewDestinations() throws Exception {
        String id = service.start("token", "/tmp", "new-file", 0);
        when(sftp.version()).thenReturn(3);
        when(sftp.getSFTPEngine()).thenReturn(mock(SFTPEngine.class));
        when(sftp.statExistence("/tmp/new-file")).thenReturn(null);
        service.complete("token", id);
        verify(sftp).rename(temp(id), "/tmp/new-file");
        verify(sftp, never()).rename(anyString(), anyString(), anySet());
    }
    @SuppressWarnings("unchecked")
    Object state(String id) { return ((Map<String, ?>) ReflectionTestUtils.getField(service, "uploads")).get(id); }
    @Test void writesBeyondTwoGiBWithLongOffsets() throws Exception {
        long offset = 3L * 1024 * 1024 * 1024;
        String id = service.start("token", "/tmp", "file", offset + 3);
        ReflectionTestUtils.setField(state(id), "offset", offset);
        when(remote.length()).thenReturn(offset);
        service.chunk("token", id, offset, bytes(3));
        verify(remote).write(eq(offset), any(byte[].class), eq(0), eq(3));
    }
    @Test void expiresAbandonedUploadsAndRetriesFailedCleanup() throws Exception {
        String id = service.start("token", "/tmp", "file", 10);
        ReflectionTestUtils.setField(state(id), "touched", System.currentTimeMillis() - 31 * 60 * 1000L);
        doThrow(new IOException("connection lost")).doNothing().when(sftp).rm(temp(id));
        service.cleanupExpired();
        assertNotNull(state(id));
        assertThrows(IOException.class, () -> service.chunk("token", id, 0, bytes(1)));
        service.cleanupExpired();
        assertNull(state(id));
    }
    @Test void cancellationWaitsForInFlightChunkAndRejectsLateWrites() throws Exception {
        String id = service.start("token", "/tmp", "file", 3);
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> { writing.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return null; })
                .when(remote).write(anyLong(), any(byte[].class), anyInt(), anyInt());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> chunk = pool.submit(() -> { service.chunk("token", id, 0, bytes(3)); return null; });
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<?> cancel = pool.submit(() -> { service.cancel("token", id); return null; });
            verify(sftp, never()).rm(anyString());
            release.countDown();
            chunk.get(5, TimeUnit.SECONDS); cancel.get(5, TimeUnit.SECONDS);
            verify(sftp).rm(temp(id));
            assertThrows(IOException.class, () -> service.chunk("token", id, 3, bytes(1)));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void rejectsTraversalAndNegativeTotal() {
        for (String name : new String[]{"..", ".", "../file", "a/b", "a\\b", "a\0b"})
            assertThrows(IOException.class, () -> service.start("token", "/tmp", name, 0));
        assertThrows(IOException.class, () -> service.start("token", "/tmp", "file", -1));
    }
}
