package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.sftp.SshSftpAttributeFormatter;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshSftpIdentityTest {
    private final SshSftpService service = new SshSftpService();
    private final SSHClient client = mock(SSHClient.class);
    private final SFTPClient sftp = mock(SFTPClient.class);
    private final Session session = mock(Session.class);
    private final List<String> commands = new ArrayList<>();
    private String passwd = "1000:alice\n0:root\n", group = "100:developers\n0:root\n";
    private int exitStatus;

    @BeforeEach void setup() throws Exception {
        SshSessionRegistry registry = mock(SshSessionRegistry.class);
        SshSessionRegistry.SessionEntry entry = new SshSessionRegistry.SessionEntry();
        entry.setClient(client);
        when(registry.get("token")).thenReturn(entry);
        when(client.isConnected()).thenReturn(true);
        when(client.newSFTPClient()).thenReturn(sftp);
        when(client.startSession()).thenReturn(session);
        when(session.exec(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            commands.add(text);
            Session.Command command = mock(Session.Command.class);
            String output = text.contains("getent passwd ") ? passwd : group;
            when(command.getInputStream()).thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
            when(command.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(command.getExitStatus()).thenReturn(exitStatus);
            return command;
        });
        ReflectionTestUtils.setField(service, "sessionRegistry", registry);
    }

    private RemoteResourceInfo file(String name, FileAttributes attrs) {
        RemoteResourceInfo info = mock(RemoteResourceInfo.class);
        when(info.getName()).thenReturn(name);
        when(info.getAttributes()).thenReturn(attrs);
        return info;
    }

    private FileAttributes attrs(int uid, int gid) {
        return new FileAttributes.Builder().withType(FileMode.Type.REGULAR)
                .withUIDGID(uid, gid).withPermissions(0644).build();
    }

    @Test void linuxNamesReplaceNumericIdsAndPreserveSftpAttributes() throws Exception {
        doReturn(Arrays.asList(file("report", attrs(1000, 100)), file("system", attrs(0, 0)))).when(sftp).ls("/tmp");
        List<SshSftpService.FileEntry> rows = service.list("token", "/tmp").getItems();
        assertEquals("alice", rows.get(0).getOwner());
        assertEquals("developers", rows.get(0).getGroup());
        assertEquals("root", rows.get(1).getOwner());
        assertEquals("root", rows.get(1).getGroup());
        assertEquals("-rw-r--r--", rows.get(0).getPermissions());
        assertEquals(2, commands.size());
        assertTrue(commands.get(0).contains("getent passwd 1000 0 "));
        assertTrue(commands.get(1).contains("getent group 100 0 "));
        org.mockito.InOrder order = inOrder(sftp, client);
        order.verify(sftp).close();
        order.verify(client, times(2)).startSession();
    }

    @Test void unknownIdsAndPartialGetentFailureKeepNumericValues() throws Exception {
        exitStatus = 2;
        doReturn(Arrays.asList(file("known", attrs(1000, 100)), file("unknown", attrs(2000, 200)))).when(sftp).ls("/tmp");
        List<SshSftpService.FileEntry> rows = service.list("token", "/tmp").getItems();
        assertEquals("alice", rows.get(0).getOwner());
        assertEquals("developers", rows.get(0).getGroup());
        assertEquals("2000", rows.get(1).getOwner());
        assertEquals("200", rows.get(1).getGroup());
    }

    @Test void missingGetentHasLocalAccountFileFallback() throws Exception {
        passwd = "1000:local-user\n";
        group = "100:local-group\n";
        doReturn(Collections.singletonList(file("local", attrs(1000, 100)))).when(sftp).ls("/tmp");
        SshSftpService.FileEntry row = service.list("token", "/tmp").getItems().get(0);
        assertEquals("local-user", row.getOwner());
        assertEquals("local-group", row.getGroup());
        assertTrue(commands.get(0).startsWith("if command -v getent >/dev/null 2>&1; then "));
        assertTrue(commands.get(0).contains("else cat /etc/passwd 2>/dev/null; fi"));
        assertTrue(commands.get(1).contains("else cat /etc/group 2>/dev/null; fi"));
    }

    @Test void windowsOrSftpOnlyServerCanRejectExecWithoutBreakingListing() throws Exception {
        when(client.startSession()).thenThrow(new ConnectionException("exec is disabled"));
        doReturn(Collections.singletonList(file("file", attrs(1000, 100)))).when(sftp).ls("/tmp");
        SshSftpService.FileEntry row = service.list("token", "/tmp").getItems().get(0);
        assertEquals("1000", row.getOwner());
        assertEquals("100", row.getGroup());
    }

    @Test void unavailableOwnershipIsNotReportedAsRoot() throws Exception {
        doReturn(Arrays.asList(file("empty", FileAttributes.EMPTY), file("absent", null))).when(sftp).ls("/tmp");
        for (SshSftpService.FileEntry row : service.list("token", "/tmp").getItems()) {
            assertEquals("—", row.getOwner());
            assertEquals("—", row.getGroup());
        }
        verify(client, never()).startSession();
    }

    @Test void unsupportedUnixCommandsKeepIdsEvenIfSshExecSucceeds() throws Exception {
        exitStatus = 127;
        passwd = "";
        group = "";
        doReturn(Collections.singletonList(file("file", attrs(1000, 100)))).when(sftp).ls("/tmp");
        SshSftpService.FileEntry row = service.list("token", "/tmp").getItems().get(0);
        assertEquals("1000", row.getOwner());
        assertEquals("100", row.getGroup());
    }

    @Test void ownerLookupFailureDoesNotDiscardResolvableGroup() throws Exception {
        when(client.startSession()).thenThrow(new ConnectionException("passwd lookup failed")).thenReturn(session);
        doReturn(Collections.singletonList(file("file", attrs(1000, 100)))).when(sftp).ls("/tmp");
        SshSftpService.FileEntry row = service.list("token", "/tmp").getItems().get(0);
        assertEquals("1000", row.getOwner());
        assertEquals("developers", row.getGroup());
    }

    @Test void namesAndPathsWithTabsNewlinesAndQuotesNeverEnterCommands() throws Exception {
        String path = "/tmp/space ' $()\t\n/dir", name = "a\tname\n'$(whoami)";
        doReturn(Collections.singletonList(file(name, attrs(1000, 100)))).when(sftp).ls(path);
        SshSftpService.FileEntry row = service.list("token", path).getItems().get(0);
        assertEquals(name, row.getName());
        assertEquals("alice", row.getOwner());
        for (String command : commands) {
            assertFalse(command.contains(path));
            assertFalse(command.contains(name));
        }
    }

    @Test void duplicateIdsAreQueriedOnceAndParentEntriesAreIgnored() throws Exception {
        List<RemoteResourceInfo> files = new ArrayList<>();
        files.add(file(".", attrs(999, 999)));
        files.add(file("..", attrs(998, 998)));
        for (int i = 0; i < 200; i++) files.add(file("file" + i, attrs(1000, 100)));
        when(sftp.ls("/tmp")).thenReturn(files);
        assertEquals(200, service.list("token", "/tmp").getItems().size());
        assertEquals(2, commands.size());
        assertTrue(commands.get(0).contains("getent passwd 1000 2>"));
        assertFalse(commands.get(0).contains("999"));
        assertFalse(commands.get(0).contains("998"));
    }

    @Test void uidGidSupportUnsigned32BitValues() throws Exception {
        passwd = "4294967294:nobody\n4294967296:invalid\n";
        group = "2147483648:large-group\n";
        doReturn(Arrays.asList(file("named", attrs(-2, Integer.MIN_VALUE)), file("numeric", attrs(-1, -1)))).when(sftp).ls("/tmp");
        List<SshSftpService.FileEntry> rows = service.list("token", "/tmp").getItems();
        assertEquals("nobody", rows.get(0).getOwner());
        assertEquals("large-group", rows.get(0).getGroup());
        assertEquals("4294967295", rows.get(1).getOwner());
        assertEquals("4294967295", rows.get(1).getGroup());
        assertTrue(commands.get(0).contains("4294967294 4294967295"));
        Map<Integer, String> parsed = SshSftpAttributeFormatter.parseIdNameLines(passwd);
        assertEquals(1, parsed.size());
    }

    @Test void manyDistinctIdsAreBatchedWithoutLosingNames() throws Exception {
        List<RemoteResourceInfo> files = new ArrayList<>();
        StringBuilder users = new StringBuilder();
        for (int i = 0; i < 130; i++) {
            files.add(file(String.format("file%03d", i), attrs(5000 + i, 100)));
            users.append(5000 + i).append(":user").append(i).append('\n');
        }
        passwd = users.toString();
        when(sftp.ls("/tmp")).thenReturn(files);
        List<SshSftpService.FileEntry> rows = service.list("token", "/tmp").getItems();
        assertEquals("user0", rows.get(0).getOwner());
        assertEquals("user129", rows.get(129).getOwner());
        assertEquals(3, commands.size());
        assertTrue(commands.get(1).contains("getent passwd 5128 5129 2>"));
    }
}
