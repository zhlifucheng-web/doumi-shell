package com.doumiot.sshconsole.sftp;

import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SshSftpDeleteTest {
    private final SFTPClient sftp = mock(SFTPClient.class);

    private void type(String path, FileMode.Type type) throws IOException {
        // FileAttributes is final, so build a real value instead of mocking it.
        when(sftp.lstat(path)).thenReturn(new FileAttributes.Builder().withType(type).build());
    }

    private RemoteResourceInfo entry(String name) {
        RemoteResourceInfo entry = mock(RemoteResourceInfo.class);
        when(entry.getName()).thenReturn(name);
        return entry;
    }

    @Test
    void deletesNestedAndHiddenContentsBeforeDirectories() throws IOException {
        type("/data", FileMode.Type.DIRECTORY);
        type("/data/子目录", FileMode.Type.DIRECTORY);
        type("/data/子目录/.hidden", FileMode.Type.REGULAR);
        RemoteResourceInfo current = entry(".");
        RemoteResourceInfo parent = entry("..");
        RemoteResourceInfo sub = entry("子目录");
        RemoteResourceInfo hidden = entry(".hidden");
        when(sftp.ls("/data")).thenReturn(Arrays.asList(current, parent, sub));
        when(sftp.ls("/data/子目录")).thenReturn(Collections.singletonList(hidden));

        SshSftpDelete.delete(sftp, "/data");

        InOrder order = inOrder(sftp);
        order.verify(sftp).rm("/data/子目录/.hidden");
        order.verify(sftp).rmdir("/data/子目录");
        order.verify(sftp).rmdir("/data");
        verify(sftp, never()).lstat("/data/..");
    }

    @Test
    void removesSymlinkWithoutListingItsTarget() throws IOException {
        type("/link", FileMode.Type.SYMLINK);
        SshSftpDelete.delete(sftp, "/link");
        verify(sftp).rm("/link");
        verify(sftp, never()).ls(anyString());
        verify(sftp, never()).rmdir(anyString());
    }

    @Test
    void deletesEmptyDirectory() throws IOException {
        type("/empty", FileMode.Type.DIRECTORY);
        when(sftp.ls("/empty")).thenReturn(Collections.emptyList());
        SshSftpDelete.delete(sftp, "/empty");
        verify(sftp).rmdir("/empty");
    }

    @Test
    void stopsOnFailureAndKeepsParent() throws IOException {
        type("/data", FileMode.Type.DIRECTORY);
        type("/data/file", FileMode.Type.REGULAR);
        RemoteResourceInfo file = entry("file");
        when(sftp.ls("/data")).thenReturn(Collections.singletonList(file));
        doThrow(new IOException("permission denied")).when(sftp).rm("/data/file");
        assertThrows(IOException.class, () -> SshSftpDelete.delete(sftp, "/data"));
        verify(sftp, never()).rmdir(anyString());
    }

    @Test
    void rejectsRootBeforeRemoteOperations() {
        assertThrows(IOException.class, () -> SshSftpDelete.delete(sftp, "/"));
        verifyNoInteractions(sftp);
    }
}
