package com.doumiot.sshconsole.rdp;

import org.apache.guacamole.io.*;
import org.apache.guacamole.protocol.GuacamoleInstruction;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RdpConnectionTestServiceTest {
    RdpConnectionTestService probe(FakeGuacd gateway, int timeout) {
        RdpProperties properties=new RdpProperties(); properties.setGuacdPort(gateway.server.getLocalPort());
        RdpConnectionService profiles=mock(RdpConnectionService.class);
        when(profiles.resolveTest(any())).thenReturn(new RdpTarget("192.0.2.1",3392,"draft-user","draft-secret","DRAFT",true));
        return new RdpConnectionTestService(properties,profiles,timeout,1);
    }
    @Test void renderedDesktopFrameSucceedsAndClosesTransport() throws Exception {
        try(FakeGuacd gateway=new FakeGuacd("success"); RdpConnectionTestService probe=probe(gateway,2000)) {
            RdpConnectionTestService.TestResult result=probe.test(new RdpConnectionService.TestRequest());
            assertTrue(result.isSuccess(),result.getMessage()); assertTrue(result.getElapsedMs()>=0);
            Map<String,String> parameters=gateway.parameters.get(2,TimeUnit.SECONDS);
            assertEquals("draft-user",parameters.get("username")); assertEquals("draft-secret",parameters.get("password"));
            assertEquals("3392",parameters.get("port")); assertEquals("true",parameters.get("ignore-cert"));
            assertEquals("nla",parameters.get("security"));
            assertTrue(gateway.closed.get(2,TimeUnit.SECONDS));
        }
    }
    @Test void readyAndPreAuthenticationEmptyFrameDoNotSucceedAndDeadlineClosesTransport() throws Exception {
        for(String mode:Arrays.asList("ready","empty-frame","trickle")) {
            try(FakeGuacd gateway=new FakeGuacd(mode);RdpConnectionTestService probe=probe(gateway,250)) {
                long start=System.nanoTime();
                RdpConnectionTestService.TestResult result=probe.test(new RdpConnectionService.TestRequest());
                assertFalse(result.isSuccess(),mode); assertTrue(result.getMessage().contains("超时"),result.getMessage());
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000);
                assertTrue(gateway.closed.get(2,TimeUnit.SECONDS));
            }
        }
    }
    @Test void authenticationAndCertificateErrorsAreActionableAndNeverEchoRemoteSecrets() throws Exception {
        for(String mode:Arrays.asList("authentication","certificate")) {
            try(FakeGuacd gateway=new FakeGuacd(mode);RdpConnectionTestService probe=probe(gateway,2000)) {
                RdpConnectionTestService.TestResult result=probe.test(new RdpConnectionService.TestRequest());
                assertFalse(result.isSuccess()); assertFalse(result.getMessage().contains("draft-secret"));
                assertTrue(result.getMessage().contains(mode.equals("authentication")?"认证":"证书"),result.getMessage());
                assertTrue(gateway.closed.get(2,TimeUnit.SECONDS));
            }
        }
    }
    @Test void concurrentProbesAreBoundedAndShutdownCancelsActiveTransport() throws Exception {
        try(FakeGuacd gateway=new FakeGuacd("ready"); RdpConnectionTestService probe=probe(gateway,5000)) {
            ExecutorService caller=Executors.newSingleThreadExecutor();
            try {
                Future<RdpConnectionTestService.TestResult> running=caller.submit(()->probe.test(new RdpConnectionService.TestRequest()));
                gateway.parameters.get(2,TimeUnit.SECONDS);
                RdpConnectionTestService.TestResult busy=probe.test(new RdpConnectionService.TestRequest());
                assertFalse(busy.isSuccess()); assertTrue(busy.getMessage().contains("稍后"));
                probe.close(); assertFalse(running.get(2,TimeUnit.SECONDS).isSuccess());
                assertTrue(gateway.closed.get(2,TimeUnit.SECONDS));
                assertFalse(probe.test(new RdpConnectionService.TestRequest()).isSuccess());
            } finally {caller.shutdownNow();}
        }
    }
    @Test void deadlineBoundsCallerEvenWhenTargetResolutionIsSlow() throws Exception {
        RdpProperties properties=new RdpProperties();
        RdpConnectionService profiles=mock(RdpConnectionService.class);
        CountDownLatch release=new CountDownLatch(1);
        when(profiles.resolveTest(any())).thenAnswer(call->{
            try {release.await(1,TimeUnit.SECONDS);} catch(InterruptedException ignored) {}
            return new RdpTarget("192.0.2.1",3389,"u","p","",false);
        });
        try(RdpConnectionTestService probe=new RdpConnectionTestService(properties,profiles,100,1)) {
            long start=System.nanoTime();
            assertFalse(probe.test(new RdpConnectionService.TestRequest()).isSuccess());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500,"caller must have its own total deadline");
        } finally {release.countDown();}
    }

    static class FakeGuacd implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final CompletableFuture<Map<String,String>> parameters=new CompletableFuture<>();
        final CompletableFuture<Boolean> closed=new CompletableFuture<>();
        FakeGuacd(String mode) throws IOException {
            worker.submit(()->{
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(7000);
                    ReaderGuacamoleReader input=new ReaderGuacamoleReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    WriterGuacamoleWriter output=new WriterGuacamoleWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
                    assertEquals("select",input.readInstruction().getOpcode());
                    List<String> keys=Arrays.asList("hostname","port","username","password","domain","security","ignore-cert");
                    output.writeInstruction(new GuacamoleInstruction("args",keys));
                    GuacamoleInstruction next;
                    do {next=input.readInstruction();} while(next!=null&&!next.getOpcode().equals("connect"));
                    if(next==null)throw new EOFException();
                    Map<String,String> actual=new HashMap<>(); for(int i=0;i<keys.size();i++)actual.put(keys.get(i),next.getArgs().get(i));
                    parameters.complete(actual);
                    output.writeInstruction(new GuacamoleInstruction("ready","test"));
                    if(mode.equals("success")) {
                        output.writeInstruction(new GuacamoleInstruction("size","0","1024","768"));
                        output.writeInstruction(new GuacamoleInstruction("sync","1234"));
                    } else if(mode.equals("empty-frame")) {
                        output.writeInstruction(new GuacamoleInstruction("size","0","0","0"));
                        output.writeInstruction(new GuacamoleInstruction("sync","1234"));
                    } else if(mode.equals("authentication")) output.writeInstruction(new GuacamoleInstruction("error","draft-secret auth failed","769"));
                    else if(mode.equals("certificate")) output.writeInstruction(new GuacamoleInstruction("error","certificate rejected draft-secret","519"));
                    if(mode.equals("trickle")) {
                        // Keep the peer alive while feeding an incomplete instruction; SO_TIMEOUT alone is insufficient.
                        try { for(int i=0;i<100;i++){socket.getOutputStream().write('1');socket.getOutputStream().flush();Thread.sleep(30);} }
                        catch(SocketException expected) {closed.complete(true);return;}
                    }
                    try {while(input.readInstruction()!=null) {}} catch(org.apache.guacamole.GuacamoleConnectionClosedException expected) {}
                    closed.complete(true);
                }catch(Throwable failure){parameters.completeExceptionally(failure);closed.completeExceptionally(failure);}
            });
        }
        public void close() throws Exception {server.close();worker.shutdownNow();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}
    }
}
