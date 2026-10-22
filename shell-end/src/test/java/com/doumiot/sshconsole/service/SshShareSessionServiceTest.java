package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.entity.SshAccount;
import com.doumiot.sshconsole.entity.SshHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshShareSessionServiceTest {

    private SshShareSessionService shareService;
    private SshAccountService accountService;
    private SshHostService hostService;

    @BeforeEach
    void setUp() {
        shareService = new SshShareSessionService();
        accountService = mock(SshAccountService.class);
        hostService = mock(SshHostService.class);
        ReflectionTestUtils.setField(shareService, "sshAccountService", accountService);
        ReflectionTestUtils.setField(shareService, "sshHostService", hostService);

        SshAccount account = new SshAccount();
        account.setId(101L);
        account.setHostId(11L);
        account.setUsername("root");
        when(accountService.requireAccount(101L)).thenReturn(account);

        SshHost host = new SshHost();
        host.setId(11L);
        host.setName("app-prod-01");
        host.setHost("192.168.10.11");
        host.setPort(22);
        when(hostService.getById(11L)).thenReturn(host);
    }

    @Test
    void createsShareWithStableMetadataAndCustomExpiration() {
        SshShareSessionService.ShareCreateRequest req = new SshShareSessionService.ShareCreateRequest();
        req.setAccountId(101L);
        req.setTerminalId("default");
        req.setPermission("READ_WRITE");
        req.setExpireMinutes(60);
        req.setEncoding("GBK");

        SshShareSessionService.ShareInfoVO info = shareService.createShare(req);
        assertNotNull(info.getToken());
        assertTrue(info.getToken().startsWith("sh_"));
        assertEquals(101L, info.getAccountId());
        assertEquals("default", info.getTerminalId());
        assertEquals("READ_WRITE", info.getPermission());
        assertEquals("GBK", info.getEncoding());
        assertFalse(info.isReadOnly());
        assertFalse(info.isExpired());
        assertNotNull(info.getExpireAt());
        assertTrue(info.getLabel().contains("app-prod-01"));
        assertTrue(info.getLabel().contains("root"));

        // Retrieve and verify
        SshShareSessionService.ShareRecord record = shareService.getValidShare(info.getToken());
        assertNotNull(record);
        assertEquals(101L, record.getAccountId());
        assertEquals("GBK", record.getEncoding());
    }

    @Test
    void supportsReadOnlyPermissionAndRevocation() {
        SshShareSessionService.ShareCreateRequest req = new SshShareSessionService.ShareCreateRequest();
        req.setAccountId(101L);
        req.setTerminalId("term-2");
        req.setPermission("READ_ONLY");
        req.setExpireMinutes(0); // permanent until revoked

        SshShareSessionService.ShareInfoVO info = shareService.createShare(req);
        assertTrue(info.isReadOnly());
        assertNull(info.getExpireAt());

        // List active shares for account
        List<SshShareSessionService.ShareInfoVO> list = shareService.listActiveShares(101L, "term-2");
        assertEquals(1, list.size());
        assertEquals(info.getToken(), list.get(0).getToken());

        // Revoke
        boolean revoked = shareService.revokeShare(info.getToken());
        assertTrue(revoked);
        assertNull(shareService.getValidShare(info.getToken()));
        assertTrue(shareService.getShareInfo(info.getToken()).isExpired());
    }
}
