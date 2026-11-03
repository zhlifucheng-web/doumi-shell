package com.doumiot.updater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SystemdControlTest {
 @TempDir Path dir;
 @Test void identifiesOnlySystemServiceAndValidatesOwnership()throws Exception {
  assertEquals("web-shell.service",SystemdControl.owningUnit("1:name=systemd:/system.slice/web-shell.service\n"));
  assertEquals("web-shell.service",SystemdControl.owningUnit("0::/system.slice/web-shell.service/child\n"));
  assertEquals("",SystemdControl.owningUnit("0::/user.slice/user-1000.slice/user@1000.service/app.slice/web-shell.service\n"));
  for(String bad:new String[]{"../web-shell.service","--all","web shell.service","other.socket","web;reboot.service"})assertThrows(IllegalArgumentException.class,()->SystemdControl.validateUnit(bad));
  Properties p=new Properties();p.setProperty("MainPID","123");p.setProperty("User","root");p.setProperty("WorkingDirectory",dir.toString());p.setProperty("KillMode","control-group");
  assertDoesNotThrow(()->SystemdControl.verifyOwnership("web-shell.service",p,dir,124,123,0));
  assertThrows(java.io.IOException.class,()->SystemdControl.verifyOwnership("web-shell.service",p,dir,124,125,0));
  assertThrows(java.io.IOException.class,()->SystemdControl.verifyOwnership("web-shell.service",p,dir,124,123,1000));
  p.setProperty("KillMode","process");assertThrows(java.io.IOException.class,()->SystemdControl.verifyOwnership("web-shell.service",p,dir,124,123,0));p.setProperty("KillMode","control-group");
  p.setProperty("WorkingDirectory",dir.resolve("other").toString());assertThrows(java.io.IOException.class,()->SystemdControl.verifyOwnership("web-shell.service",p,dir,124,123,0));
 }
 @Test @DisabledOnOs(OS.WINDOWS) // Runtime systemd drop-ins require POSIX file permissions.
 void transientHelperAndHealthOverrideAreIsolatedFromOriginalService()throws Exception {
  String id=UUID.randomUUID().toString();Path job=dir.resolve(id);Files.createDirectory(job);
  List<String> command=SystemdControl.launchCommand("/jdk/bin/java",job.resolve("doumi-updater.jar"),job.resolve("job.properties"));
  assertTrue(command.contains("--unit=doumi-update-"+id+".service"));assertFalse(command.stream().anyMatch(arg->arg.startsWith("--property=")),"systemd 219 rejects transient WorkingDirectory and UMask assignments");assertEquals(job.resolve("job.properties").toString(),command.get(command.size()-1));
  List<List<String>> calls=new ArrayList<>();
  SystemdControl c=new SystemdControl("web-shell.service",id,dir,cmd->{calls.add(new ArrayList<>(cmd));return "MainPID=0\nActiveState=inactive\nSubState=dead\n";});
  Path sibling=dir.resolve("web-shell.service.d/10-existing.conf");Files.createDirectories(sibling.getParent());Files.writeString(sibling,"existing");
  try {c.stop();c.start();Path file=sibling.resolveSibling("90-doumi-update-health.conf");assertTrue(Files.readString(file).contains("DOUMI_UPDATE_HEALTH_ID="+id));assertEquals("existing",Files.readString(sibling));}
  finally {c.close();}
  assertFalse(Files.exists(sibling.resolveSibling("90-doumi-update-health.conf")));assertEquals("existing",Files.readString(sibling));
  assertTrue(calls.stream().anyMatch(x->x.contains("daemon-reload")));
 }
 @Test void neverOverwritesAnExistingHealthDropin()throws Exception {
  Path file=dir.resolve("web-shell.service.d/90-doumi-update-health.conf");Files.createDirectories(file.getParent());Files.writeString(file,"external");
  SystemdControl c=new SystemdControl("web-shell.service",UUID.randomUUID().toString(),dir,cmd->"");
  assertThrows(java.io.IOException.class,c::start);c.close();assertEquals("external",Files.readString(file));
 }
}
