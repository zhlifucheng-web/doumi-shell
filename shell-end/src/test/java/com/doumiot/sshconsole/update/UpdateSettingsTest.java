package com.doumiot.sshconsole.update;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class UpdateSettingsTest {
 @TempDir Path dir;
 @Test void persistsProxyAndNeverAcceptsUntrustedDownloadTargets() throws Exception {
  UpdateSettings settings=new UpdateSettings(dir,"","");
  settings.saveProxy(" https://gh-proxy.com ");
  assertEquals("https://gh-proxy.com/",new UpdateSettings(dir,"","").proxy());
  assertEquals("https://gh-proxy.com/https://github.com/zhlifucheng-web/doumi-shell/releases/download/v1.0.63/Doumi.Shell-1.0.63.msi",settings.route("https://github.com/zhlifucheng-web/doumi-shell/releases/download/v1.0.63/Doumi.Shell-1.0.63.msi"));
  assertThrows(IllegalArgumentException.class,()->settings.route("https://evil.invalid/payload"));
  for(String proxy:new String[]{"file:///tmp/","https://user:pass@host/","https://host/?key=secret","https://host/#fragment","https://host/\r\nx"})
   assertThrows(IllegalArgumentException.class,()->settings.saveProxy(proxy));
  settings.saveProxy("");assertEquals("",settings.proxy());
 }
 @Test void tokenIsStablePrivateAndCanBeOverriddenByDeployment() throws Exception {
  UpdateSettings settings=new UpdateSettings(dir,"","");
  String token=new String(Files.readAllBytes(dir.resolve("updates/admin-token")),java.nio.charset.StandardCharsets.UTF_8).trim();
  assertTrue(token.length()>=32);settings.authorize(token);
  new UpdateSettings(dir,"","").authorize(token);
  assertThrows(SecurityException.class,()->settings.authorize(""));
  assertThrows(SecurityException.class,()->settings.authorize("wrong"));
  new UpdateSettings(dir,"","deployment-secret").authorize("deployment-secret");
 }
}
