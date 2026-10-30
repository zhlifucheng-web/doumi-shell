package com.doumiot.sshconsole.service;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.config.ConfigurationArchive;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.doumiot.sshconsole.model.SshAuthType;
import com.doumiot.sshconsole.ssh.SshClientFactory;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
@SshConsoleEnabled
public class SshRemoteBackupService {

    private static final long SINGLETON_ID = 1L;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Resource private JdbcTemplate jdbc;
    @Resource private SshConfigurationService configurationService;
    @Resource private CredentialCipher cipher;
    @Resource private SshClientFactory sshClientFactory;
    @Resource private TaskScheduler taskScheduler;
    @Resource private ObjectMapper json;
    @Resource private com.doumiot.sshconsole.update.UpdateMaintenance updateMaintenance;

    private ScheduledFuture<?> scheduledFuture;
    private String currentScheduleSignature;
    private volatile boolean ready;

    @Data
    public static class BackupSecrets {
        private String backupPassword;
        private String minioSecretKey;
        private String sftpPassword;
        private String sftpPrivateKeyPem;
        private String sftpPassphrase;
    }

    @Data
    public static class RemoteBackupConfigDTO {
        private boolean enabled;
        private String targetType = "MINIO"; // MINIO | SFTP
        private String cron = "0 0 2 * * *";
        private String zone = "Asia/Shanghai";
        private boolean encrypted = true;

        // Write-only password/secrets (if blank when saving, keep existing)
        private String backupPassword;
        private boolean clearBackupPassword;
        private boolean hasBackupPassword;

        // MinIO
        private String minioEndpoint;
        private String minioBucket;
        private String minioAccessKey;
        private String minioSecretKey;
        private boolean clearMinioSecretKey;
        private boolean hasMinioSecretKey;
        private String minioRegion;
        private String minioPath;

        // SFTP
        private String sftpHost;
        private Integer sftpPort = 22;
        private String sftpUsername;
        private String sftpAuthType = "PASSWORD"; // PASSWORD | PRIVATE_KEY | PRIVATE_KEY_WITH_PASSPHRASE
        private String sftpPassword;
        private boolean clearSftpPassword;
        private boolean hasSftpPassword;
        private String sftpPrivateKeyPem;
        private boolean clearSftpPrivateKey;
        private boolean hasSftpPrivateKey;
        private String sftpPassphrase;
        private boolean clearSftpPassphrase;
        private boolean hasSftpPassphrase;
        private String sftpRemoteDir;

        // Status & Next Run
        private String lastBackupAt;
        private String lastStatus;
        private String lastMessage;
        private String nextRunAt;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS ssh_backup_config (" +
                "id INTEGER PRIMARY KEY," +
                "enabled INTEGER NOT NULL DEFAULT 0," +
                "target_type TEXT NOT NULL DEFAULT 'MINIO'," +
                "cron TEXT NOT NULL DEFAULT '0 0 2 * * *'," +
                "zone TEXT NOT NULL DEFAULT 'Asia/Shanghai'," +
                "encrypted INTEGER NOT NULL DEFAULT 1," +
                "minio_endpoint TEXT," +
                "minio_bucket TEXT," +
                "minio_access_key TEXT," +
                "minio_region TEXT," +
                "minio_path TEXT," +
                "sftp_host TEXT," +
                "sftp_port INTEGER DEFAULT 22," +
                "sftp_username TEXT," +
                "sftp_auth_type TEXT DEFAULT 'PASSWORD'," +
                "sftp_remote_dir TEXT," +
                "secret_enc BLOB," +
                "last_backup_at TEXT," +
                "last_status TEXT," +
                "last_message TEXT," +
                "updated_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')))");

        Integer count = jdbc.queryForObject("SELECT count(*) FROM ssh_backup_config WHERE id = ?", Integer.class, SINGLETON_ID);
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO ssh_backup_config (id, enabled, target_type, cron, zone, encrypted) VALUES (?, 0, 'MINIO', '0 0 2 * * *', 'Asia/Shanghai', 1)",
                    SINGLETON_ID);
        }
    }

    @Order(4)
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ready = true;
        reconcileSchedule();
    }

    @PreDestroy
    public synchronized void stop() {
        ready = false;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    public synchronized RemoteBackupConfigDTO getConfig() {
        List<RemoteBackupConfigDTO> list = jdbc.query("SELECT * FROM ssh_backup_config WHERE id = ?", (rs, rowNum) -> {
            RemoteBackupConfigDTO dto = new RemoteBackupConfigDTO();
            dto.setEnabled(rs.getInt("enabled") == 1);
            dto.setTargetType(rs.getString("target_type"));
            dto.setCron(rs.getString("cron"));
            dto.setZone(rs.getString("zone"));
            dto.setEncrypted(rs.getInt("encrypted") == 1);
            dto.setMinioEndpoint(rs.getString("minio_endpoint"));
            dto.setMinioBucket(rs.getString("minio_bucket"));
            dto.setMinioAccessKey(rs.getString("minio_access_key"));
            dto.setMinioRegion(rs.getString("minio_region"));
            dto.setMinioPath(rs.getString("minio_path"));
            dto.setSftpHost(rs.getString("sftp_host"));
            int port = rs.getInt("sftp_port");
            dto.setSftpPort(rs.wasNull() ? 22 : port);
            dto.setSftpUsername(rs.getString("sftp_username"));
            dto.setSftpAuthType(rs.getString("sftp_auth_type"));
            dto.setSftpRemoteDir(rs.getString("sftp_remote_dir"));
            dto.setLastBackupAt(rs.getString("last_backup_at"));
            dto.setLastStatus(rs.getString("last_status"));
            dto.setLastMessage(rs.getString("last_message"));

            byte[] secretBytes = rs.getBytes("secret_enc");
            BackupSecrets secrets = loadSecrets(secretBytes);
            dto.setHasBackupPassword(StringUtils.hasText(secrets.getBackupPassword()));
            dto.setHasMinioSecretKey(StringUtils.hasText(secrets.getMinioSecretKey()));
            dto.setHasSftpPassword(StringUtils.hasText(secrets.getSftpPassword()));
            dto.setHasSftpPrivateKey(StringUtils.hasText(secrets.getSftpPrivateKeyPem()));
            dto.setHasSftpPassphrase(StringUtils.hasText(secrets.getSftpPassphrase()));

            return dto;
        }, SINGLETON_ID);

        if (list.isEmpty()) {
            return new RemoteBackupConfigDTO();
        }

        RemoteBackupConfigDTO dto = list.get(0);
        if (dto.isEnabled() && StringUtils.hasText(dto.getCron())) {
            try {
                List<String> nextRuns = SshScriptScheduleService.preview(dto.getCron(), dto.getZone());
                if (!nextRuns.isEmpty()) {
                    dto.setNextRunAt(nextRuns.get(0));
                }
            } catch (Exception ignored) {
            }
        }
        return dto;
    }

    public synchronized void saveConfig(RemoteBackupConfigDTO request) {
        if (request == null) throw invalid("配置请求不能为空");

        String targetType = "SFTP".equalsIgnoreCase(request.getTargetType()) ? "SFTP" : "MINIO";
        String cron = SshScriptScheduleService.normalizeCron(request.getCron());
        String zone = StringUtils.hasText(request.getZone()) ? request.getZone().trim() : "Asia/Shanghai";
        SshScriptScheduleService.preview(cron, zone);

        // Load existing secrets
        byte[] oldEnc = jdbc.queryForObject("SELECT secret_enc FROM ssh_backup_config WHERE id = ?", byte[].class, SINGLETON_ID);
        BackupSecrets secrets = loadSecrets(oldEnc);

        // Update secrets if provided
        if (request.isClearBackupPassword()) {
            secrets.setBackupPassword(null);
        } else if (StringUtils.hasText(request.getBackupPassword())) {
            if (request.getBackupPassword().length() < 8 || request.getBackupPassword().length() > 1024) {
                throw invalid("备份加密密码长度须为 8 至 1024 个字符");
            }
            secrets.setBackupPassword(request.getBackupPassword());
        }

        if (request.isClearMinioSecretKey()) {
            secrets.setMinioSecretKey(null);
        } else if (StringUtils.hasText(request.getMinioSecretKey())) {
            secrets.setMinioSecretKey(request.getMinioSecretKey().trim());
        }

        if (request.isClearSftpPassword()) {
            secrets.setSftpPassword(null);
        } else if (StringUtils.hasText(request.getSftpPassword())) {
            secrets.setSftpPassword(request.getSftpPassword());
        }

        if (request.isClearSftpPrivateKey()) {
            secrets.setSftpPrivateKeyPem(null);
        } else if (StringUtils.hasText(request.getSftpPrivateKeyPem())) {
            secrets.setSftpPrivateKeyPem(request.getSftpPrivateKeyPem());
        }

        if (request.isClearSftpPassphrase()) {
            secrets.setSftpPassphrase(null);
        } else if (StringUtils.hasText(request.getSftpPassphrase())) {
            secrets.setSftpPassphrase(request.getSftpPassphrase());
        }

        if (request.isEnabled()) {
            validateTargetRequirements(targetType, request, secrets);
            if (request.isEncrypted() && !StringUtils.hasText(secrets.getBackupPassword())) {
                throw invalid("已启用加密备份，请提供至少 8 个字符的备份密码");
            }
        }

        byte[] newEnc = encryptSecrets(secrets);

        jdbc.update("UPDATE ssh_backup_config SET " +
                        "enabled = ?, target_type = ?, cron = ?, zone = ?, encrypted = ?, " +
                        "minio_endpoint = ?, minio_bucket = ?, minio_access_key = ?, minio_region = ?, minio_path = ?, " +
                        "sftp_host = ?, sftp_port = ?, sftp_username = ?, sftp_auth_type = ?, sftp_remote_dir = ?, " +
                        "secret_enc = ?, updated_at = datetime('now', 'localtime') WHERE id = ?",
                request.isEnabled() ? 1 : 0,
                targetType,
                cron,
                zone,
                request.isEncrypted() ? 1 : 0,
                cleanString(request.getMinioEndpoint()),
                cleanString(request.getMinioBucket()),
                cleanString(request.getMinioAccessKey()),
                cleanString(request.getMinioRegion()),
                cleanString(request.getMinioPath()),
                cleanString(request.getSftpHost()),
                request.getSftpPort() != null ? request.getSftpPort() : 22,
                cleanString(request.getSftpUsername()),
                cleanString(request.getSftpAuthType()),
                cleanString(request.getSftpRemoteDir()),
                newEnc,
                SINGLETON_ID
        );

        reconcileSchedule();
    }

    public synchronized Map<String, Object> testConnection(RemoteBackupConfigDTO request) {
        if (request == null) throw invalid("测试请求不能为空");
        String targetType = "SFTP".equalsIgnoreCase(request.getTargetType()) ? "SFTP" : "MINIO";

        byte[] oldEnc = jdbc.queryForObject("SELECT secret_enc FROM ssh_backup_config WHERE id = ?", byte[].class, SINGLETON_ID);
        BackupSecrets secrets = loadSecrets(oldEnc);

        // Merge request secrets
        if (StringUtils.hasText(request.getMinioSecretKey())) {
            secrets.setMinioSecretKey(request.getMinioSecretKey().trim());
        }
        if (StringUtils.hasText(request.getSftpPassword())) {
            secrets.setSftpPassword(request.getSftpPassword());
        }
        if (StringUtils.hasText(request.getSftpPrivateKeyPem())) {
            secrets.setSftpPrivateKeyPem(request.getSftpPrivateKeyPem());
        }
        if (StringUtils.hasText(request.getSftpPassphrase())) {
            secrets.setSftpPassphrase(request.getSftpPassphrase());
        }

        validateTargetRequirements(targetType, request, secrets);

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if ("MINIO".equals(targetType)) {
                testMinioConnection(request, secrets);
                result.put("success", true);
                result.put("message", "MinIO 存储服务连接与 Bucket 访问测试成功！");
            } else {
                testSftpConnection(request, secrets);
                result.put("success", true);
                result.put("message", "SFTP 服务器连接与远程目录检测成功！");
            }
        } catch (Exception e) {
            log.warn("远程备份连接测试失败: {}", e.getMessage(), e);
            throw invalid("连接测试失败: " + e.getMessage());
        }
        return result;
    }

    public synchronized Map<String, Object> executeBackupNow() {
        if(updateMaintenance!=null&&!updateMaintenance.enter())throw new IllegalStateException("正在更新，暂不执行远程备份");
        try {
        RemoteBackupConfigDTO config = getConfig();
        byte[] enc = jdbc.queryForObject("SELECT secret_enc FROM ssh_backup_config WHERE id = ?", byte[].class, SINGLETON_ID);
        BackupSecrets secrets = loadSecrets(enc);

        validateTargetRequirements(config.getTargetType(), config, secrets);
        if (config.isEncrypted() && !StringUtils.hasText(secrets.getBackupPassword())) {
            throw invalid("已启用加密备份，但未设置备份密码，请先在配置中保存备份密码");
        }

        String nowStr = LocalDateTime.now().format(TIME_FORMATTER);
        try {
            // 1. Export configuration
            ConfigurationArchive.Archive archive = configurationService.exportAll(
                    secrets.getBackupPassword(),
                    config.isEncrypted()
            );
            byte[] data = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(archive);

            // 2. Generate backup filename
            String fileTimestamp = LocalDateTime.now().format(FILE_TIME_FORMATTER);
            String filename = "doumi-shell-backup-" + fileTimestamp + ".json";

            // 3. Upload to target
            String message;
            if ("MINIO".equalsIgnoreCase(config.getTargetType())) {
                message = uploadToMinio(config, secrets, filename, data);
            } else {
                message = uploadToSftp(config, secrets, filename, data);
            }

            jdbc.update("UPDATE ssh_backup_config SET last_backup_at = ?, last_status = 'SUCCESS', last_message = ? WHERE id = ?",
                    nowStr, message, SINGLETON_ID);

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("message", message);
            res.put("backupAt", nowStr);
            return res;
        } catch (Exception e) {
            String errorMsg = "备份失败: " + e.getMessage();
            log.error("执行远程定时备份失败: {}", e.getMessage(), e);
            jdbc.update("UPDATE ssh_backup_config SET last_backup_at = ?, last_status = 'FAILED', last_message = ? WHERE id = ?",
                    nowStr, errorMsg, SINGLETON_ID);
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, errorMsg);
        }
        } finally { if(updateMaintenance!=null)updateMaintenance.leave(); }
    }

    private synchronized void reconcileSchedule() {
        if (!ready) return;

        RemoteBackupConfigDTO config = getConfig();
        if (!config.isEnabled()) {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
                scheduledFuture = null;
                currentScheduleSignature = null;
                log.info("远程定时备份计划已停用");
            }
            return;
        }

        String cron = SshScriptScheduleService.normalizeCron(config.getCron());
        String zone = StringUtils.hasText(config.getZone()) ? config.getZone().trim() : "Asia/Shanghai";
        String signature = cron + "|" + zone + "|" + config.getTargetType() + "|" + config.isEncrypted();

        if (signature.equals(currentScheduleSignature) && scheduledFuture != null && !scheduledFuture.isDone()) {
            return;
        }

        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }

        try {
            CronTrigger trigger = new CronTrigger(cron, SshScriptScheduleService.timeZone(zone));
            scheduledFuture = taskScheduler.schedule(this::dispatchScheduledBackup, trigger);
            currentScheduleSignature = signature;
            log.info("已注册远程定时备份任务: cron='{}', zone='{}', target={}", cron, zone, config.getTargetType());
        } catch (Exception e) {
            log.error("注册远程定时备份任务失败: {}", e.getMessage(), e);
            jdbc.update("UPDATE ssh_backup_config SET last_status = 'FAILED', last_message = ? WHERE id = ?",
                    "调度注册失败: " + e.getMessage(), SINGLETON_ID);
        }
    }

    private void dispatchScheduledBackup() {
        log.info("开始触发执行远程定时备份...");
        try {
            executeBackupNow();
            log.info("远程定时备份调度执行成功");
        } catch (Exception e) {
            log.error("调度执行远程定时备份失败: {}", e.getMessage(), e);
        }
    }

    private void testMinioConnection(RemoteBackupConfigDTO config, BackupSecrets secrets) throws Exception {
        MinioClient client = createMinioClient(config, secrets);
        String bucket = config.getMinioBucket().trim();
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            try {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            } catch (Exception e) {
                throw new IllegalStateException("存储桶 [" + bucket + "] 不存在且无法自动创建: " + e.getMessage(), e);
            }
        }
    }

    private String uploadToMinio(RemoteBackupConfigDTO config, BackupSecrets secrets, String filename, byte[] data) throws Exception {
        MinioClient client = createMinioClient(config, secrets);
        String bucket = config.getMinioBucket().trim();
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }

        String prefix = config.getMinioPath() != null ? config.getMinioPath().trim().replace('\\', '/') : "";
        while (prefix.startsWith("/")) prefix = prefix.substring(1);
        if (prefix.length() > 0 && !prefix.endsWith("/")) prefix += "/";
        String objectName = prefix + filename;

        client.putObject(
                PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectName)
                        .stream(new ByteArrayInputStream(data), data.length, -1)
                        .contentType("application/json")
                        .build()
        );

        return String.format("成功备份至 MinIO [Bucket: %s, 对象: %s] (大小: %s)",
                bucket, objectName, formatFileSize(data.length));
    }

    private MinioClient createMinioClient(RemoteBackupConfigDTO config, BackupSecrets secrets) {
        String endpoint = config.getMinioEndpoint();
        if (!StringUtils.hasText(endpoint)) throw invalid("请填写 MinIO Endpoint 服务地址");
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            endpoint = "http://" + endpoint;
        }

        String accessKey = config.getMinioAccessKey();
        String secretKey = secrets.getMinioSecretKey();
        if (!StringUtils.hasText(accessKey)) throw invalid("请填写 MinIO Access Key");
        if (!StringUtils.hasText(secretKey)) throw invalid("请填写 MinIO Secret Key");
        if (!StringUtils.hasText(config.getMinioBucket())) throw invalid("请填写 MinIO 存储桶 Bucket 名称");

        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey.trim(), secretKey.trim());

        if (StringUtils.hasText(config.getMinioRegion())) {
            builder.region(config.getMinioRegion().trim());
        }
        return builder.build();
    }

    private void testSftpConnection(RemoteBackupConfigDTO config, BackupSecrets secrets) throws Exception {
        CredentialCipher.SshSecretPayload payload = new CredentialCipher.SshSecretPayload();
        payload.setPassword(secrets.getSftpPassword());
        payload.setPrivateKeyPem(secrets.getSftpPrivateKeyPem());
        payload.setPassphrase(secrets.getSftpPassphrase());

        SSHClient ssh = sshClientFactory.connectAndAuth(
                config.getSftpHost(),
                config.getSftpPort() != null ? config.getSftpPort() : 22,
                config.getSftpUsername(),
                config.getSftpAuthType(),
                payload
        );
        try (SFTPClient sftp = ssh.newSFTPClient()) {
            String remoteDir = normalizeSftpDir(config.getSftpRemoteDir());
            if (StringUtils.hasText(remoteDir)) {
                sftp.mkdirs(remoteDir);
            }
        } finally {
            sshClientFactory.disconnectQuietly(ssh);
        }
    }

    private String uploadToSftp(RemoteBackupConfigDTO config, BackupSecrets secrets, String filename, byte[] data) throws Exception {
        CredentialCipher.SshSecretPayload payload = new CredentialCipher.SshSecretPayload();
        payload.setPassword(secrets.getSftpPassword());
        payload.setPrivateKeyPem(secrets.getSftpPrivateKeyPem());
        payload.setPassphrase(secrets.getSftpPassphrase());

        SSHClient ssh = sshClientFactory.connectAndAuth(
                config.getSftpHost(),
                config.getSftpPort() != null ? config.getSftpPort() : 22,
                config.getSftpUsername(),
                config.getSftpAuthType(),
                payload
        );
        String remotePath;
        try (SFTPClient sftp = ssh.newSFTPClient()) {
            String remoteDir = normalizeSftpDir(config.getSftpRemoteDir());
            if (StringUtils.hasText(remoteDir)) {
                sftp.mkdirs(remoteDir);
                remotePath = remoteDir.endsWith("/") ? remoteDir + filename : remoteDir + "/" + filename;
            } else {
                remotePath = filename;
            }

            try (RemoteFile file = sftp.open(remotePath, EnumSet.of(OpenMode.CREAT, OpenMode.WRITE, OpenMode.TRUNC))) {
                try (OutputStream out = file.new RemoteFileOutputStream()) {
                    out.write(data);
                    out.flush();
                }
            }
        } finally {
            sshClientFactory.disconnectQuietly(ssh);
        }

        return String.format("成功备份至 SFTP 服务器 [%s:%s] (大小: %s)",
                config.getSftpHost(), remotePath, formatFileSize(data.length));
    }

    private static String normalizeSftpDir(String dir) {
        if (!StringUtils.hasText(dir)) return "";
        String normalized = dir.trim().replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void validateTargetRequirements(String targetType, RemoteBackupConfigDTO config, BackupSecrets secrets) {
        if ("MINIO".equalsIgnoreCase(targetType)) {
            if (!StringUtils.hasText(config.getMinioEndpoint())) throw invalid("MinIO Endpoint 服务地址不能为空");
            if (!StringUtils.hasText(config.getMinioBucket())) throw invalid("MinIO Bucket 存储桶不能为空");
            if (!StringUtils.hasText(config.getMinioAccessKey())) throw invalid("MinIO Access Key 不能为空");
            if (!StringUtils.hasText(secrets.getMinioSecretKey())) throw invalid("MinIO Secret Key 不能为空");
        } else if ("SFTP".equalsIgnoreCase(targetType)) {
            if (!StringUtils.hasText(config.getSftpHost())) throw invalid("SFTP 主机地址不能为空");
            if (config.getSftpPort() == null || config.getSftpPort() <= 0 || config.getSftpPort() > 65535) {
                throw invalid("SFTP 端口须在 1 到 65535 之间");
            }
            if (!StringUtils.hasText(config.getSftpUsername())) throw invalid("SFTP 用户名不能为空");
            String authType = config.getSftpAuthType();
            if (SshAuthType.PASSWORD.equals(authType)) {
                if (!StringUtils.hasText(secrets.getSftpPassword())) throw invalid("SFTP 密码不能为空");
            } else if (SshAuthType.PRIVATE_KEY.equals(authType) || SshAuthType.PRIVATE_KEY_WITH_PASSPHRASE.equals(authType)) {
                if (!StringUtils.hasText(secrets.getSftpPrivateKeyPem())) throw invalid("SFTP 私钥不能为空");
            }
        } else {
            throw invalid("不支持的目标类型: " + targetType);
        }
    }

    private BackupSecrets loadSecrets(byte[] enc) {
        if (enc == null || enc.length == 0) return new BackupSecrets();
        try {
            String jsonStr = cipher.decryptToJson(enc);
            return json.readValue(jsonStr, BackupSecrets.class);
        } catch (Exception e) {
            log.warn("无法解密备份凭据: {}", e.getMessage());
            return new BackupSecrets();
        }
    }

    private byte[] encryptSecrets(BackupSecrets secrets) {
        try {
            return cipher.encryptSecretJson(json.writeValueAsString(secrets));
        } catch (Exception e) {
            throw invalid("加密凭据失败: " + e.getMessage());
        }
    }

    private static String cleanString(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.ROOT, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private static BusinessRuntimeException invalid(String message) {
        return new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, message);
    }
}
