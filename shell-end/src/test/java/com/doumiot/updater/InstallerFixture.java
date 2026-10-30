package com.doumiot.updater;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.zip.*;
/** Real child process used by offline install/rollback integration tests. */
public class InstallerFixture {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);Files.writeString(root.resolve("fixture.pid"),Long.toString(ProcessHandle.current().pid()));
  String version;try(ZipFile zip=new ZipFile(root.resolve("doumi-shell.jar").toFile())) {version=new String(zip.getInputStream(zip.getEntry("BOOT-INF/classes/static/version.json")).readAllBytes()).contains("1.0.64")?"1.0.64":"1.0.63";}
  if(version.equals("1.0.64")){Files.writeString(root.resolve("data/doumi.db"),"candidate-data");Files.writeString(root.resolve("data/doumi.db-wal"),"candidate-wal");}
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[1])),0);
  server.createContext("/health",exchange->{
   byte[] body=("{\"id\":\"fixture-id\",\"version\":\""+version+"\"}").getBytes();
   exchange.sendResponseHeaders(version.equals("1.0.64")&&!Boolean.parseBoolean(args[2])?503:200,body.length);exchange.getResponseBody().write(body);exchange.close();
  });server.start();
 }
}
