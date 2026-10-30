package com.doumiot;

import com.doumiot.sshconsole.controller.SshGroupController;
import com.doumiot.sshconsole.controller.SshHostController;
import com.doumiot.sshconsole.controller.SshAccountController;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.doumiot.sshconsole.rdp.RdpConnectionService;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired private RdpConnectionService rdp;
    @Autowired private JdbcTemplate jdbc;

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
    @Test
    public void searchTargetsIncludesRdpNameProtocolAndIndependentPortWithoutSecrets() {
        SshGroupService.SshGroupSaveRequest group = new SshGroupService.SshGroupSaveRequest();
        group.setName("DesktopSearch");
        long groupId = groupService.create(group).getId();
        try {
            SshHostService.SshHostSaveRequest host = new SshHostService.SshHostSaveRequest();
            host.setGroupId(groupId); host.setName("Windows host"); host.setHost("192.0.2.88"); host.setPort(2222);
            long hostId = hostService.create(host).getId();
            RdpConnectionService.SaveRequest request = new RdpConnectionService.SaveRequest();
            request.setHostId(hostId); request.setName("Finance desktop"); request.setUsername("Administrator");
            request.setPassword("synthetic-rdp-password"); request.setPort(3390); request.setRemark("Searchable desktop remark");
            long id = rdp.create(request).getId();
            List<Map<String,Object>> rows = treeService.listSearchTargets().stream()
                    .filter(t -> ((Number)t.get("hostId")).longValue()==hostId).collect(java.util.stream.Collectors.toList());
            assertEquals(1, rows.size(), "A host with an RDP account must not be reported as having no account");
            Map<String,Object> found=rows.get(0);
            assertNotNull(found.get("accountId"), "RDP account must be included in search targets");
            assertEquals(id, ((Number)found.get("accountId")).longValue());
            assertEquals("RDP", found.get("protocol"));
            assertEquals("Finance desktop", found.get("accountName"));
            assertEquals("Administrator", found.get("username"));
            assertEquals(3390, ((Number)found.get("hostPort")).intValue());
            assertEquals("Searchable desktop remark", found.get("accountRemark"));
            assertFalse(found.containsKey("password")); assertFalse(found.containsKey("secret_enc"));
            assertFalse(found.toString().contains("synthetic-rdp-password"));

            SshAccountService.SshAccountSaveRequest ssh = new SshAccountService.SshAccountSaveRequest();
            ssh.setHostId(hostId); ssh.setUsername("root"); ssh.setAuthType("PASSWORD");
            CredentialCipher.SshSecretPayload secret=new CredentialCipher.SshSecretPayload(); secret.setPassword("synthetic-ssh-password"); ssh.setSecret(secret);
            long sshId=accountService.create(ssh).getId();
            Map<String,Object> sshTarget=treeService.listSearchTargets().stream()
                    .filter(t -> "SSH".equals(t.get("protocol")) && t.get("accountId")!=null && ((Number)t.get("accountId")).longValue()==sshId).findFirst().orElseThrow();
            assertEquals(2222, ((Number)sshTarget.get("hostPort")).intValue());
            assertEquals(2, treeService.listSearchTargets().stream().filter(t -> ((Number)t.get("hostId")).longValue()==hostId).count());

            request.setEnabled(0); rdp.update(id,request);
            assertFalse(treeService.listSearchTargets().stream().anyMatch(t -> "RDP".equals(t.get("protocol")) && ((Number)t.get("accountId")).longValue()==id));
            request.setEnabled(1); rdp.update(id,request);
            jdbc.update("UPDATE ssh_host SET enabled=0 WHERE id=?",hostId);
            assertFalse(treeService.listSearchTargets().stream().anyMatch(t -> ((Number)t.get("hostId")).longValue()==hostId));
        } finally { groupService.delete(groupId); }
    }

}
