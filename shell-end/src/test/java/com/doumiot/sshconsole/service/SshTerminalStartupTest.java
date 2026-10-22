package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.SshConsoleProperties;
import com.doumiot.sshconsole.entity.SshAccount;
import com.doumiot.sshconsole.entity.SshHost;
import com.doumiot.sshconsole.ssh.*;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshTerminalStartupTest {
    SshTerminalService service;
    SshSessionRegistry registry;
    SSHClient client;
    Session session;
    Session.Shell shell;
    Session.Command probe;

    @BeforeEach void setup() throws Exception {
        service = new SshTerminalService();
        SshAccountService accounts = mock(SshAccountService.class);
        SshHostService hosts = mock(SshHostService.class);
        SshClientFactory factory = mock(SshClientFactory.class);
        SshConsoleProperties properties = new SshConsoleProperties();
        registry = new SshSessionRegistry(properties, factory, mock(SshHostMonitorService.class));
        ReflectionTestUtils.setField(service,"sshAccountService",accounts);
        ReflectionTestUtils.setField(service,"sshHostService",hosts);
        ReflectionTestUtils.setField(service,"sshClientFactory",factory);
        ReflectionTestUtils.setField(service,"sessionRegistry",registry);
        ReflectionTestUtils.setField(service,"properties",properties);
        SshAccount account = new SshAccount(); account.setId(1L); account.setHostId(1L); account.setUsername("root");
        SshHost host = new SshHost(); host.setId(1L); host.setHost("test-host");
        when(accounts.requireAccount(1L)).thenReturn(account);
        when(hosts.getById(1L)).thenReturn(host);
        client = mock(SSHClient.class); when(client.isConnected()).thenReturn(true);
        when(factory.connectAndAuth(anyString(), anyInt(), anyString(), any(), any())).thenReturn(client);
        session = mock(Session.class); when(client.startSession()).thenReturn(session);
        probe = mock(Session.Command.class); when(session.exec(anyString())).thenReturn(probe);
        when(probe.getExitStatus()).thenReturn(0);
        when(probe.getInputStream()).thenAnswer(i -> new ByteArrayInputStream("/usr/bin/tmux\n".getBytes("UTF-8")));
        when(probe.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        shell = mock(Session.Shell.class); when(session.startShell()).thenReturn(shell);
        when(shell.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(shell.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
    }

    @Test void rejectsAChannelThatNeverConfirmsTmuxAttachment() {
        assertThrows(IOException.class, () -> service.openTerminal(1L,120,40,"stable",false));
        assertEquals(0, registry.activeCount(), "failed startup must release its registry entry");
    }

    @Test void readOnlyShareCannotSilentlyOpenAnUnrelatedPlainShell() {
        when(probe.getExitStatus()).thenReturn(1);
        assertThrows(IOException.class, () -> service.openTerminal(1L,120,40,"stable",true));
        assertEquals(0, registry.activeCount());
    }

    @Test void releasesRegisteredConnectionWhenStartupThrowsRuntimeError() throws Exception {
        when(session.startShell()).thenThrow(new IllegalStateException("channel failed"));
        assertThrows(Exception.class, () -> service.openTerminal(1L,120,40,"stable",false,false,true,stage -> {}));
        assertEquals(0, registry.activeCount());
    }
}
