package com.doumiot.sshconsole.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doumiot.sshconsole.entity.SshAccount;
import com.doumiot.sshconsole.entity.SshGroup;
import com.doumiot.sshconsole.entity.SshHost;
import com.doumiot.sshconsole.mapper.SshAccountMapper;
import com.doumiot.sshconsole.mapper.SshGroupMapper;
import com.doumiot.sshconsole.mapper.SshHostMapper;
import com.doumiot.sshconsole.service.SshGroupService.SshGroupSaveRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        properties = {
                "doumi.storage.data-dir=./target/test-data-finalshell/${random.uuid}",
                "doumi.ssh-console.credential-master-key=",
                "logging.level.root=WARN",
                "server.port=0"
        }
)
public class FinalShellImportServiceTest {

    @Autowired
    private FinalShellImportService importService;

    @Autowired
    private SshGroupService groupService;

    @Autowired
    private SshAccountService accountService;

    @Autowired
    private SshGroupMapper groupMapper;

    @Autowired
    private SshHostMapper hostMapper;

    @Autowired
    private SshAccountMapper accountMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM ssh_account");
        jdbc.update("DELETE FROM ssh_host");
        jdbc.update("DELETE FROM ssh_group");
    }

    @Test
    void testImportSingleJson() throws Exception {
        SshGroupSaveRequest groupReq = new SshGroupSaveRequest();
        groupReq.setName("TargetGroup");
        SshGroup targetGroup = groupService.create(groupReq);

        String jsonConfig = "{\n" +
                "  \"name\": \"TestServer\",\n" +
                "  \"host\": \"192.168.10.50\",\n" +
                "  \"port\": 2222,\n" +
                "  \"user_name\": \"testuser\",\n" +
                "  \"password\": \"MTlSVHgIIzDGSsDjK+Zz+APwnPHttcHj\",\n" +
                "  \"description\": \"测试描述\",\n" +
                "  \"terminal_encoding\": \"UTF-8\"\n" +
                "}";

        FinalShellImportService.FinalShellImportResult result = importService.importFromBytes(
                jsonConfig.getBytes(StandardCharsets.UTF_8),
                "server_connect_config.json",
                targetGroup.getId(),
                false
        );

        assertEquals(1, result.getTotalFiles());
        assertEquals(1, result.getImportedHosts());
        assertEquals(1, result.getImportedAccounts());
        assertEquals(0, result.getFailedCount());

        List<SshHost> hosts = hostMapper.selectList(new LambdaQueryWrapper<SshHost>().eq(SshHost::getGroupId, targetGroup.getId()));
        assertEquals(1, hosts.size());
        SshHost h = hosts.get(0);
        assertEquals("TestServer", h.getName());
        assertEquals("192.168.10.50", h.getHost());
        assertEquals(2222, h.getPort());

        List<SshAccount> accounts = accountMapper.selectList(new LambdaQueryWrapper<SshAccount>().eq(SshAccount::getHostId, h.getId()));
        assertEquals(1, accounts.size());
        SshAccount a = accounts.get(0);
        assertEquals("testuser", a.getUsername());
        // Verify decrypted password matches
        assertEquals("hs@home123", accountService.loadSecret(a).getPassword());
    }

    @Test
    void testImportZipArchiveWithHierarchy() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Folder 1: Dev/Backend
            ZipEntry e1 = new ZipEntry("finalshell/Dev/Backend/10.0.0.1_connect_config.json");
            zos.putNextEntry(e1);
            String json1 = "{\"name\":\"Backend1\",\"host\":\"10.0.0.1\",\"port\":22,\"user_name\":\"root\",\"password\":\"MTlSVHgIIzDGSsDjK+Zz+APwnPHttcHj\"}";
            zos.write(json1.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // Folder 2: Dev/Frontend
            ZipEntry e2 = new ZipEntry("finalshell/Dev/Frontend/10.0.0.2_connect_config.json");
            zos.putNextEntry(e2);
            String json2 = "{\"name\":\"Frontend1\",\"host\":\"10.0.0.2\",\"port\":22,\"user_name\":\"root\",\"password\":\"\"}";
            zos.write(json2.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        FinalShellImportService.FinalShellImportResult result = importService.importFromBytes(
                baos.toByteArray(),
                "export.zip",
                null,
                false
        );

        assertEquals(2, result.getTotalFiles());
        assertEquals(2, result.getImportedHosts());
        assertEquals(2, result.getImportedAccounts());
        assertEquals(0, result.getFailedCount());

        // Check group Dev and sub-groups
        List<SshGroup> rootGroups = groupMapper.selectList(new LambdaQueryWrapper<SshGroup>().isNull(SshGroup::getParentId));
        assertEquals(1, rootGroups.size());
        assertEquals("Dev", rootGroups.get(0).getName());

        List<SshGroup> subGroups = groupMapper.selectList(new LambdaQueryWrapper<SshGroup>().eq(SshGroup::getParentId, rootGroups.get(0).getId()));
        assertEquals(2, subGroups.size());
    }

    @Test
    void testImportFromUserLocalDirectoryIfExists() throws Exception {
        File dir = new File("/Users/pino/Downloads/finalshell");
        if (!dir.exists() || !dir.isDirectory()) {
            return; // Skip if directory not on machine
        }

        SshGroupSaveRequest targetReq = new SshGroupSaveRequest();
        targetReq.setName("FinalShell导入总目录");
        SshGroup targetGroup = groupService.create(targetReq);

        FinalShellImportService.FinalShellImportResult result = importService.importFromLocalPath(
                dir.getAbsolutePath(),
                targetGroup.getId(),
                false
        );

        assertEquals(98, result.getTotalFiles());
        assertEquals(95, result.getImportedHosts());
        assertEquals(96, result.getImportedAccounts());
        assertEquals(0, result.getFailedCount());

        // Check sub-groups under targetGroup: "测试环境" and "个人测试环境"
        List<SshGroup> firstLevel = groupMapper.selectList(new LambdaQueryWrapper<SshGroup>().eq(SshGroup::getParentId, targetGroup.getId()));
        assertEquals(2, firstLevel.size());

        // Check host 10.20.199.171 under PB
        List<SshHost> pbHosts = hostMapper.selectList(new LambdaQueryWrapper<SshHost>().eq(SshHost::getHost, "10.20.199.171"));
        assertEquals(1, pbHosts.size());
        SshHost pbHost = pbHosts.get(0);
        List<SshAccount> pbAccounts = accountMapper.selectList(new LambdaQueryWrapper<SshAccount>().eq(SshAccount::getHostId, pbHost.getId()));
        assertEquals(1, pbAccounts.size());
        assertEquals("root", pbAccounts.get(0).getUsername());
        assertEquals("hs@home123", accountService.loadSecret(pbAccounts.get(0)).getPassword());

        // Check host 10.20.45.146 has both accounts ufrdb and root
        List<SshHost> multiHosts = hostMapper.selectList(new LambdaQueryWrapper<SshHost>().eq(SshHost::getHost, "10.20.45.146"));
        assertEquals(1, multiHosts.size());
        List<SshAccount> multiAccounts = accountMapper.selectList(new LambdaQueryWrapper<SshAccount>().eq(SshAccount::getHostId, multiHosts.get(0).getId()));
        assertEquals(2, multiAccounts.size());
    }
}
