package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.SshConsoleProperties;
import com.doumiot.sshconsole.controller.SshAccountController;
import com.doumiot.sshconsole.ssh.SshClientFactory;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import net.schmizz.sshj.SSHClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshViewerSessionTest {
    @Test void reconnectMetadataAndViewerCleanupNeverCloseSharedTerminalConnection() {
        SshConsoleProperties properties = new SshConsoleProperties(); properties.setMaxSessions(10);
        SshClientFactory clients = mock(SshClientFactory.class);
        SshSessionRegistry sessions = new SshSessionRegistry(properties, clients, mock(SshHostMonitorService.class));
        SSHClient sharedClient = mock(SSHClient.class), viewerClient = mock(SSHClient.class);
        SshSessionRegistry.SessionEntry shared = sessions.register(sharedClient,57,"u@host","","",null);
        SshSessionRegistry.SessionEntry viewer = sessions.register(viewerClient,57,"u@host","","",null);
        SshAccountService accounts = mock(SshAccountService.class);
        SshAccountService.SshSessionResponse response = new SshAccountService.SshSessionResponse(); response.setSessionToken(viewer.getSessionToken());
        when(accounts.openSession(57)).thenReturn(response);
        SshAccountController controller = new SshAccountController();
        ReflectionTestUtils.setField(controller,"sessions",sessions);
        ReflectionTestUtils.setField(controller,"sshAccountService",accounts);
        assertEquals(57L,controller.sessionContext(shared.getSessionToken()).get("accountId").longValue());
        controller.closeViewerSession(shared.getSessionToken());
        assertEquals(2,sessions.activeCount()); verify(clients,never()).disconnectQuietly(sharedClient);
        assertEquals(viewer.getSessionToken(),controller.openViewerSession(57).getSessionToken());
        controller.closeViewerSession(viewer.getSessionToken()); controller.closeViewerSession(viewer.getSessionToken());
        assertEquals(1,sessions.activeCount()); assertSame(shared,sessions.get(shared.getSessionToken()));
        verify(clients,times(1)).disconnectQuietly(viewerClient);
    }
}
