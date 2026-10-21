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
import java.util.Base64;
import java.util.Map;
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

    @Resource
    private SshTerminalService sshTerminalService;
    @Resource
    private SshSessionRegistry sessionRegistry;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long accountId = parseAccountId(session.getUri());
        if (accountId == null) {
            sendErrorAndClose(session, "BAD_REQUEST", "缺少或无效的 accountId");
            return;
        }
        int cols = parseIntQuery(session.getUri(), "cols", 0);
        int rows = parseIntQuery(session.getUri(), "rows", 0);
        String terminalId = parseStringQuery(session.getUri(), "terminalId");

        SshTerminalService.TerminalSession terminalSession;
        try {
            terminalSession = sshTerminalService.openTerminal(accountId, cols, rows, terminalId);
        } catch (BusinessRuntimeException e) {
            sendErrorAndClose(session, "CONNECT_FAILED", e.getMessage());
            return;
        } catch (IOException e) {
            log.warn("SSH terminal open failed accountId={}: {}", accountId, e.getMessage());
            sendErrorAndClose(session, "CONNECT_FAILED", e.getMessage());
            return;
        }

        SshSessionRegistry.SessionEntry entry = terminalSession.getEntry();
        String token = entry.getSessionToken();
        session.getAttributes().put("sessionToken", token);
        sessionRegistry.bindWebSocket(token, session);
        sendLocks.put(token, new Object());

        SshTerminalFrame ready = SshTerminalFrame.ready(
                token, terminalSession.getLabel(), terminalSession.getReadyRemark(), terminalSession.getConnectionMode());
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
            case "input":
                if (StringUtils.hasText(frame.getData())) {
                    byte[] bytes = Base64.getDecoder().decode(frame.getData());
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
        String token = (String) session.getAttributes().get("sessionToken");
        if (StringUtils.hasText(token)) {
            cleanupSession(token);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket transport error: {}", exception.getMessage());
        String token = (String) session.getAttributes().get("sessionToken");
        if (StringUtils.hasText(token)) {
            cleanupSession(token);
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
