package com.doumiot.updater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.net.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
class UpdateInstallerTest {
 @TempDir Path dir;
 @Test void healthProbeMatchesNonceAndVersionDespiteJsonWhitespace() {
  assertTrue(UpdateInstaller.matchesHealth("{\"id\" : \"abc\", \"version\" : \"1.0.64\"}","abc","1.0.64"));
  assertFalse(UpdateInstaller.matchesHealth("{\"id\":\"old\",\"version\":\"1.0.64\"}","abc","1.0.64"));
 }
 @Test void failedCandidateRestoresProgramDataAndRestartsPreviousVersion() throws Exception { scenario(false); }
 @Test void healthyCandidateCommitsWithoutTouchingExistingData() throws Exception { scenario(true); }
 void scenario(boolean healthy)throws Exception {
  Path root=dir.resolve("install"),data=root.resolve("data"),job=data.resolve("updates/job");Files.createDirectories(job.resolve("expanded"));
  jar(root.resolve("doumi-shell.jar"),"1.0.63");jar(job.resolve("expanded/doumi-shell.jar"),"1.0.64");
  Files.writeString(data.resolve("doumi.db"),"old-data");Files.writeString(data.resolve("master.key"),"old-key");
  int port;try(ServerSocket s=new ServerSocket(0)){port=s.getLocalPort();}
  Properties p=new Properties();p.setProperty("install.root",root.toString());p.setProperty("data.root",data.toString());p.setProperty("platform","linux");
  p.setProperty("version","1.0.64");p.setProperty("previous.version","1.0.63");p.setProperty("app.jar",root.resolve("doumi-shell.jar").toString());
  p.setProperty("health.url","http://127.0.0.1:"+port+"/health");p.setProperty("health.id","fixture-id");p.setProperty("health.timeout.seconds","3");p.setProperty("working.directory",root.toString());
  List<String> command=List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-cp",Path.of("target/test-classes").toAbsolutePath().toString(),InstallerFixture.class.getName(),root.toString(),Integer.toString(port),Boolean.toString(healthy));
  p.setProperty("restart.count",Integer.toString(command.size()));for(int i=0;i<command.size();i++)p.setProperty("restart."+i,command.get(i));
  try {
   UpdateInstaller.install(job,p);
   String state=UpdateFiles.load(job.resolve("state.properties")).getProperty("phase");assertEquals(healthy?"SUCCEEDED":"ROLLED_BACK",state);
   UpdateFiles.verifyJarVersion(root.resolve("doumi-shell.jar"),healthy?"1.0.64":"1.0.63");
   assertEquals(healthy?"candidate-data":"old-data",Files.readString(data.resolve("doumi.db")));
   assertEquals("old-key",Files.readString(data.resolve("master.key")));
   if(!healthy)assertFalse(Files.exists(data.resolve("doumi.db-wal")));
  } finally {
   Path pid=root.resolve("fixture.pid");if(Files.exists(pid))ProcessHandle.of(Long.parseLong(Files.readString(pid))).ifPresent(h->{h.destroy();try{h.onExit().get(10,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception ignored){}});
  }
 }
 static void jar(Path path,String version)throws Exception {
  try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(path))){out.putNextEntry(new ZipEntry("BOOT-INF/classes/static/version.json"));out.write(("{\"version\":\""+version+"\"}").getBytes());out.closeEntry();}
 }
}
