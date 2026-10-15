package com.doumiot.sshconsole.ssh;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.config.SshConsoleProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import com.doumiot.sshconsole.service.SshHostMonitorService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@SshConsoleEnabled
@Component
public class SshSessionRegistry {

    private final SshConsoleProperties properties;
    private final SshClientFactory clientFactory;
    private final SshHostMonitorService sshHostMonitorService;
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();

    public SshSessionRegistry(
            SshConsoleProperties properties,
            SshClientFactory clientFactory,
            @Lazy SshHostMonitorService sshHostMonitorService) {
        this.properties = properties;
        this.clientFactory = clientFactory;
        this.sshHostMonitorService = sshHostMonitorService;
    }

    public int activeCount() {
        return sessions.size();
    }

    public SessionEntry register(
            SSHClient client, long accountId, String label, String hostRemark, String accountRemark, String defaultCwd) {
        if (sessions.size() >= properties.getMaxSessions()) {
            clientFactory.disconnectQuietly(client);
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY,
                    "SSH 会话数已达上限: " + properties.getMaxSessions());
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        SessionEntry entry = new SessionEntry();
        entry.setSessionToken(token);
        entry.setAccountId(accountId);
        entry.setLabel(label);
        entry.setHostRemark(hostRemark);
        entry.setAccountRemark(accountRemark);
        entry.setDefaultCwd(defaultCwd);
        entry.setClient(client);
        entry.setCreatedAt(Instant.now());
        entry.touch();
        sessions.put(token, entry);
        return entry;
    }

    public SessionEntry get(String sessionToken) {
        SessionEntry e = sessions.get(sessionToken);
        if (e == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "会话不存在或已过期");
        }
        e.touch();
        return e;
    }

    public void touchSession(String sessionToken) {
        SessionEntry e = sessions.get(sessionToken);
        if (e != null) {
            e.touch();
        }
    }

    public void closeByAccountId(long accountId) {
        sessions.entrySet().removeIf(e -> {
            if (e.getValue().getAccountId() == accountId) {
                sshHostMonitorService.clearState(e.getKey());
                clientFactory.disconnectQuietly(e.getValue().getClient());
                return true;
            }
            return false;
        });
    }

    public void close(String sessionToken) {
        SessionEntry removed = sessions.remove(sessionToken);
        if (removed != null) {
            removed.closeTerminalResources();
            clientFactory.disconnectQuietly(removed.getClient());
            sshHostMonitorService.clearState(sessionToken);
        }
    }

    public List<String> findIdleTokens(Instant idleBefore) {
        List<String> tokens = new ArrayList<>();
        for (Map.Entry<String, SessionEntry> e : sessions.entrySet()) {
            Instant last = e.getValue().getLastAccessAt();
            if (last != null && last.isBefore(idleBefore)) {
                tokens.add(e.getKey());
            }
        }
        return tokens;
    }

    public void bindWebSocket(String sessionToken, WebSocketSession webSocketSession) {
        SessionEntry entry = sessions.get(sessionToken);
        if (entry != null) {
            entry.setWebSocketSession(webSocketSession);
        }
    }

    public void unbindWebSocket(String sessionToken) {
        SessionEntry entry = sessions.get(sessionToken);
        if (entry != null) {
            entry.setWebSocketSession(null);
        }
    }

    @Data
    public static class SessionEntry {
        private String sessionToken;
        private long accountId;
        private String label;
        private String hostRemark;
        private String accountRemark;
        /** 账户 default_cwd，SFTP 路径沙箱根（可为空） */
        private String defaultCwd;
        private SSHClient client;
        private Session sshSession;
        private Session.Shell shell;
        private OutputStream shellStdin;
        private InputStream shellStdout;
        private int ptyCols;
        private int ptyRows;
        private boolean terminalAttached;
        private volatile WebSocketSession webSocketSession;
        private Instant createdAt;
        private Instant lastAccessAt;
        /** 短命令 exec 与 Shell 通道互斥 */
        private final Object execLock = new Object();

        void touch() {
            lastAccessAt = Instant.now();
        }

        void closeTerminalResources() {
            closeWebSocketQuietly();
            if (shell != null) {
                try {
                    shell.close();
                } catch (IOException e) {
                    log.debug("shell close: {}", e.getMessage());
                }
            }
            if (sshSession != null) {
                try {
                    sshSession.close();
                } catch (IOException e) {
                    log.debug("ssh session close: {}", e.getMessage());
                }
            }
            shell = null;
            sshSession = null;
            shellStdin = null;
            shellStdout = null;
            terminalAttached = false;
        }

        private void closeWebSocketQuietly() {
            WebSocketSession ws = webSocketSession;
            webSocketSession = null;
            if (ws != null && ws.isOpen()) {
                try {
                    ws.close();
                } catch (IOException e) {
                    log.debug("websocket close: {}", e.getMessage());
                }
            }
        }
    }
}
