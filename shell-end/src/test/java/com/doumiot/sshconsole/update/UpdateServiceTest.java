package com.doumiot.sshconsole.update;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class UpdateServiceTest {
 @TempDir Path dir;
 @Test void detectsLegacySystemdAndContainerCgroupsWithoutEnvironmentMarkers() {
  assertTrue(UpdateService.managedCgroup("1:name=systemd:/system.slice/web-shell.service\n"));
  assertTrue(UpdateService.managedCgroup("0::/system.slice/web-shell.service\n"));
  assertTrue(UpdateService.managedCgroup("0::/user.slice/user-1000.slice/user@1000.service/app.slice/doumi.service\n"));
  assertTrue(UpdateService.managedCgroup("0::/docker/123abc\n"));
  assertTrue(UpdateService.managedCgroup("0::/kubepods.slice/pod/123abc\n"));
  assertFalse(UpdateService.managedCgroup("0::/user.slice/user-1000.slice/user@1000.service/session-7.scope\n"));
  assertFalse(UpdateService.managedCgroup("0::/\n"));
 }
 @Test void onlyPackagedSupportedLayoutsCanSelfUpdate() throws Exception {
  Path root=dir.resolve("install");Files.createDirectories(root);Path jar=root.resolve("doumi-shell.jar");Files.write(jar,new byte[]{1});
  assertThrows(IllegalStateException.class,()->UpdateService.installRoot(jar,"Linux","amd64"));
  Files.write(root.resolve("doumi-updater.jar"),new byte[]{1});
  assertEquals(root,UpdateService.installRoot(jar,"Linux","amd64"));
  assertThrows(IllegalStateException.class,()->UpdateService.installRoot(jar,"Mac OS X","aarch64"));
  assertThrows(IllegalStateException.class,()->UpdateService.installRoot(jar,"Linux","aarch64"));
  assertThrows(IllegalStateException.class,()->UpdateService.installRoot(root.resolve("dev.jar"),"Linux","amd64"));
 }
 @Test void repeatedUpdatesReplaceOldHealthAndResolvedPortArguments() {
  java.util.List<String> args=UpdateService.restartArguments(new String[]{"--server.port=0","--doumi.storage.data-dir=relative","--doumi.update.health-id=old-job","--spring.config.location=file:/custom.yml"},dir,"19873");
  assertEquals(java.util.List.of("--spring.config.location=file:/custom.yml","--doumi.storage.data-dir="+dir,"--server.port=19873"),args);
 }
 @Test void numericVersionComparisonRejectsDowngradeAndSameVersion() {
  assertTrue(UpdateService.newer("1.0.100","1.0.99"));assertFalse(UpdateService.newer("1.0.99","1.0.100"));
  assertFalse(UpdateService.newer("1.0.99","1.0.99"));assertThrows(IllegalArgumentException.class,()->UpdateService.newer("../bad","1.0.99"));
 }
 @Test void statusExposesOnlyGithubTokenPresence() throws Exception {
  com.doumiot.config.DoumiShellStorageProperties storage=new com.doumiot.config.DoumiShellStorageProperties();storage.setDataDir(dir.toString());
  org.springframework.mock.env.MockEnvironment env=new org.springframework.mock.env.MockEnvironment().withProperty("doumi.update.github-token","ghp_fixtureStatusSecret");
  UpdateSettings settings=new UpdateSettings(storage,env);
  UpdateService service=new UpdateService(settings,null,new UpdateMaintenance(),null,null,storage,env,null,null);
  try {
   java.util.Map<String,Object> status=service.status();assertEquals(true,status.get("githubTokenConfigured"));
   assertEquals(false,status.get("tokenRequired"));
   assertFalse(status.toString().contains("ghp_fixtureStatusSecret"));assertFalse(status.containsKey("githubToken"));
  }finally{service.close();}
 }
 @Test void statusReportsExplicitlyEnabledManagementTokenPolicy() throws Exception {
  com.doumiot.config.DoumiShellStorageProperties storage=new com.doumiot.config.DoumiShellStorageProperties();storage.setDataDir(dir.toString());
  org.springframework.mock.env.MockEnvironment env=new org.springframework.mock.env.MockEnvironment().withProperty("doumi.update.require-token","true").withProperty("doumi.update.token","fixture-management-secret");
  UpdateService service=new UpdateService(new UpdateSettings(storage,env),null,new UpdateMaintenance(),null,null,storage,env,null,null);
  try {java.util.Map<String,Object> status=service.status();assertEquals(true,status.get("tokenRequired"));assertFalse(status.toString().contains("fixture-management-secret"));}
  finally{service.close();}
 }
 @Test void candidateRejectsBusinessWorkUntilInstallerCommits() throws Exception {
  UpdateSettings settings=new UpdateSettings(dir,"","");
  String id=java.util.UUID.randomUUID().toString();Path job=settings.directory().resolve(id);Files.createDirectory(job);
  java.util.Properties current=new java.util.Properties();current.setProperty("job",id);com.doumiot.updater.UpdateFiles.store(settings.directory().resolve("current.properties"),current);
  java.util.Properties metadata=new java.util.Properties();metadata.setProperty("health.id","fixture-id");com.doumiot.updater.UpdateFiles.store(job.resolve("job.properties"),metadata);
  com.doumiot.updater.UpdateFiles.state(job,"RESTARTING","",90);
  UpdateMaintenance gate=new UpdateMaintenance();
  com.doumiot.config.DoumiShellStorageProperties storage=new com.doumiot.config.DoumiShellStorageProperties();storage.setDataDir(dir.toString());
  com.doumiot.sshconsole.service.SshScriptExecutionService scripts=org.mockito.Mockito.mock(com.doumiot.sshconsole.service.SshScriptExecutionService.class);
  UpdateService service=new UpdateService(settings,null,gate,scripts,null,storage,new org.springframework.mock.env.MockEnvironment().withProperty("doumi.update.health-id","fixture-id"),null,null);
  try {
   service.guardCandidate();assertTrue(gate.blocked());assertFalse(gate.enter());
   com.doumiot.updater.UpdateFiles.state(job,"ROLLBACK_FAILED","",0);service.releaseCandidate();assertTrue(gate.blocked());
   com.doumiot.updater.UpdateFiles.state(job,"SUCCEEDED","",100);service.releaseCandidate();assertFalse(gate.blocked());
   org.mockito.Mockito.verify(scripts).beginUpdate();org.mockito.Mockito.verify(scripts).endUpdate();
  }finally{service.close();}
 }

}
