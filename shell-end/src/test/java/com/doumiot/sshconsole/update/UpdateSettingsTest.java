package com.doumiot.sshconsole.update;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class UpdateSettingsTest {
 @TempDir Path dir;
 @Test void defaultDoesNotRequireOrGenerateManagementToken() throws Exception {
  com.doumiot.config.DoumiShellStorageProperties storage=new com.doumiot.config.DoumiShellStorageProperties();storage.setDataDir(dir.toString());
  UpdateSettings settings=new UpdateSettings(storage,new org.springframework.mock.env.MockEnvironment());
  assertDoesNotThrow(()->settings.authorize(null));assertFalse(Files.exists(dir.resolve("updates/admin-token")));
  Files.writeString(dir.resolve("updates/admin-token"),"unused-invalid-old-token");
  assertDoesNotThrow(()->new UpdateSettings(storage,new org.springframework.mock.env.MockEnvironment()).authorize(null));
 }
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
  UpdateSettings settings=new UpdateSettings(dir,"","",true);
  String token=new String(Files.readAllBytes(dir.resolve("updates/admin-token")),java.nio.charset.StandardCharsets.UTF_8).trim();
  assertTrue(token.length()>=32);settings.authorize(token);
  new UpdateSettings(dir,"","",true).authorize(token);
  assertThrows(SecurityException.class,()->settings.authorize(""));
  assertThrows(SecurityException.class,()->settings.authorize("wrong"));
  new UpdateSettings(dir,"","deployment-secret",true).authorize("deployment-secret");
 }
 @Test void rejectsGithubTokensWithHeaderInjection() throws Exception {
  com.doumiot.config.DoumiShellStorageProperties storage=new com.doumiot.config.DoumiShellStorageProperties();storage.setDataDir(dir.toString());
  assertThrows(IllegalArgumentException.class,()->new UpdateSettings(storage,new org.springframework.mock.env.MockEnvironment().withProperty("doumi.update.github-token","secret\r\nInjected: value")));
 }
 UpdateSettings configured(String environmentToken) throws Exception {
  com.doumiot.config.DoumiShellStorageProperties storage=new com.doumiot.config.DoumiShellStorageProperties();storage.setDataDir(dir.toString());
  UpdateSettings settings=new UpdateSettings(storage,new org.springframework.mock.env.MockEnvironment().withProperty("doumi.update.github-token",environmentToken));
  com.doumiot.sshconsole.crypto.CredentialCipher cipher=new com.doumiot.sshconsole.crypto.CredentialCipher(new com.doumiot.sshconsole.config.SshConsoleProperties(),storage);
  org.springframework.test.util.ReflectionTestUtils.invokeMethod(cipher,"init");org.springframework.test.util.ReflectionTestUtils.setField(settings,"cipher",cipher);return settings;
 }
 @Test void encryptsGithubTokenAndRetainsItOnProxySaveAcrossRestarts() throws Exception {
  UpdateSettings settings=configured("environmentToken");assertEquals("environmentToken",settings.githubToken());
  settings.saveSettings("https://gh-proxy.com/","ghp_fixtureSecret123");
  Path file=dir.resolve("updates/settings.properties");assertFalse(Files.readString(file).contains("ghp_fixtureSecret123"));
  UpdateSettings reloaded=configured("differentEnvironmentToken");assertEquals("ghp_fixtureSecret123",reloaded.githubToken());
  reloaded.saveProxy("");assertEquals("ghp_fixtureSecret123",configured("").githubToken());
  assertTrue(reloaded.hasGithubToken());assertTrue(reloaded.revision()>0);
  if(Files.getFileStore(file).supportsFileAttributeView("posix"))assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
 }
 @Test void clearingOverridesEnvironmentAndInvalidInputDoesNotPartiallySave() throws Exception {
  UpdateSettings settings=configured("environmentToken");settings.saveSettings(null,"");
  assertFalse(settings.hasGithubToken());assertEquals("",configured("environmentToken").githubToken());
  assertThrows(IllegalArgumentException.class,()->settings.saveSettings("https://gh-proxy.com/","invalid\nToken"));
  assertEquals("",settings.proxy());assertEquals("",settings.githubToken());
 }
 @Test void unreadableEncryptedTokenNeverAppearsInErrors() throws Exception {
  java.util.Properties props=new java.util.Properties();props.setProperty("github-token.enc","notValidCiphertext");
  Files.createDirectories(dir.resolve("updates"));try(java.io.OutputStream out=Files.newOutputStream(dir.resolve("updates/settings.properties"))){props.store(out,"");}
  IllegalStateException error=assertThrows(IllegalStateException.class,()->configured("").githubToken());
  assertFalse(error.getMessage().contains("notValidCiphertext"));assertTrue(error.getMessage().contains("重新保存"));
 }
}
