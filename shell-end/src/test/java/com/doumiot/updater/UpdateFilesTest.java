package com.doumiot.updater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;
class UpdateFilesTest {
 @TempDir Path dir;
 @Test void checksumRequiresMatchingFilenameAndContent() throws Exception {
  Path file=dir.resolve("Doumi.Shell-1.0.63.msi");Files.write(file,new byte[]{1,2,3});
  String hash=UpdateFiles.hex(MessageDigest.getInstance("SHA-256").digest(new byte[]{1,2,3}));
  UpdateFiles.verify(file,hash+"  "+file.getFileName());
  assertThrows(IOException.class,()->UpdateFiles.verify(file,hash+"  other.msi"));
  assertThrows(IOException.class,()->UpdateFiles.verify(file,"0".repeat(64)+"  "+file.getFileName()));
 }
 @Test void tarRejectsTraversalLinksAndUnexpectedFiles() throws Exception {
  for(String name:new String[]{"../escape","doumi-shell-1.0.63/../../escape","/tmp/escape","doumi-shell-1.0.63/data/doumi.db"}) {
   Path tar=tar(name,(byte)'0',new byte[]{1});
   assertThrows(IOException.class,()->UpdateFiles.extractLinux(tar,dir.resolve(UUID.randomUUID().toString()),"1.0.63"));
  }
  assertThrows(IOException.class,()->UpdateFiles.extractLinux(tar("doumi-shell-1.0.63/doumi-shell.jar",(byte)'2',new byte[0]),dir.resolve("links"),"1.0.63"));
 }
 @Test void extractsOnlyPackagedProgramFiles() throws Exception {
  Path target=dir.resolve("expanded");UpdateFiles.extractLinux(tar("doumi-shell-1.0.63/doumi-shell.jar",(byte)'0',new byte[]{4,5}),target,"1.0.63");
  assertArrayEquals(new byte[]{4,5},Files.readAllBytes(target.resolve("doumi-shell.jar")));
 }
 @Test void dataSnapshotRestoresDatabaseAndRemovesCandidateWalWithoutTouchingUpdates() throws Exception {
  Path data=dir.resolve("data"),backup=dir.resolve("backup");Files.createDirectories(data.resolve("updates"));
  Files.writeString(data.resolve("doumi.db"),"old");Files.writeString(data.resolve("key"),"secret");Files.writeString(data.resolve("updates/state"),"running");
  UpdateFiles.snapshot(data,backup,Set.of("updates","logs","doumi.lock","desktop-instance.properties"));
  Files.writeString(data.resolve("doumi.db"),"new");Files.writeString(data.resolve("doumi.db-wal"),"new-wal");
  UpdateFiles.restore(data,backup,Set.of("updates","logs","doumi.lock","desktop-instance.properties"));
  assertEquals("old",Files.readString(data.resolve("doumi.db")));assertFalse(Files.exists(data.resolve("doumi.db-wal")));
  assertEquals("secret",Files.readString(data.resolve("key")));assertEquals("running",Files.readString(data.resolve("updates/state")));
 }
 static Path tar(String name,byte type,byte[] content) throws Exception {
  Path path=Files.createTempFile("doumi-tar-test-",".tar.gz");path.toFile().deleteOnExit();
  byte[] h=new byte[512];System.arraycopy(name.getBytes(java.nio.charset.StandardCharsets.UTF_8),0,h,0,name.length());
  byte[] length=String.format("%011o",content.length).getBytes();System.arraycopy(length,0,h,124,length.length);h[156]=type;
  Arrays.fill(h,148,156,(byte)' ');long sum=0;for(byte b:h)sum+=b&255;
  byte[] checksum=String.format("%06o\0 ",sum).getBytes();System.arraycopy(checksum,0,h,148,8);
  try(OutputStream out=new GZIPOutputStream(Files.newOutputStream(path))){out.write(h);out.write(content);out.write(new byte[(512-content.length%512)%512]);out.write(new byte[1024]);}
  return path;
 }
}
