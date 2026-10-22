package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.doumiot.sshconsole.entity.SshAccount;
import com.doumiot.sshconsole.entity.SshHost;
import com.doumiot.sshconsole.mapper.SshAccountMapper;
import com.doumiot.sshconsole.ssh.SshClientFactory;
import net.schmizz.sshj.SSHClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshAccountServiceTest {

    private SshAccountService accountService;
    private SshHostService hostService;
    private SshClientFactory sshClientFactory;
    private SshAccountMapper accountMapper;

    @BeforeEach
    void setUp() {
        accountService = new SshAccountService();
        hostService = mock(SshHostService.class);
        sshClientFactory = mock(SshClientFactory.class);
        accountMapper = mock(SshAccountMapper.class);

        ReflectionTestUtils.setField(accountService, "sshHostService", hostService);
        ReflectionTestUtils.setField(accountService, "sshClientFactory", sshClientFactory);
        ReflectionTestUtils.setField(accountService, "sshAccountMapper", accountMapper);

        SshHost host = new SshHost();
        host.setId(10L);
        host.setHost("192.168.1.100");
        host.setPort(22);
        host.setEnabled(1);
        when(hostService.getById(10L)).thenReturn(host);
    }

    @Test
    void testConnectionDirectSuccess() throws Exception {
        SSHClient mockClient = mock(SSHClient.class);
        when(sshClientFactory.connectAndAuth(anyString(), anyInt(), anyString(), anyString(), any()))
                .thenReturn(mockClient);

        SshAccountService.SshAccountTestRequest req = new SshAccountService.SshAccountTestRequest();
        req.setHostId(10L);
        req.setUsername("root");
        req.setAuthType("PASSWORD");
        CredentialCipher.SshSecretPayload secret = new CredentialCipher.SshSecretPayload();
        secret.setPassword("secret123");
        req.setSecret(secret);

        SshAccountService.SshConnectionTestResult result = accountService.testConnection(req);

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("连接与认证成功"));
        verify(sshClientFactory).disconnectQuietly(mockClient);
    }

    @Test
    void testConnectionDirectValidationFailures() {
        // Missing username
        SshAccountService.SshAccountTestRequest req1 = new SshAccountService.SshAccountTestRequest();
        req1.setHostId(10L);
        SshAccountService.SshConnectionTestResult r1 = accountService.testConnection(req1);
        assertFalse(r1.isSuccess());
        assertTrue(r1.getMessage().contains("用户名"));

        // Missing secret
        SshAccountService.SshAccountTestRequest req2 = new SshAccountService.SshAccountTestRequest();
        req2.setHostId(10L);
        req2.setUsername("root");
        SshAccountService.SshConnectionTestResult r2 = accountService.testConnection(req2);
        assertFalse(r2.isSuccess());
        assertTrue(r2.getMessage().contains("凭据"));
    }

    @Test
    void testConnectionHandlesAuthFailure() throws Exception {
        when(sshClientFactory.connectAndAuth(anyString(), anyInt(), anyString(), anyString(), any()))
                .thenThrow(new IOException("Auth failed (password)"));

        SshAccountService.SshAccountTestRequest req = new SshAccountService.SshAccountTestRequest();
        req.setHostId(10L);
        req.setUsername("root");
        req.setAuthType("PASSWORD");
        CredentialCipher.SshSecretPayload secret = new CredentialCipher.SshSecretPayload();
        secret.setPassword("wrongpass");
        req.setSecret(secret);

        SshAccountService.SshConnectionTestResult result = accountService.testConnection(req);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("Auth failed"));
    }
}
