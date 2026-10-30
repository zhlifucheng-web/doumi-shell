package com.doumiot.sshconsole.rdp;

import org.apache.guacamole.io.*;
import org.apache.guacamole.protocol.GuacamoleInstruction;
import org.junit.jupiter.api.*;
import org.springframework.web.socket.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RdpWebSocketTest {
    @Test void negotiatesRdpWithServerCredentialsRelaysFramesAndClosesOnCancel() throws Exception {
        try (FakeGuacd guacd=new FakeGuacd()) {
            RdpProperties props=new RdpProperties(); props.setGuacdPort(guacd.server.getLocalPort());
            RdpConnectionService profiles=mock(RdpConnectionService.class);
            when(profiles.resolve(7L)).thenReturn(new RdpTarget("192.0.2.5",13389,"alice","server-secret","EXAMPLE",false));
            RdpSessionService sessions=new RdpSessionService(props,profiles);
            RdpWebSocketHandler handler=new RdpWebSocketHandler(sessions,props);
            String token=sessions.issue(7,1280,720,96);
            WebSocketSession browser=mock(WebSocketSession.class);
            when(browser.getId()).thenReturn("browser"); when(browser.isOpen()).thenReturn(true);
            when(browser.getUri()).thenReturn(URI.create("ws://localhost/ws/ssh-console/rdp?token="+token));
            BlockingQueue<String> output=new LinkedBlockingQueue<>();
            doAnswer(call->{output.add(((TextMessage)call.getArgument(0)).getPayload());return null;}).when(browser).sendMessage(any(WebSocketMessage.class));
            try {
                handler.afterConnectionEstablished(browser);
                String uuid=output.poll(5,TimeUnit.SECONDS);
                assertNotNull(uuid); assertTrue(uuid.startsWith("0.,36."));
                assertEquals("4.sync,3.123;",output.poll(5,TimeUnit.SECONDS));
                Map<String,String> actual=guacd.connect.get(5,TimeUnit.SECONDS);
                assertEquals("192.0.2.5",actual.get("hostname"));assertEquals("13389",actual.get("port"));
                assertEquals("server-secret",actual.get("password")); assertEquals("nla",actual.get("security"));
                assertEquals("false",actual.get("ignore-cert")); assertEquals("display-update",actual.get("resize-method"));
                handler.handleMessage(browser,new TextMessage("0.,4.ping,3.456;"));
                assertEquals("0.,4.ping,3.456;",output.poll(5,TimeUnit.SECONDS));
                handler.handleMessage(browser,new TextMessage("3.key,2.65,1.1;"));
                GuacamoleInstruction input=guacd.input.poll(5,TimeUnit.SECONDS);
                assertNotNull(input); assertEquals("key",input.getOpcode()); assertEquals(Arrays.asList("65","1"),input.getArgs());
                sessions.cancel(token);
                assertTrue(guacd.eof.get(5,TimeUnit.SECONDS));
            } finally {handler.shutdown();sessions.shutdown();}
        }
    }
    @Test void invalidTicketIsRejectedWithoutOpeningGateway() throws Exception {
        RdpProperties props=new RdpProperties();
        RdpSessionService sessions=new RdpSessionService(props,mock(RdpConnectionService.class));
        RdpWebSocketHandler handler=new RdpWebSocketHandler(sessions,props);
        WebSocketSession browser=mock(WebSocketSession.class);
        when(browser.getId()).thenReturn("invalid");when(browser.isOpen()).thenReturn(true);
        when(browser.getUri()).thenReturn(URI.create("ws://localhost/ws/ssh-console/rdp?token=invalid"));
        try { handler.afterConnectionEstablished(browser); verify(browser).close(any(CloseStatus.class)); }
        finally {handler.shutdown();sessions.shutdown();}
    }
    static class FakeGuacd implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
        final CompletableFuture<Map<String,String>> connect=new CompletableFuture<>();
        final CompletableFuture<Boolean> eof=new CompletableFuture<>();
        final BlockingQueue<GuacamoleInstruction> input=new LinkedBlockingQueue<>();
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        FakeGuacd() throws IOException {
            worker.submit(()->{
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(7000);
                    ReaderGuacamoleReader in=new ReaderGuacamoleReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    WriterGuacamoleWriter out=new WriterGuacamoleWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
                    GuacamoleInstruction select=in.readInstruction();
                    if(!"select".equals(select.getOpcode()) || !select.getArgs().equals(Collections.singletonList("rdp")))throw new IOException("not RDP");
                    List<String> names=Arrays.asList("hostname","port","username","password","domain","security","ignore-cert","resize-method");
                    out.writeInstruction(new GuacamoleInstruction("args",names));
                    GuacamoleInstruction instruction;
                    while((instruction=in.readInstruction())!=null && !"connect".equals(instruction.getOpcode())) {}
                    if(instruction==null)throw new EOFException();
                    Map<String,String> params=new HashMap<>();for(int i=0;i<names.size();i++)params.put(names.get(i),instruction.getArgs().get(i));
                    connect.complete(params);
                    out.writeInstruction(new GuacamoleInstruction("ready","test-connection"));
                    out.writeInstruction(new GuacamoleInstruction("sync","123"));
                    while((instruction=in.readInstruction())!=null)input.add(instruction);
                    eof.complete(true);
                }catch(Exception e){connect.completeExceptionally(e);eof.completeExceptionally(e);}
            });
        }
        public void close() throws Exception {server.close();worker.shutdownNow();}
    }
}
