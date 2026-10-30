package com.doumiot.sshconsole.rdp;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.doumiot.sshconsole.entity.SshHost;
import com.doumiot.sshconsole.service.SshHostService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

@SshConsoleEnabled
@Service
public class RdpConnectionService {
    private static final String SELECT = "SELECT id,host_id,name,username,domain,port,ignore_certificate,enabled,secret_enc FROM rdp_connection";
    private final JdbcTemplate jdbc;
    private final SshHostService hosts;
    private final CredentialCipher cipher;

    public RdpConnectionService(JdbcTemplate jdbc, SshHostService hosts, CredentialCipher cipher) {
        this.jdbc = jdbc;
        this.hosts = hosts;
        this.cipher = cipher;
    }

    // AUTO initialization skips the main schema on existing databases. Run this after its order-0 listener.
    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void initializeSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS rdp_connection ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,host_id INTEGER NOT NULL,name TEXT NOT NULL,"
                + "username TEXT NOT NULL,domain TEXT NOT NULL DEFAULT '',port INTEGER NOT NULL DEFAULT 3389,"
                + "ignore_certificate INTEGER NOT NULL DEFAULT 0,enabled INTEGER NOT NULL DEFAULT 1,secret_enc BLOB NOT NULL,"
                + "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),"
                + "updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),"
                + "FOREIGN KEY (host_id) REFERENCES ssh_host(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_rdp_connection_host ON rdp_connection(host_id)");
    }

    /** Included only in explicit configuration archives, never in normal connection API responses. */
    public List<BackupConnection> exportAllSnapshots() {
        return jdbc.query(SELECT + " ORDER BY id", (rs, n) -> {
            Profile row = read(rs);
            CredentialCipher.SshSecretPayload secret = CredentialCipher.parseSecretJson(cipher.decryptToJson(row.secret));
            if (secret == null || !StringUtils.hasText(secret.getPassword())) throw invalid("远程桌面密码未配置");
            BackupConnection saved = new BackupConnection();
            saved.setId(row.id); saved.setHostId(row.hostId); saved.setName(row.name); saved.setUsername(row.username);
            saved.setDomain(row.domain); saved.setPort(row.port); saved.setIgnoreCertificate(row.ignoreCertificate);
            saved.setEnabled(row.enabled); saved.setPassword(secret.getPassword());
            return saved;
        });
    }

    public List<ConnectionVO> list(long hostId) {
        requireHost(hostId);
        return jdbc.query(SELECT + " WHERE host_id=? ORDER BY id", (rs, n) -> toVo(read(rs)), hostId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ConnectionVO create(SaveRequest request) {
        if (request == null || request.getHostId() == null) throw invalid("hostId 必填");
        requireHost(request.getHostId());
        Profile row = new Profile();
        row.hostId = request.getHostId(); row.port = 3389; row.enabled = 1; row.domain = "";
        apply(row, request);
        if (!StringUtils.hasText(request.getPassword())) throw invalid("新建远程桌面须填写密码");
        row.secret = encrypt(request.getPassword());
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO rdp_connection "
                    + "(host_id,name,username,domain,port,ignore_certificate,enabled,secret_enc) VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1,row.hostId); ps.setString(2,row.name); ps.setString(3,row.username); ps.setString(4,row.domain);
            ps.setInt(5,row.port); ps.setInt(6,row.ignoreCertificate ? 1 : 0); ps.setInt(7,row.enabled); ps.setBytes(8,row.secret);
            return ps;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("远程桌面保存失败");
        row.id = key.getKey().longValue();
        return toVo(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public ConnectionVO update(long id, SaveRequest request) {
        Profile row = require(id);
        if (request == null) throw invalid("连接参数不能为空");
        if (request.getHostId() != null) row.hostId = request.getHostId();
        requireHost(row.hostId);
        apply(row, request);
        if (StringUtils.hasText(request.getPassword())) row.secret = encrypt(request.getPassword());
        jdbc.update("UPDATE rdp_connection SET host_id=?,name=?,username=?,domain=?,port=?,ignore_certificate=?,enabled=?,secret_enc=?,updated_at=datetime('now','localtime') WHERE id=?",
                row.hostId,row.name,row.username,row.domain,row.port,row.ignoreCertificate ? 1 : 0,row.enabled,row.secret,id);
        return toVo(row);
    }

    public int delete(long id) {
        require(id);
        return jdbc.update("DELETE FROM rdp_connection WHERE id=?", id);
    }

    public RdpTarget resolve(long id) {
        Profile row = require(id);
        if (row.enabled != 1) throw invalid("远程桌面连接已禁用");
        SshHost host = requireHost(row.hostId);
        if (!Integer.valueOf(1).equals(host.getEnabled())) throw invalid("主机已禁用，禁止连接");
        CredentialCipher.SshSecretPayload secret = CredentialCipher.parseSecretJson(cipher.decryptToJson(row.secret));
        if (secret == null || !StringUtils.hasText(secret.getPassword())) throw invalid("远程桌面密码未配置");
        return new RdpTarget(host.getHost(),row.port,row.username,secret.getPassword(),row.domain,row.ignoreCertificate);
    }

    private SshHost requireHost(long id) {
        if (id < 1) throw invalid("hostId 无效");
        return hosts.getById(id);
    }

    private Profile require(long id) {
        if (id < 1) throw invalid("连接不存在");
        List<Profile> rows = jdbc.query(SELECT + " WHERE id=?", (rs, n) -> read(rs), id);
        if (rows.isEmpty()) throw invalid("连接不存在");
        return rows.get(0);
    }

    private void apply(Profile row, SaveRequest request) {
        if (request.getName() != null) row.name = request.getName().trim();
        if (request.getUsername() != null) row.username = request.getUsername().trim();
        if (request.getDomain() != null) row.domain = request.getDomain().trim();
        if (request.getPort() != null) row.port = request.getPort();
        if (request.getEnabled() != null) row.enabled = request.getEnabled();
        if (request.getIgnoreCertificate() != null) row.ignoreCertificate = request.getIgnoreCertificate();
        validateText(row.name,"name",true); validateText(row.username,"username",true); validateText(row.domain,"domain",false);
        if (row.port < 1 || row.port > 65535) throw invalid("port 无效");
        if (row.enabled != 0 && row.enabled != 1) throw invalid("enabled 无效");
        if (request.getPassword() != null && request.getPassword().length() > 4096) throw invalid("密码过长");
    }

    private static void validateText(String value, String field, boolean required) {
        if ((required && !StringUtils.hasText(value)) || (value != null && value.length() > 255)) throw invalid(field + " 无效");
        if (value != null && value.chars().anyMatch(Character::isISOControl)) throw invalid(field + " 无效");
    }

    private byte[] encrypt(String password) {
        CredentialCipher.SshSecretPayload secret = new CredentialCipher.SshSecretPayload(); secret.setPassword(password);
        return cipher.encryptSecretJson(CredentialCipher.buildSecretJson(secret));
    }

    private static Profile read(ResultSet rs) throws SQLException {
        Profile row = new Profile(); row.id=rs.getLong("id"); row.hostId=rs.getLong("host_id");
        row.name=rs.getString("name"); row.username=rs.getString("username"); row.domain=rs.getString("domain");
        row.port=rs.getInt("port"); row.ignoreCertificate=rs.getInt("ignore_certificate") == 1;
        row.enabled=rs.getInt("enabled"); row.secret=rs.getBytes("secret_enc"); return row;
    }

    private static ConnectionVO toVo(Profile row) {
        ConnectionVO vo = new ConnectionVO(); vo.setId(row.id); vo.setHostId(row.hostId); vo.setName(row.name);
        vo.setUsername(row.username); vo.setDomain(row.domain); vo.setPort(row.port); vo.setEnabled(row.enabled);
        vo.setIgnoreCertificate(row.ignoreCertificate); vo.setHasPassword(row.secret != null && row.secret.length > 0); return vo;
    }

    private static BusinessRuntimeException invalid(String message) {
        return new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY,message);
    }

    private static final class Profile {
        long id,hostId;
        String name,username,domain;
        int port,enabled;
        boolean ignoreCertificate;
        byte[] secret;
    }

    @lombok.Data
    public static class SaveRequest {
        private Long hostId;
        private String name;
        private String username;
        private String domain;
        private Integer port;
        private Boolean ignoreCertificate;
        private Integer enabled;
        @lombok.ToString.Exclude
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        private String password;
    }

    @lombok.Data
    public static class BackupConnection {
        private Long id;
        private Long hostId;
        private String name;
        private String username;
        private String domain;
        private Integer port;
        private Boolean ignoreCertificate;
        private Integer enabled;
        @lombok.ToString.Exclude
        private String password;
    }

    @lombok.Data
    public static class ConnectionVO {
        private Long id;
        private Long hostId;
        private String name;
        private String username;
        private String domain;
        private int port;
        private boolean ignoreCertificate;
        private int enabled;
        private boolean hasPassword;
    }
}
