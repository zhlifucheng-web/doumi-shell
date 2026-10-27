package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.service.SshRemoteBackupService.RemoteBackupConfigDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        properties = {
                "doumi.storage.data-dir=./target/test-data-backup/${random.uuid}",
                "doumi.ssh-console.credential-master-key=",
                "logging.level.root=WARN",
                "server.port=0"
        }
)
public class SshRemoteBackupServiceTest {

    @Autowired
    private SshRemoteBackupService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM ssh_backup_config");
        service.initialize();
    }

    @Test
    void testDefaultConfigAndSaveMinioConfig() {
        RemoteBackupConfigDTO config = service.getConfig();
        assertNotNull(config);
        assertFalse(config.isEnabled());
        assertEquals("MINIO", config.getTargetType());

        // Update config with MinIO details
        RemoteBackupConfigDTO update = new RemoteBackupConfigDTO();
        update.setEnabled(false);
        update.setTargetType("MINIO");
        update.setCron("0 0 3 * * *");
        update.setZone("Asia/Shanghai");
        update.setEncrypted(true);
        update.setBackupPassword("myBackupPass123");
        update.setMinioEndpoint("http://localhost:9000");
        update.setMinioBucket("doumi-backup");
        update.setMinioAccessKey("admin");
        update.setMinioSecretKey("admin123456");
        update.setMinioPath("web-shell/backups");

        service.saveConfig(update);

        RemoteBackupConfigDTO saved = service.getConfig();
        assertNotNull(saved);
        assertEquals("MINIO", saved.getTargetType());
        assertEquals("0 0 3 * * *", saved.getCron());
        assertTrue(saved.isEncrypted());
        assertEquals("http://localhost:9000", saved.getMinioEndpoint());
        assertEquals("doumi-backup", saved.getMinioBucket());
        assertEquals("admin", saved.getMinioAccessKey());
        assertEquals("web-shell/backups", saved.getMinioPath());

        // Verify secrets are masked
        assertTrue(saved.isHasBackupPassword());
        assertTrue(saved.isHasMinioSecretKey());
        assertNull(saved.getBackupPassword());
        assertNull(saved.getMinioSecretKey());

        // Now update without changing secret keys (keep existing)
        RemoteBackupConfigDTO update2 = new RemoteBackupConfigDTO();
        update2.setEnabled(false);
        update2.setTargetType("MINIO");
        update2.setCron("0 0 4 * * *");
        update2.setZone("Asia/Shanghai");
        update2.setEncrypted(true);
        update2.setMinioEndpoint("http://localhost:9000");
        update2.setMinioBucket("doumi-backup-2");
        update2.setMinioAccessKey("admin2");
        // leave backupPassword and minioSecretKey blank

        service.saveConfig(update2);

        RemoteBackupConfigDTO saved2 = service.getConfig();
        assertEquals("0 0 4 * * *", saved2.getCron());
        assertEquals("doumi-backup-2", saved2.getMinioBucket());
        assertEquals("admin2", saved2.getMinioAccessKey());
        assertTrue(saved2.isHasBackupPassword());
        assertTrue(saved2.isHasMinioSecretKey());
    }

    @Test
    void testSaveSftpConfig() {
        RemoteBackupConfigDTO update = new RemoteBackupConfigDTO();
        update.setEnabled(false);
        update.setTargetType("SFTP");
        update.setCron("0 30 1 * * ?");
        update.setZone("Asia/Shanghai");
        update.setEncrypted(false);
        update.setSftpHost("192.168.1.100");
        update.setSftpPort(2222);
        update.setSftpUsername("backupuser");
        update.setSftpAuthType("PASSWORD");
        update.setSftpPassword("sftpPass123");
        update.setSftpRemoteDir("/data/backups");

        service.saveConfig(update);

        RemoteBackupConfigDTO saved = service.getConfig();
        assertEquals("SFTP", saved.getTargetType());
        assertEquals("0 30 1 * * ?", saved.getCron());
        assertFalse(saved.isEncrypted());
        assertEquals("192.168.1.100", saved.getSftpHost());
        assertEquals(2222, saved.getSftpPort());
        assertEquals("backupuser", saved.getSftpUsername());
        assertEquals("/data/backups", saved.getSftpRemoteDir());
        assertTrue(saved.isHasSftpPassword());
        assertNull(saved.getSftpPassword());
    }

    @Test
    void testValidationWhenEnabled() {
        RemoteBackupConfigDTO update = new RemoteBackupConfigDTO();
        update.setEnabled(true);
        update.setTargetType("MINIO");
        update.setCron("0 0 2 * * *");
        update.setMinioEndpoint(""); // empty endpoint

        Exception err = assertThrows(Exception.class, () -> service.saveConfig(update));
        assertTrue(err.getMessage().contains("MinIO Endpoint"));
    }
}
