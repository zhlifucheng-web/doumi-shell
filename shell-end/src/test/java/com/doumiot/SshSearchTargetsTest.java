package com.doumiot;

import com.doumiot.sshconsole.controller.SshGroupController;
import com.doumiot.sshconsole.controller.SshHostController;
import com.doumiot.sshconsole.controller.SshAccountController;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.doumiot.sshconsole.service.SshAccountService;
import com.doumiot.sshconsole.service.SshGroupService;
import com.doumiot.sshconsole.service.SshHostService;
import com.doumiot.sshconsole.service.SshTreeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ShellEndApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"doumi.storage.data-dir=./target/integration-data/${random.uuid}", "doumi.ssh-console.credential-master-key="})
public class SshSearchTargetsTest {

    @Autowired private SshGroupService groupService;
    @Autowired private SshHostService hostService;
    @Autowired private SshAccountService accountService;
    @Autowired private SshTreeService treeService;

    @Test
    public void searchTargetsContainsHostAddressIpAndAccountInfo() {
        SshGroupService.SshGroupSaveRequest gReq = new SshGroupService.SshGroupSaveRequest();
        gReq.setName("TestSearchGroup");
        long groupId = groupService.create(gReq).getId();

        try {
            SshHostService.SshHostSaveRequest hReq = new SshHostService.SshHostSaveRequest();
            hReq.setGroupId(groupId);
            hReq.setName("SearchTestHost");
            hReq.setHost("192.168.100.88");
            hReq.setPort(2222);
            hReq.setRemark("IpSearchRemark");
            long hostId = hostService.create(hReq).getId();

            SshAccountService.SshAccountSaveRequest aReq = new SshAccountService.SshAccountSaveRequest();
            aReq.setHostId(hostId);
            aReq.setUsername("searchUser");
            aReq.setAuthType("PASSWORD");
            CredentialCipher.SshSecretPayload secret = new CredentialCipher.SshSecretPayload();
            secret.setPassword("pass123");
            aReq.setSecret(secret);
            aReq.setRemark("searchUserRemark");
            long accountId = accountService.create(aReq).getId();

            List<Map<String, Object>> targets = treeService.listSearchTargets();
            assertNotNull(targets);
            assertFalse(targets.isEmpty());

            Map<String, Object> found = targets.stream()
                    .filter(t -> "192.168.100.88".equals(t.get("hostAddress")))
                    .findFirst()
                    .orElse(null);

            assertNotNull(found, "Should find target by hostAddress (IP)");
            assertEquals("SearchTestHost", found.get("hostName"));
            assertEquals("192.168.100.88", found.get("hostAddress"));
            assertEquals(2222, ((Number) found.get("hostPort")).intValue());
            assertEquals("TestSearchGroup", found.get("groupName"));
            assertEquals("searchUser", found.get("username"));
            assertEquals("searchUserRemark", found.get("accountRemark"));
            assertEquals(accountId, ((Number) found.get("accountId")).longValue());
            assertEquals(hostId, ((Number) found.get("hostId")).longValue());
        } finally {
            groupService.delete(groupId);
        }
    }
}
