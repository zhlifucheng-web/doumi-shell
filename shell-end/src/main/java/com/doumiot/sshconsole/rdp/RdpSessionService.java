package com.doumiot.sshconsole.rdp;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import org.apache.guacamole.net.GuacamoleSocket;
import org.apache.guacamole.protocol.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@SshConsoleEnabled
public class RdpSessionService {
    private static final long TICKET_TTL_MS = 30000;
    private final RdpProperties properties;
    private final RdpConnectionService profiles;
    private final Clock clock;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    @Autowired
    public RdpSessionService(RdpProperties properties, RdpConnectionService profiles) {
        this(properties, profiles, Clock.systemUTC());
    }
    RdpSessionService(RdpProperties properties, RdpConnectionService profiles, Clock clock) {
        this.properties = properties; this.profiles = profiles; this.clock = clock;
    }
    public synchronized String issue(long connectionId, int width, int height, int dpi) {
        checkEnabled();
        if (connectionId <= 0 || width < 200 || width > 8192 || height < 200 || height > 8192 || dpi < 48 || dpi > 384)
            throw invalid("远程桌面分辨率或连接参数无效");
        expire();
        if (tickets.size() >= Math.max(1, Math.min(100, properties.getMaxSessions())))
            throw invalid("远程桌面连接数已达上限，请先关闭其他桌面");
        profiles.resolve(connectionId);
        String token = UUID.randomUUID().toString();
        tickets.put(token, new Ticket(token, connectionId, width, height, dpi, clock.millis()));
        return token;
    }
    synchronized Ticket claim(String token, WebSocketSession browser) {
        checkEnabled();
        Ticket ticket = token == null ? null : tickets.get(token);
        if (ticket == null || ticket.claimed || ticket.closed.get()) throw invalid("连接票据无效或已使用，请重新连接");
        if (clock.millis() - ticket.createdAt >= TICKET_TTL_MS) {
            cancel(token); throw invalid("连接票据已过期，请重新连接");
        }
        ticket.claimed = true; ticket.browser = browser; ticket.lastContact = clock.millis();
        return ticket;
    }
    GuacamoleSocket open(Ticket ticket) throws Exception {
        checkEnabled();
        RdpTarget target = profiles.resolve(ticket.connectionId);
        Socket socket = new Socket();
        synchronized (ticket) {
            if (ticket.closed.get()) { socket.close(); throw new IOException("连接已取消"); }
            ticket.transport = socket;
        }
        try {
            socket.connect(new InetSocketAddress(properties.getGuacdHost(), properties.getGuacdPort()), 5000);
            socket.setSoTimeout(15000); socket.setTcpNoDelay(true);
            GuacamoleConfiguration config = new GuacamoleConfiguration();
            config.setProtocol("rdp");
            config.setParameter("hostname", target.getHost());
            config.setParameter("port", Integer.toString(target.getPort()));
            config.setParameter("username", target.getUsername());
            config.setParameter("password", target.getPassword());
            config.setParameter("domain", target.getDomain() == null ? "" : target.getDomain());
            config.setParameter("security", "nla");
            config.setParameter("ignore-cert", Boolean.toString(target.isIgnoreCertificate()));
            config.setParameter("resize-method", "display-update");
            config.setParameter("enable-drive", "false");
            config.setParameter("disable-audio", "true");
            config.setParameter("enable-audio-input", "false");
            config.setParameter("disable-copy", "true");
            config.setParameter("disable-paste", "true");
            GuacamoleClientInformation info = new GuacamoleClientInformation();
            info.setOptimalScreenWidth(ticket.width); info.setOptimalScreenHeight(ticket.height); info.setOptimalResolution(ticket.dpi);
            info.getImageMimetypes().addAll(Arrays.asList("image/png", "image/jpeg", "image/webp"));
            GuacamoleSocket configured = new ConfiguredGuacamoleSocket(new RdpGuacamoleSocket(socket), config, info);
            socket.setSoTimeout(0);
            if (ticket.closed.get()) { configured.close(); throw new IOException("连接已取消"); }
            return configured;
        } catch (Exception error) { socket.close(); throw error; }
    }
    public void cancel(String token) {
        if (token == null) return;
        Ticket ticket = tickets.remove(token);
        if (ticket == null || !ticket.closed.compareAndSet(false, true)) return;
        synchronized (ticket) {
            if (ticket.transport != null) try { ticket.transport.close(); } catch (IOException ignored) {}
        }
        if (ticket.browser != null && ticket.browser.isOpen()) {
            try { ticket.browser.close(CloseStatus.NORMAL); } catch (IOException ignored) {}
        }
    }
    void touch(Ticket ticket) { ticket.lastContact = clock.millis(); }
    @Scheduled(fixedDelay = 10000)
    public void expire() {
        long now = clock.millis();
        for (Ticket ticket : tickets.values()) {
            if ((!ticket.claimed && now - ticket.createdAt >= TICKET_TTL_MS)
                    || (ticket.claimed && now - ticket.lastContact >= Math.max(1, properties.getIdleTimeoutMinutes()) * 60000L))
                cancel(ticket.token);
        }
    }
    public Map<String, Object> gateway() {
        Map<String,Object> result = new LinkedHashMap<>();
        boolean enabled = properties.isEnabled() && !stopped;
        result.put("enabled", enabled);
        boolean available = false;
        if (enabled) try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.getGuacdHost(), properties.getGuacdPort()), 1000);
            available = true;
        } catch (IOException | IllegalArgumentException ignored) {}
        result.put("available", available);
        result.put("message", !enabled ? "远程桌面已禁用" : available ? "网关可用" : "无法连接 guacd，请检查部署机器的网关地址和端口");
        return result;
    }
    private void checkEnabled() {
        if (!properties.isEnabled() || stopped) throw invalid("远程桌面已禁用");
    }
    private static BusinessRuntimeException invalid(String message) {
        return new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, message);
    }
    @PreDestroy public synchronized void shutdown() {
        stopped = true;
        for (String token : new ArrayList<>(tickets.keySet())) cancel(token);
    }
    static final class Ticket {
        final String token;
        final long connectionId, createdAt;
        final int width, height, dpi;
        final AtomicBoolean closed = new AtomicBoolean();
        volatile long lastContact;
        volatile boolean claimed;
        volatile WebSocketSession browser;
        volatile Socket transport;
        Ticket(String token, long connectionId, int width, int height, int dpi, long now) {
            this.token=token;this.connectionId=connectionId;this.width=width;this.height=height;this.dpi=dpi;createdAt=now;lastContact=now;
        }
    }
}
