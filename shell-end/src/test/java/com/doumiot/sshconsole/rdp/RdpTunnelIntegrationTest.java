package com.doumiot.sshconsole.rdp;

import com.doumiot.ShellEndApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=ShellEndApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"doumi.storage.data-dir=./target/rdp-ws-data/${random.uuid}","logging.level.root=WARN"})
class RdpTunnelIntegrationTest {
    @LocalServerPort int port;
    @Autowired RdpSessionService sessions;
    @Autowired RdpProperties properties;
    @MockBean RdpConnectionService profiles;
    @Test void realWebSocketNegotiatesGuacamoleAndBlocksForeignOrigins() throws Exception {
        when(profiles.resolve(7L)).thenReturn(new RdpTarget("192.0.2.5",13389,"alice","test-secret","",false));
        StandardWebSocketClient client=new StandardWebSocketClient();
        String rejected=sessions.issue(7,1280,720,96);
        WebSocketHttpHeaders foreign=new WebSocketHttpHeaders();
        foreign.setOrigin("https://untrusted.example");foreign.setSecWebSocketProtocol(Collections.singletonList("guacamole"));
        assertThrows(ExecutionException.class,()->client.doHandshake(new TextWebSocketHandler(),foreign,uri(rejected)).get(5,TimeUnit.SECONDS));
        sessions.cancel(rejected);
        try(RdpWebSocketTest.FakeGuacd guacd=new RdpWebSocketTest.FakeGuacd()) {
            properties.setGuacdPort(guacd.server.getLocalPort());
            String token=sessions.issue(7,1280,720,96);
            BlockingQueue<String> output=new LinkedBlockingQueue<>();
            WebSocketHttpHeaders same=new WebSocketHttpHeaders();
            same.setOrigin("http://localhost:"+port);same.setSecWebSocketProtocol(Collections.singletonList("guacamole"));
            WebSocketSession socket=client.doHandshake(new TextWebSocketHandler(){
                @Override protected void handleTextMessage(WebSocketSession session,TextMessage message){output.add(message.getPayload());}
            },same,uri(token)).get(5,TimeUnit.SECONDS);
            try {
                assertEquals("guacamole",socket.getAcceptedProtocol());
                assertTrue(output.poll(5,TimeUnit.SECONDS).startsWith("0.,36."));
                assertEquals("4.sync,3.123;",output.poll(5,TimeUnit.SECONDS));
                socket.sendMessage(new TextMessage("3.key,2.65,1.1;"));
                assertEquals("key",guacd.input.poll(5,TimeUnit.SECONDS).getOpcode());
            } finally {socket.close();sessions.cancel(token);}
            assertTrue(guacd.eof.get(5,TimeUnit.SECONDS));
        }
    }
    URI uri(String token){return URI.create("ws://localhost:"+port+"/ws/ssh-console/rdp?token="+token);}
}
