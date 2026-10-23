package com.doumiot.sshconsole.ws;

import com.google.gson.Gson;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshTerminalService;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Slf4j
@SshConsoleEnabled
@Component
public class SshTerminalWebSocketHandler extends TextWebSocketHandler {

    private static final Gson GSON = new Gson();
    private static final int READ_BUF = 8192;

    private final ExecutorService pumpExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ssh-terminal-pump");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Future<?>> pumpTasks = new ConcurrentHashMap<>();
    private final Map<String, Object> sendLocks = new ConcurrentHashMap<>();
    private final Set<String> historyRequests = ConcurrentHashMap.newKeySet();

    @Resource
    private SshTerminalService sshTerminalService;
    @Resource
    private SshSessionRegistry sessionRegistry;
    @Resource
    private com.doumiot.sshconsole.service.SshShareSessionService shareSessionService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String shareToken = parseStringQuery(session.getUri(), "shareToken");
        Long accountId = parseAccountId(session.getUri());
        String terminalId = parseStringQuery(session.getUri(), "terminalId");
        boolean readOnly = false;
        com.doumiot.sshconsole.service.SshShareSessionService.ShareRecord shareRecord = null;

        if (StringUtils.hasText(shareToken)) {
            shareRecord = shareSessionService.getValidShare(shareToken);
            if (shareRecord == null) {
                sendErrorAndClose(session, "SHARE_INVALID", "共享会话链接已失效或已过期");
                return;
            }
            accountId = shareRecord.getAccountId();
            terminalId = shareRecord.getTerminalId();
            readOnly = shareRecord.isReadOnly();
            shareRecord.getActiveConnections().incrementAndGet();
            session.getAttributes().put("shareToken", shareToken);
            session.getAttributes().put("readOnly", readOnly);
            session.getAttributes().put("shareEncoding", shareRecord.getEncoding());
        } else if (accountId == null) {
            sendErrorAndClose(session, "BAD_REQUEST", "缺少或无效的 accountId");
            return;
        }

        int cols = parseIntQuery(session.getUri(), "cols", 0);
        int rows = parseIntQuery(session.getUri(), "rows", 0);
        String encoding = parseStringQuery(session.getUri(), "encoding");
        if (!StringUtils.hasText(encoding) && shareRecord != null) encoding = shareRecord.getEncoding();

        SshTerminalService.TerminalSession terminalSession;
        try {
            terminalSession = sshTerminalService.openTerminal(accountId, cols, rows, terminalId, readOnly, shareRecord != null,
                    "ssh".equals(parseStringQuery(session.getUri(), "transport")), encoding,
                    stage -> {
                        SshTerminalFrame progress = new SshTerminalFrame();
                        progress.setType("connecting");
                        progress.setMessage(stage);
                        sendFrame(session, null, progress);
                    });
        } catch (BusinessRuntimeException e) {
            sendErrorAndClose(session, "CONNECT_FAILED", e.getMessage());
            return;
        } catch (IOException | RuntimeException e) {
            log.warn("SSH terminal open failed accountId={}: {}", accountId, e.getMessage());
            sendErrorAndClose(session, "CONNECT_FAILED", e.getMessage());
            return;
        }

        SshSessionRegistry.SessionEntry entry = terminalSession.getEntry();
        if (!session.isOpen()) {
            sessionRegistry.close(entry.getSessionToken());
            releaseShareConnection(session);
            return;
        }
        String token = entry.getSessionToken();
        session.getAttributes().put("sessionToken", token);
        sessionRegistry.bindWebSocket(token, session);
        sendLocks.put(token, new Object());

        SshTerminalFrame ready = SshTerminalFrame.ready(
                token, terminalSession.getLabel(), terminalSession.getReadyRemark(), terminalSession.getConnectionMode());
        ready.setTmuxVersion(terminalSession.getTmuxVersion());
        ready.setTmuxVersionSource(terminalSession.getTmuxVersionSource());
        sendFrame(session, token, ready);

        Future<?> pump = pumpExecutor.submit(() -> pumpOutput(session, token, entry.getShellStdout()));
        pumpTasks.put(token, pump);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String token = (String) session.getAttributes().get("sessionToken");
        if (!StringUtils.hasText(token)) {
            return;
        }
        SshTerminalFrame frame = GSON.fromJson(message.getPayload(), SshTerminalFrame.class);
        if (frame == null || !StringUtils.hasText(frame.getType())) {
            return;
        }
        switch (frame.getType()) {
            case "history":
                if (frame.getRequestId() == null) break;
                if (!historyRequests.add(token)) {
                    sendHistoryError(session, token, frame.getRequestId(), "正在读取历史，请稍后重试");
                    break;
                }
                pumpExecutor.submit(() -> {
                    try {
                        com.doumiot.sshconsole.ssh.SshRemoteExec.ExecResult history =
                                sshTerminalService.readHistory(token, frame.getEncoding());
                        SshTerminalFrame snapshot = new SshTerminalFrame();
                        snapshot.setType("history"); snapshot.setRequestId(frame.getRequestId());
                        snapshot.setData(history.getStdout()); snapshot.setTruncated(history.isTruncated());
                        sendFrame(session, token, snapshot);
                    } catch (IOException | RuntimeException e) {
                        sendHistoryError(session, token, frame.getRequestId(), e.getMessage());
                    } finally { historyRequests.remove(token); }
                });
                break;
            case "input":
                if (Boolean.TRUE.equals(session.getAttributes().get("readOnly"))) {
                    // 只读共享会话：丢弃输入
                    break;
                }
                if (StringUtils.hasText(frame.getData())) {
                    byte[] bytes = Base64.getDecoder().decode(frame.getData());
                    String enc = frame.getEncoding();
                    if (!StringUtils.hasText(enc) && session.getAttributes().get("shareEncoding") != null) {
                        enc = (String) session.getAttributes().get("shareEncoding");
                    }
                    if (StringUtils.hasText(enc) && !"utf-8".equalsIgnoreCase(enc)) {
                        try {
                            String text = new String(bytes, StandardCharsets.UTF_8);
                            bytes = text.getBytes(Charset.forName(enc));
                        } catch (Exception ignored) {
                            // 保留原始字节
                        }
                    }
                    sshTerminalService.writeInput(token, bytes);
                }
                break;
            case "resize":
                if (frame.getCols() != null && frame.getRows() != null) {
                    sshTerminalService.resize(token, frame.getCols(), frame.getRows());
                }
                break;
            case "ping":
                sessionRegistry.touchSession(token);
                sendFrame(session, token, SshTerminalFrame.pong());
                break;
            default:
                break;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        releaseShareConnection(session);
        String token = (String) session.getAttributes().get("sessionToken");
        if (StringUtils.hasText(token)) {
            cleanupSession(token);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket transport error: {}", exception.getMessage());
        releaseShareConnection(session);
        String token = (String) session.getAttributes().get("sessionToken");
        if (StringUtils.hasText(token)) {
            cleanupSession(token);
        }
    }

    private void releaseShareConnection(WebSocketSession session) {
        String token = (String) session.getAttributes().remove("shareToken");
        if (StringUtils.hasText(token)) {
            com.doumiot.sshconsole.service.SshShareSessionService.ShareRecord record = shareSessionService.getShare(token);
            if (record != null) record.getActiveConnections().decrementAndGet();
        }
    }

    private void pumpOutput(WebSocketSession session, String token, InputStream stdout) {
        byte[] buf = new byte[READ_BUF];
        try {
            while (session.isOpen()) {
                int n = stdout.read(buf);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    continue;
                }
                sessionRegistry.touchSession(token);
                String b64 = Base64.getEncoder().encodeToString(copyOf(buf, n));
                sendFrame(session, token, SshTerminalFrame.output(b64));
            }
        } catch (IOException e) {
            if (session.isOpen()) {
                log.debug("SSH output pump ended token={}: {}", token, e.getMessage());
            }
        } finally {
            if (session.isOpen()) {
                sendErrorAndClose(session, token, "SESSION_END", "SSH 会话已结束");
            }
        }
    }

    private void cleanupSession(String token) {
        Future<?> f = pumpTasks.remove(token);
        if (f != null) {
            f.cancel(true);
        }
        sendLocks.remove(token);
        sessionRegistry.unbindWebSocket(token);
        sessionRegistry.close(token);
    }

    private void sendHistoryError(WebSocketSession session, String token, Long requestId, String message) {
        SshTerminalFrame error = new SshTerminalFrame();
        error.setType("history"); error.setRequestId(requestId);
        error.setMessage(message == null ? "历史读取失败，请重试" : message);
        sendFrame(session, token, error);
    }

    private void sendErrorAndClose(WebSocketSession session, String code, String message) {
        sendFrame(session, null, SshTerminalFrame.error(code, message));
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
            // ignore
        }
    }

    private void sendErrorAndClose(WebSocketSession session, String token, String code, String message) {
        sendFrame(session, token, SshTerminalFrame.error(code, message));
        try {
            session.close(CloseStatus.NORMAL);
        } catch (IOException ignored) {
            // ignore
        }
        cleanupSession(token);
    }

    private void sendFrame(WebSocketSession session, String token, SshTerminalFrame frame) {
        if (!session.isOpen()) {
            return;
        }
        Object lock = token != null ? sendLocks.computeIfAbsent(token, k -> new Object()) : session;
        synchronized (lock) {
            try {
                session.sendMessage(new TextMessage(GSON.toJson(frame)));
            } catch (IOException e) {
                log.debug("WebSocket send failed: {}", e.getMessage());
            }
        }
    }

    private static byte[] copyOf(byte[] buf, int len) {
        byte[] out = new byte[len];
        System.arraycopy(buf, 0, out, 0, len);
        return out;
    }

    private static Long parseAccountId(URI uri) {
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        for (String part : uri.getQuery().split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq);
            if ("accountId".equals(key)) {
                try {
                    return Long.parseLong(part.substring(eq + 1));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static int parseIntQuery(URI uri, String name, int defaultValue) {
        if (uri == null || uri.getQuery() == null) {
            return defaultValue;
        }
        for (String part : uri.getQuery().split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (name.equals(part.substring(0, eq))) {
                try {
                    return Integer.parseInt(part.substring(eq + 1));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private static String parseStringQuery(URI uri, String name) {
        if (uri == null || uri.getQuery() == null) return null;
        for (String part : uri.getQuery().split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && name.equals(part.substring(0, eq))) {
                String value = part.substring(eq + 1);
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    @PreDestroy
    public void shutdown() {
        pumpExecutor.shutdownNow();
    }
}
