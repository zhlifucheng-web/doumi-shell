package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.entity.SshAccount;
import com.doumiot.sshconsole.entity.SshHost;
import lombok.Data;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class SshShareSessionService {

    private static final String ALPHABET = "23456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource
    private SshAccountService sshAccountService;
    @Resource
    private SshHostService sshHostService;

    @Data
    public static class ShareRecord {
        private String token;
        private Long accountId;
        private String terminalId;
        private String permission; // "READ_WRITE" or "READ_ONLY"
        private String encoding; // "UTF-8", "GBK", etc.
        private Instant createdAt;
        private Instant expireAt; // null = 永不过期
        private String label;
        private String hostEndpoint;
        private String username;
        private boolean revoked;
        private final AtomicInteger activeConnections = new AtomicInteger(0);

        public boolean isExpired() {
            return revoked || (expireAt != null && Instant.now().isAfter(expireAt));
        }

        public boolean isReadOnly() {
            return "READ_ONLY".equalsIgnoreCase(permission);
        }
    }

    @Data
    public static class ShareCreateRequest {
        private Long accountId;
        private String terminalId;
        private String permission; // "READ_WRITE" or "READ_ONLY"
        private Integer expireMinutes; // 0 = 永不过期, 30, 60, 120, 1440 等
        private String encoding; // 终端字符编码，如 "UTF-8", "GBK"
    }

    @Data
    public static class ShareInfoVO {
        private String token;
        private Long accountId;
        private String terminalId;
        private String permission;
        private String encoding;
        private boolean readOnly;
        private boolean expired;
        private Long createdAt;
        private Long expireAt;
        private String label;
        private String hostEndpoint;
        private String username;
        private int activeGuests;
    }

    private final Map<String, ShareRecord> shareStore = new ConcurrentHashMap<>();

    public ShareInfoVO createShare(ShareCreateRequest req) {
        if (req == null || req.getAccountId() == null) {
            throw new IllegalArgumentException("缺少 accountId");
        }
        SshAccount account = sshAccountService.requireAccount(req.getAccountId());
        SshHost host = sshHostService.getById(account.getHostId());

        String token = generateToken();
        String perm = "READ_ONLY".equalsIgnoreCase(req.getPermission()) ? "READ_ONLY" : "READ_WRITE";
        String termId = StringUtils.hasText(req.getTerminalId()) ? req.getTerminalId().trim()
                : ("s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));

        Instant now = Instant.now();
        Instant expireAt = null;
        if (req.getExpireMinutes() != null && req.getExpireMinutes() > 0) {
            expireAt = now.plusSeconds(req.getExpireMinutes() * 60L);
        }

        String hostLabel = (host != null && StringUtils.hasText(host.getName()))
                ? host.getName() + " (" + (host.getHost() != null ? host.getHost() : "") + ")"
                : (host != null ? host.getHost() : "主机#" + account.getHostId());
        String label = hostLabel + " · " + account.getUsername();

        ShareRecord record = new ShareRecord();
        record.setToken(token);
        record.setAccountId(account.getId());
        record.setTerminalId(termId);
        record.setPermission(perm);
        record.setEncoding(StringUtils.hasText(req.getEncoding()) ? req.getEncoding().trim() : "UTF-8");
        record.setCreatedAt(now);
        record.setExpireAt(expireAt);
        record.setLabel(label);
        record.setHostEndpoint(host != null ? host.getHost() + ":" + host.getPort() : "");
        record.setUsername(account.getUsername());
        record.setRevoked(false);

        shareStore.put(token, record);
        return toVO(record);
    }

    public ShareRecord getShare(String token) {
        if (!StringUtils.hasText(token)) return null;
        return shareStore.get(token);
    }

    public ShareRecord getValidShare(String token) {
        ShareRecord record = getShare(token);
        if (record == null || record.isExpired()) {
            return null;
        }
        return record;
    }

    public ShareInfoVO getShareInfo(String token) {
        ShareRecord record = getShare(token);
        if (record == null) return null;
        return toVO(record);
    }

    public List<ShareInfoVO> listActiveShares(Long accountId, String terminalId) {
        if (accountId == null) return Collections.emptyList();
        String termId = StringUtils.hasText(terminalId) ? terminalId.trim() : null;
        return shareStore.values().stream()
                .filter(r -> r.getAccountId().equals(accountId))
                .filter(r -> termId == null || r.getTerminalId().equals(termId))
                .filter(r -> !r.isExpired())
                .sorted(Comparator.comparing(ShareRecord::getCreatedAt).reversed())
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    public boolean revokeShare(String token) {
        ShareRecord record = getShare(token);
        if (record != null) {
            record.setRevoked(true);
            return true;
        }
        return false;
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpiredShares() {
        shareStore.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private ShareInfoVO toVO(ShareRecord record) {
        ShareInfoVO vo = new ShareInfoVO();
        vo.setToken(record.getToken());
        vo.setAccountId(record.getAccountId());
        vo.setTerminalId(record.getTerminalId());
        vo.setPermission(record.getPermission());
        vo.setEncoding(StringUtils.hasText(record.getEncoding()) ? record.getEncoding() : "UTF-8");
        vo.setReadOnly(record.isReadOnly());
        vo.setExpired(record.isExpired());
        vo.setCreatedAt(record.getCreatedAt() != null ? record.getCreatedAt().toEpochMilli() : null);
        vo.setExpireAt(record.getExpireAt() != null ? record.getExpireAt().toEpochMilli() : null);
        vo.setLabel(record.getLabel());
        vo.setHostEndpoint(record.getHostEndpoint());
        vo.setUsername(record.getUsername());
        vo.setActiveGuests(record.getActiveConnections().get());
        return vo;
    }

    private static String generateToken() {
        StringBuilder sb = new StringBuilder("sh_");
        for (int i = 0; i < 22; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
