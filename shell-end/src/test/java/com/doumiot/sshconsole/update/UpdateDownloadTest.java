package com.doumiot.sshconsole.update;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class UpdateDownloadTest {
 @TempDir Path dir;
 @Test void streamsThroughRedirectAndRejectsOversizedOrMissingDownloads()throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/asset",x->{byte[] b={1,2,3};x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});
  server.createContext("/redirect",x->{x.getResponseHeaders().set("Location","/asset");x.sendResponseHeaders(302,-1);x.close();});
  server.createContext("/missing",x->{x.sendResponseHeaders(404,-1);x.close();});
  server.start();String url="http://127.0.0.1:"+server.getAddress().getPort();
  try {
   Path ok=dir.resolve("asset");UpdateDownload.download(url+"/redirect",ok,10,n->{});assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(ok));
   Path tooBig=dir.resolve("too-big");assertThrows(IOException.class,()->UpdateDownload.download(url+"/asset",tooBig,2,n->{}));assertFalse(Files.exists(tooBig));
   assertThrows(IOException.class,()->UpdateDownload.download(url+"/missing",dir.resolve("missing"),10,n->{}));
   assertThrows(IOException.class,()->UpdateDownload.download("file:///etc/passwd",dir.resolve("local"),10,n->{}));
  } finally {server.stop(0);}
 }
}
