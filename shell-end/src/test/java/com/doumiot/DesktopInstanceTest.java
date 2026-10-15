package com.doumiot;

import com.doumiot.config.DesktopInstance;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class DesktopInstanceTest {
    @TempDir Path directory;
    private HttpServer server(String title) throws Exception {
        HttpServer server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            byte[] body=("<html><title>"+title+"</title></html>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);try(java.io.OutputStream out=exchange.getResponseBody()){out.write(body);}
        });
        server.start();return server;
    }
    @Test void duplicateUsesActualPortAndCanTakeOverAfterOriginalExits() throws Exception {
        HttpServer http=server("Doumi Shell — SSH");
        try(DesktopInstance first=new DesktopInstance(directory);DesktopInstance second=new DesktopInstance(directory)) {
            assertTrue(first.tryAcquire());assertFalse(second.tryAcquire());
            String url="http://127.0.0.1:"+http.getAddress().getPort()+"/";
            first.publish(url);
            assertEquals(url,second.runningUrl());
            first.close();assertTrue(second.tryAcquire());
            assertFalse(Files.exists(directory.resolve("desktop-instance.properties")));
        }finally {http.stop(0);}
    }
    @Test void onlyPublishedDoumiInstancesAreOpened() throws Exception {
        HttpServer other=server("Other Application");
        try(DesktopInstance first=new DesktopInstance(directory);DesktopInstance second=new DesktopInstance(directory)) {
            assertTrue(first.tryAcquire());assertNull(second.runningUrl());
            String url="http://127.0.0.1:"+other.getAddress().getPort()+"/";
            first.publish(url);assertNull(second.runningUrl());
            assertThrows(IllegalArgumentException.class,()->first.publish("http://example.com/"));
            assertThrows(IllegalArgumentException.class,()->first.publish(url+"another-path"));
            assertThrows(IllegalStateException.class,()->second.publish(url));
        }finally {other.stop(0);}
    }
    @Test void staleMetadataDoesNotPreventRestartAndStartupWaitsForReadiness() throws Exception {
        Files.write(directory.resolve("desktop-instance.properties"),"url=http://example.com/".getBytes(StandardCharsets.UTF_8));
        HttpServer http=server("Doumi Shell");
        try(DesktopInstance first=new DesktopInstance(directory);DesktopInstance second=new DesktopInstance(directory)) {
            assertTrue(first.tryAcquire());assertFalse(Files.exists(directory.resolve("desktop-instance.properties")));
            assertFalse(second.tryAcquire());assertNull(second.runningUrl());
            String url="http://127.0.0.1:"+http.getAddress().getPort()+"/";
            first.publish(url);assertEquals(url,second.runningUrl());
        }finally {http.stop(0);}
    }
}
