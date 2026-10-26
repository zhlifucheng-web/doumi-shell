package com.doumiot.sshconsole.service;

import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SshSftpChownTest {
    private final SshSftpService service = new SshSftpService();
    private final SSHClient client = mock(SSHClient.class);
    private final SFTPClient sftp = mock(SFTPClient.class);
    private final Session session = mock(Session.class);
    private final List<String> commands = new ArrayList<>();
    private String idOutput = "0\nroot\n";
    private String usersOutput = "root\nalice\nbob\n";
    private String groupsOutput = "root\ndevelopers\n";
    private int exitStatus;

    @BeforeEach
    void setup() throws Exception {
        commands.clear();
        exitStatus = 0;
        idOutput = "0\nroot\n";
        usersOutput = "root\nalice\nbob\n";
        groupsOutput = "root\ndevelopers\n";

        SshSessionRegistry registry = mock(SshSessionRegistry.class);
        SshSessionRegistry.SessionEntry entry = new SshSessionRegistry.SessionEntry();
        entry.setClient(client);
        entry.setDefaultCwd(null);
        when(registry.get("token")).thenReturn(entry);
        when(client.isConnected()).thenReturn(true);
        when(client.newSFTPClient()).thenReturn(sftp);
        when(client.startSession()).thenReturn(session);
        when(session.exec(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            commands.add(text);
            Session.Command command = mock(Session.Command.class);
            String output = "";
            if (text.contains("===ID===")) {
                output = "===ID===\n" + idOutput + "===USERS===\n" + usersOutput + "===GROUPS===\n" + groupsOutput;
            }
            when(command.getInputStream()).thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
            when(command.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            when(command.getExitStatus()).thenReturn(exitStatus);
            return command;
        });
        ReflectionTestUtils.setField(service, "sessionRegistry", registry);
    }

    @Test
    void testSystemUsersAndGroupsForRoot() throws Exception {
        SshSftpService.SystemUsersAndGroups result = service.systemUsersAndGroups("token");
        assertEquals("root", result.getCurrentUser());
        assertEquals(0, result.getCurrentUid());
        assertTrue(result.isCanViewOtherUsers());
        assertTrue(result.isCanChown());
        assertTrue(result.getUsers().contains("alice"));
        assertTrue(result.getGroups().contains("developers"));
    }

    @Test
    void testSystemUsersAndGroupsForNonRootClosesOption() throws Exception {
        idOutput = "1000\nalice\n";
        SshSftpService.SystemUsersAndGroups result = service.systemUsersAndGroups("token");
        assertEquals("alice", result.getCurrentUser());
        assertEquals(1000, result.getCurrentUid());
        assertFalse(result.isCanViewOtherUsers());
        assertFalse(result.isCanChown());
    }

    @Test
    void testSystemUsersAndGroupsWithNoOtherUsersClosesOption() throws Exception {
        idOutput = "0\nroot\n";
        usersOutput = "root\n";
        SshSftpService.SystemUsersAndGroups result = service.systemUsersAndGroups("token");
        assertFalse(result.isCanViewOtherUsers());
        assertFalse(result.isCanChown());
    }

    @Test
    void testChownSingleFile() throws Exception {
        service.chown("token", "/var/www/index.html", "alice", "developers", false);
        boolean foundChown = commands.stream().anyMatch(c -> c.equals("chown alice:developers '/var/www/index.html'"));
        assertTrue(foundChown, "Should execute chown with owner:group on file");
    }

    @Test
    void testChownRecursiveOnDirectory() throws Exception {
        service.chown("token", "/var/www/html", "www-data", "www-data", true);
        boolean foundRecursive = commands.stream().anyMatch(c -> c.equals("chown -R www-data:www-data '/var/www/html'"));
        assertTrue(foundRecursive, "Should execute chown -R with owner:group on directory");
    }

    @Test
    void testChownOwnerOnly() throws Exception {
        service.chown("token", "/var/www/html", "alice", "", false);
        boolean foundOwnerOnly = commands.stream().anyMatch(c -> c.equals("chown alice '/var/www/html'"));
        assertTrue(foundOwnerOnly, "Should execute chown with owner only");
    }

    @Test
    void testChownGroupOnly() throws Exception {
        service.chown("token", "/var/www/html", "", "developers", false);
        boolean foundGroupOnly = commands.stream().anyMatch(c -> c.equals("chown :developers '/var/www/html'"));
        assertTrue(foundGroupOnly, "Should execute chown with :group only");
    }

    @Test
    void testChownRejectsInvalidUsername() {
        assertThrows(BusinessRuntimeException.class, () ->
                service.chown("token", "/var/www", "alice; rm -rf /", "root", false));
    }

    @Test
    void testChownRejectsWhenNonRootOrCannotViewOtherUsers() {
        idOutput = "1000\nalice\n";
        assertThrows(BusinessRuntimeException.class, () ->
                service.chown("token", "/var/www", "root", "root", false));
    }
}
