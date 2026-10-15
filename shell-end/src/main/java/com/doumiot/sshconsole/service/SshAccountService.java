package com.doumiot.sshconsole.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.doumiot.sshconsole.entity.SshAccount;
import com.doumiot.sshconsole.entity.SshHost;
import com.doumiot.sshconsole.mapper.SshAccountMapper;
import com.doumiot.sshconsole.model.SshAuthType;
import com.doumiot.sshconsole.ssh.SshClientFactory;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.io.IOException;

@Slf4j
@SshConsoleEnabled
@Service
public class SshAccountService {

    @Resource
    private SshAccountMapper sshAccountMapper;
    @Resource
    private SshHostService sshHostService;
    @Resource
    private CredentialCipher credentialCipher;
    @Resource
    private SshClientFactory sshClientFactory;
    @Resource
    private SshSessionRegistry sessionRegistry;

    public SshAccountVO create(SshAccountSaveRequest req) {
        validateSave(req, true);
        SshHost host = sshHostService.getById(req.getHostId());
        assertUsernameUnique(host.getId(), req.getUsername(), null);
        if (req.getSecret() == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "新建账户须填写 secret");
        }
        credentialCipher.ensureKeyConfigured();
        SshAccount row = new SshAccount();
        applyCommon(row, req);
        applySecret(row, req.getAuthType(), req.getSecret());
        sshAccountMapper.insert(row);
        return toVo(row);
    }

    public SshAccountVO update(long id, SshAccountSaveRequest req) {
        SshAccount existing = requireAccount(id);
        validateSave(req, false);
        assertUsernameUnique(existing.getHostId(), req.getUsername(), id);
        applyCommon(existing, req);
        if (req.getSecret() != null) {
            applySecret(existing, req.getAuthType(), req.getSecret());
        }
        sshAccountMapper.updateById(existing);
        return toVo(existing);
    }

    public SshAccountVO getById(long id) {
        return toVo(requireAccount(id));
    }

    public int delete(long id) {
        requireAccount(id);
        sessionRegistry.closeByAccountId(id);
        return sshAccountMapper.deleteById(id);
    }

    public SshConnectionTestResult testConnection(long id) {
        SshAccount account = requireAccount(id);
        SshHost host = sshHostService.getById(account.getHostId());
        assertHostEnabled(host);
        long start = System.nanoTime();
        SSHClient client = null;
        try {
            CredentialCipher.SshSecretPayload secret = loadSecret(account);
            client = sshClientFactory.connectAndAuth(
                    host.getHost(), portOf(host), account.getUsername(), account.getAuthType(), secret);
            SshConnectionTestResult r = new SshConnectionTestResult();
            r.setSuccess(true);
            r.setElapsedMs(SshClientFactory.elapsedMs(start));
            r.setMessage("连接与认证成功");
            return r;
        } catch (BusinessRuntimeException e) {
            return failTest(start, e.getMessage());
        } catch (IOException e) {
            log.warn("SSH test failed accountId={}: {}", id, e.getMessage());
            return failTest(start, e.getMessage());
        } finally {
            sshClientFactory.disconnectQuietly(client);
        }
    }

    public SshSessionResponse openSession(long id) {
        SshAccount account = requireAccount(id);
        if (account.getEnabled() != null && account.getEnabled() == 0) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "账户已禁用");
        }
        SshHost host = sshHostService.getById(account.getHostId());
        assertHostEnabled(host);
        CredentialCipher.SshSecretPayload secret = loadSecret(account);
        SSHClient client;
        try {
            client = sshClientFactory.connectAndAuth(
                    host.getHost(), portOf(host), account.getUsername(), account.getAuthType(), secret);
        } catch (IOException e) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "SSH 连接失败: " + e.getMessage());
        }
        String label = account.getUsername() + "@" + host.getHost();
        SshSessionRegistry.SessionEntry entry = sessionRegistry.register(
                client, id, label, host.getRemark(), account.getRemark(), account.getDefaultCwd());
        SshSessionResponse resp = new SshSessionResponse();
        resp.setSessionToken(entry.getSessionToken());
        resp.setLabel(label);
        resp.setHostRemark(host.getRemark());
        resp.setAccountRemark(account.getRemark());
        return resp;
    }

    CredentialCipher.SshSecretPayload loadSecret(SshAccount account) {
        String json = credentialCipher.decryptToJson(account.getSecretEnc());
        return CredentialCipher.parseSecretJson(json);
    }

    private void applyCommon(SshAccount row, SshAccountSaveRequest req) {
        if (req.getHostId() != null) {
            if (sshHostService.getById(req.getHostId()) == null) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "主机不存在");
            }
            row.setHostId(req.getHostId());
        }
        row.setUsername(req.getUsername().trim());
        row.setAuthType(req.getAuthType());
        row.setDefaultCwd(trimToNull(req.getDefaultCwd()));
        row.setRemark(trimToNull(req.getRemark()));
        row.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        row.setEnabled(req.getEnabled() != null ? req.getEnabled() : 1);
    }

    private void applySecret(SshAccount row, String authType, CredentialCipher.SshSecretPayload secret) {
        String json = CredentialCipher.buildSecretJson(secret);
        row.setSecretEnc(credentialCipher.encryptSecretJson(json));
        row.setSecretHint(CredentialCipher.hintForSecret(secret, authType));
    }

    private void assertUsernameUnique(long hostId, String username, Long excludeId) {
        LambdaQueryWrapper<SshAccount> q = new LambdaQueryWrapper<>();
        q.eq(SshAccount::getHostId, hostId).eq(SshAccount::getUsername, username.trim());
        if (excludeId != null) {
            q.ne(SshAccount::getId, excludeId);
        }
        Long count = sshAccountMapper.selectCount(q);
        if (count != null && count > 0) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "该主机下用户名已存在");
        }
    }

    SshAccount requireAccount(long id) {
        SshAccount a = sshAccountMapper.selectById(id);
        if (a == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "账户不存在");
        }
        return a;
    }

    private static void validateSave(SshAccountSaveRequest req, boolean create) {
        if (create && req.getHostId() == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "hostId 必填");
        }
        if (!StringUtils.hasText(req.getUsername())) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "username 必填");
        }
        if (!StringUtils.hasText(req.getAuthType())) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "authType 必填");
        }
        if (!SshAuthType.PASSWORD.equals(req.getAuthType())
                && !SshAuthType.PRIVATE_KEY.equals(req.getAuthType())
                && !SshAuthType.PRIVATE_KEY_WITH_PASSPHRASE.equals(req.getAuthType())) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "authType 无效");
        }
    }

    private static void assertHostEnabled(SshHost host) {
        if (host.getEnabled() != null && host.getEnabled() == 0) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "主机已禁用，禁止连接");
        }
    }

    private static int portOf(SshHost host) {
        return host.getPort() != null ? host.getPort() : 22;
    }

    private static SshConnectionTestResult failTest(long startNanos, String message) {
        SshConnectionTestResult r = new SshConnectionTestResult();
        r.setSuccess(false);
        r.setElapsedMs(SshClientFactory.elapsedMs(startNanos));
        r.setMessage(message);
        return r;
    }

    static SshAccountVO toVo(SshAccount row) {
        SshAccountVO vo = new SshAccountVO();
        vo.setId(row.getId());
        vo.setHostId(row.getHostId());
        vo.setUsername(row.getUsername());
        vo.setAuthType(row.getAuthType());
        vo.setSecretHint(row.getSecretHint());
        vo.setHasSecret(row.getSecretEnc() != null && row.getSecretEnc().length > 0);
        vo.setDefaultCwd(row.getDefaultCwd());
        vo.setRemark(row.getRemark());
        vo.setSortOrder(row.getSortOrder());
        vo.setEnabled(row.getEnabled());
        return vo;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @lombok.Data
    public static class SshAccountSaveRequest {
        private Long hostId;
        private String username;
        private String authType;
        private CredentialCipher.SshSecretPayload secret;
        private String defaultCwd;
        private String remark;
        private Integer sortOrder;
        private Integer enabled;
    }

    @lombok.Data
    public static class SshAccountVO {
        private Long id;
        private Long hostId;
        private String username;
        private String authType;
        private String secretHint;
        private Boolean hasSecret;
        private String defaultCwd;
        private String remark;
        private Integer sortOrder;
        private Integer enabled;
    }

    @lombok.Data
    public static class SshConnectionTestResult {
        private boolean success;
        private long elapsedMs;
        private String message;
    }

    @lombok.Data
    public static class SshSessionResponse {
        private String sessionToken;
        private String label;
        private String hostRemark;
        private String accountRemark;
    }
}
