package com.doumiot.sshconsole.rdp;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import org.apache.guacamole.*;
import org.apache.guacamole.io.GuacamoleWriter;
import org.apache.guacamole.net.GuacamoleSocket;
import org.apache.guacamole.protocol.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Bridges Guacamole instructions, never accepting host or credential overrides from the browser. */
@Component
@SshConsoleEnabled
public class RdpWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {
    private static final Set<String> INPUT = new HashSet<>(Arrays.asList("key","mouse","size","sync","ack","disconnect","nop"));
    private final RdpSessionService sessions;
    private final Map<String, Bridge> bridges = new ConcurrentHashMap<>();
    private final ExecutorService readers, writers;
    public RdpWebSocketHandler(RdpSessionService sessions, RdpProperties properties) {
        this.sessions=sessions;
        int max=Math.max(1,Math.min(100,properties.getMaxSessions()));
        readers=pool("rdp-read",max); writers=pool("rdp-write",max);
    }
    private static ExecutorService pool(String name,int max) {
        return new ThreadPoolExecutor(0,max,60,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{Thread thread=new Thread(task,name);thread.setDaemon(true);return thread;});
    }
    public List<String> getSubProtocols() { return Collections.singletonList("guacamole"); }
    @Override public void afterConnectionEstablished(WebSocketSession browser) {
        browser.setTextMessageSizeLimit(65536);
        if (browser instanceof org.springframework.web.socket.adapter.NativeWebSocketSession) {
            javax.websocket.Session nativeSession=((org.springframework.web.socket.adapter.NativeWebSocketSession)browser).getNativeSession(javax.websocket.Session.class);
            if(nativeSession!=null) nativeSession.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT",5000L);
        }
        try {
            List<String> tokens=UriComponentsBuilder.fromUri(browser.getUri()).build().getQueryParams().get("token");
            if(tokens==null || tokens.size()!=1) throw new IllegalArgumentException("invalid ticket");
            RdpSessionService.Ticket ticket=sessions.claim(tokens.get(0),browser);
            Bridge bridge=new Bridge(ticket,browser);
            bridges.put(browser.getId(),bridge);
            try { readers.execute(()->read(bridge)); }
            catch(RejectedExecutionException full) { fail(bridge,"远程桌面连接繁忙，请重试",GuacamoleStatus.SERVER_BUSY); }
        } catch(Exception error) {
            try { browser.close(CloseStatus.POLICY_VIOLATION); } catch(IOException ignored) {}
        }
    }
    private void read(Bridge bridge) {
        try {
            GuacamoleSocket socket=sessions.open(bridge.ticket);
            bridge.socket=socket;
            if(bridge.ticket.closed.get())return;
            send(bridge,new GuacamoleInstruction("",UUID.randomUUID().toString()).toString());
            writers.execute(()->write(bridge));
            char[] instruction;
            while(!bridge.ticket.closed.get() && (instruction=socket.getReader().read())!=null) send(bridge,new String(instruction));
        } catch(Exception error) {
            if(!bridge.ticket.closed.get()) fail(bridge,"RDP 连接失败，请检查 guacd、Windows 远程桌面、账户和证书设置",GuacamoleStatus.UPSTREAM_ERROR);
        } finally { close(bridge); }
    }
    private void write(Bridge bridge) {
        try {
            GuacamoleWriter writer=new FilteredGuacamoleWriter(bridge.socket.getWriter(), instruction->{
                if(instruction.getOpcode().isEmpty()) {
                    if(instruction.getArgs().size()==2 && "ping".equals(instruction.getArgs().get(0))) {
                        try { send(bridge,instruction.toString()); } catch(IOException error) { throw new GuacamoleServerException("浏览器连接已断开",error); }
                    }
                    return null;
                }
                if(!INPUT.contains(instruction.getOpcode())) throw new GuacamoleClientException("不支持的桌面输入");
                return instruction;
            });
            while(!bridge.ticket.closed.get()) {
                String input=bridge.input.poll(1,TimeUnit.SECONDS);
                if(input==null) continue;
                bridge.writingAt=System.currentTimeMillis();
                writer.write(input.toCharArray());
                bridge.writingAt=0;
            }
        } catch(Exception error) {
            if(!bridge.ticket.closed.get()) fail(bridge,"桌面输入连接已断开，请重新连接",GuacamoleStatus.UPSTREAM_ERROR);
        } finally { close(bridge); }
    }
    @Override protected void handleTextMessage(WebSocketSession browser,TextMessage message) {
        Bridge bridge=bridges.get(browser.getId());
        if(bridge==null || bridge.ticket.closed.get())return;
        if(!bridge.input.offer(message.getPayload())) {fail(bridge,"桌面输入过快，请重新连接",GuacamoleStatus.CLIENT_OVERRUN);return;}
        sessions.touch(bridge.ticket);
    }
    private void send(Bridge bridge,String instruction) throws IOException {
        synchronized(bridge.browser) {
            if(!bridge.ticket.closed.get() && bridge.browser.isOpen()) bridge.browser.sendMessage(new TextMessage(instruction));
        }
    }
    private void fail(Bridge bridge,String message,GuacamoleStatus status) {
        try {send(bridge,new GuacamoleInstruction("error",message,Integer.toString(status.getGuacamoleStatusCode())).toString());}catch(IOException ignored){}
        close(bridge);
    }
    private void close(Bridge bridge) {
        bridges.remove(bridge.browser.getId(),bridge);
        sessions.cancel(bridge.ticket.token);
        bridge.input.clear();
    }
    @Override public void afterConnectionClosed(WebSocketSession browser,CloseStatus status) {
        Bridge bridge=bridges.get(browser.getId());if(bridge!=null)close(bridge);
    }
    @Override public void handleTransportError(WebSocketSession browser,Throwable error) {
        Bridge bridge=bridges.get(browser.getId());if(bridge!=null)close(bridge);
    }
    @Scheduled(fixedDelay=1000) public void closeStalledWriters() {
        long now=System.currentTimeMillis();
        for(Bridge bridge:bridges.values())if(bridge.writingAt!=0 && now-bridge.writingAt>10000)close(bridge);
    }
    @PreDestroy public void shutdown() {
        for(Bridge bridge:new ArrayList<>(bridges.values()))close(bridge);
        readers.shutdownNow();writers.shutdownNow();
    }
    private static final class Bridge {
        final RdpSessionService.Ticket ticket;
        final WebSocketSession browser;
        final BlockingQueue<String> input=new ArrayBlockingQueue<>(32);
        volatile GuacamoleSocket socket;
        volatile long writingAt;
        Bridge(RdpSessionService.Ticket ticket,WebSocketSession browser){this.ticket=ticket;this.browser=browser;}
    }
}
