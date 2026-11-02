package com.doumiot.sshconsole.update;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class UpdateDownloadTest {
 @TempDir Path dir;
 @Test void existingFilesAreNeverDeletedOrOverwritten()throws Exception {
  Path target=dir.resolve("existing");Files.write(target,new byte[]{6});
  assertThrows(FileAlreadyExistsException.class,()->UpdateDownload.download("http://127.0.0.1:1/asset",target,10,n->{}));
  assertArrayEquals(new byte[]{6},Files.readAllBytes(target));
 }
 @Test void retriesTransientResponsesAndDiscardsPartialFilesBeforeRetrying()throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger requests=new AtomicInteger();
  server.createContext("/asset",x->{int attempt=requests.incrementAndGet();
   if(attempt==1){x.sendResponseHeaders(503,-1);}else if(attempt==2){x.sendResponseHeaders(200,8);x.getResponseBody().write(new byte[]{9});}
   else {x.sendResponseHeaders(200,3);x.getResponseBody().write(new byte[]{1,2,3});}x.close();});
  server.start();
  try {Path target=dir.resolve("retry");UpdateDownload.download("http://127.0.0.1:"+server.getAddress().getPort()+"/asset","",target,100,n->{},1000,0);
   assertEquals(3,requests.get());assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(target));
  }finally{server.stop(0);}
 }
 @Test void readTimeoutRetriesAreBoundedAndDoNotLeaveAPartialFile()throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger requests=new AtomicInteger();
  server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
  server.createContext("/slow",x->{requests.incrementAndGet();try{Thread.sleep(500);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{x.close();}});server.start();
  try {Path target=dir.resolve("timeout");IOException error=assertThrows(IOException.class,()->UpdateDownload.download("http://127.0.0.1:"+server.getAddress().getPort()+"/slow","",target,100,n->{},50,0));
   assertEquals(3,requests.get());assertFalse(Files.exists(target));assertTrue(error.getMessage().contains("3"));assertTrue(error.getMessage().contains("127.0.0.1"));
  }finally{server.stop(0);((java.util.concurrent.ExecutorService)server.getExecutor()).shutdownNow();}
 }
 @Test void permanentFailuresAreNotRetriedAndProxyDoesNotResolveTargetLocally()throws Exception {
  HttpServer proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger requests=new AtomicInteger();
  proxy.createContext("/",x->{requests.incrementAndGet();assertEquals("unresolvable.invalid",x.getRequestURI().getHost());
   if(x.getRequestURI().getPath().equals("/missing")){x.sendResponseHeaders(404,-1);}else{x.sendResponseHeaders(200,2);x.getResponseBody().write(new byte[]{4,5});}x.close();});proxy.start();
  String url="http://127.0.0.1:"+proxy.getAddress().getPort();
  try {Path target=dir.resolve("proxy");UpdateDownload.download("http://unresolvable.invalid/asset",url,target,10,n->{});assertArrayEquals(new byte[]{4,5},Files.readAllBytes(target));
   IOException error=assertThrows(IOException.class,()->UpdateDownload.download("http://unresolvable.invalid/missing",url,dir.resolve("missing"),10,n->{},1000,0));
   assertEquals(2,requests.get());assertTrue(error.getMessage().contains("404"));
  }finally{proxy.stop(0);}
 }
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
