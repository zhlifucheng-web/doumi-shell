package com.doumiot.updater;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs outside the app/runtime being replaced. Only consumes server-created private jobs. */
public final class UpdateInstaller {
    public interface ServiceControl extends AutoCloseable {
        void stop()throws Exception;void start()throws Exception;boolean running()throws Exception;void close()throws Exception;
    }
    private static final Set<String> DATA_EXCLUDES=Set.of("updates","logs","doumi.lock","desktop-instance.properties");
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected job.properties");
        Path jobFile=Path.of(args[0]).toAbsolutePath().normalize(),job=jobFile.getParent();
        Properties p=UpdateFiles.load(jobFile);
        boolean managed=!p.getProperty("systemd.unit","").isEmpty();
        if(managed) {
            PrintStream log=new PrintStream(Files.newOutputStream(job.resolve("installer.log"),StandardOpenOption.CREATE,StandardOpenOption.APPEND),true,"UTF-8");
            System.setOut(log);System.setErr(log);
            int uid=((Number)Files.getAttribute(Path.of("/proc/self"),"unix:uid")).intValue();
            long parent=Long.parseLong(p.getProperty("parent.pid"));
            SystemdControl.verifyOwnership(p.getProperty("systemd.unit"),SystemdControl.show(p.getProperty("systemd.unit")),Path.of(p.getProperty("install.root")),parent,parent,uid);
        }
        try(FileChannel channel=FileChannel.open(job.resolve("installer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=channel.tryLock()) {
            if(lock==null)throw new IOException("Another installer owns this job");
            // Signal handoff readiness before the parent exits. Keep this JVM outside the MSI install tree.
            Files.writeString(job.resolve("helper-ready"),Long.toString(ProcessHandle.current().pid()));
            long pid=Long.parseLong(p.getProperty("parent.pid"));
            Optional<ProcessHandle> parent=ProcessHandle.of(pid);
            if(managed) {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(!Files.exists(job.resolve("handoff-ready"))&&parent.isPresent()&&parent.get().isAlive()&&System.nanoTime()<deadline)Thread.sleep(100);
                if(!Files.exists(job.resolve("handoff-ready")))throw new IOException("应用未确认 systemd 更新交接");
            }else if(parent.isPresent())parent.get().onExit().get(120,TimeUnit.SECONDS);
            try(FileChannel global=FileChannel.open(job.getParent().resolve("update.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                FileLock globalLock=acquire(global)) {
                if(globalLock==null)throw new IOException("Another update owns this installation");
                install(job,p);
            }
        }catch(Exception error) {
            error.printStackTrace();UpdateFiles.state(job,"FAILED","更新未完成，请查看部署机器的更新日志后重试",0);System.exit(1);
        }
    }
    private static FileLock acquire(FileChannel channel)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        do {FileLock lock=channel.tryLock();if(lock!=null)return lock;Thread.sleep(100);}while(System.nanoTime()<deadline);
        throw new IOException("Another update owns this installation");
    }
    static void install(Path job,Properties p)throws Exception {
        ServiceControl control=p.getProperty("systemd.unit","").isEmpty()?null:new SystemdControl(p.getProperty("systemd.unit"),p.getProperty("health.id"));
        install(job,p,control);
    }
    static void install(Path job,Properties p,ServiceControl service)throws Exception {
        Path root=Path.of(p.getProperty("install.root")),data=Path.of(p.getProperty("data.root"));
        if(data.equals(root)||root.startsWith(data))throw new IOException("程序目录和数据目录重叠");
        Set<String> excludes=new HashSet<>();
        if(data.startsWith(root))excludes.add(root.relativize(data).getName(0).toString());
        Path programBackup=job.resolve("backup/program"),dataBackup=job.resolve("backup/data");
        Process candidate=null;boolean installed=false,windows=p.getProperty("platform").equals("windows");
        try {
            if(service!=null){UpdateFiles.state(job,"STOPPING","正在停止原 systemd 服务",62);service.stop();}
            UpdateFiles.state(job,"BACKING_UP","服务已停止，正在备份程序和数据",65);
            if(windows)UpdateFiles.snapshot(root,programBackup,excludes);else UpdateFiles.snapshotLinuxProgram(root,programBackup);
            UpdateFiles.snapshot(data,dataBackup,DATA_EXCLUDES);
            UpdateFiles.state(job,"INSTALLING","正在安装新版本",75);
            installed=true;
            if(windows) {
                runMsi(job,List.of("/i",p.getProperty("artifact"),"INSTALLDIR="+root),"install");
            } else UpdateFiles.snapshot(job.resolve("expanded"),root,Collections.emptySet());
            UpdateFiles.verifyJarVersion(Path.of(p.getProperty("app.jar")),p.getProperty("version"));
            UpdateFiles.state(job,"RESTARTING","正在启动新版本并检查服务",90);
            if(service!=null)service.start();else candidate=start(job,p,false);
            if(!awaitHealth(p,candidate,p.getProperty("version"),service))throw new IOException("New application health check failed");
            UpdateFiles.state(job,"SUCCEEDED","更新完成",100);
        }catch(Exception error) {
            error.printStackTrace();
            if(error instanceof InstallerStillRunningException)return;
            if(service!=null)try{service.stop();}catch(Exception stopError){
                stopError.printStackTrace();UpdateFiles.state(job,"ROLLBACK_FAILED","无法确认 systemd 服务已停止，保留备份并等待人工检查",0);return;
            }
            if(candidate!=null)try{stop(candidate);}catch(Exception stopError){
                stopError.printStackTrace();UpdateFiles.state(job,"ROLLBACK_FAILED","新进程未能停止，请在部署机器检查进程与备份",0);return;
            }
            try {
                if(installed) {
                    UpdateFiles.state(job,"ROLLING_BACK","新版本未通过检查，正在恢复旧程序和数据",90);
                    if(p.getProperty("platform").equals("windows")) {
                        runMsi(job,List.of("/x",p.getProperty("artifact")),"remove-candidate");
                        runMsi(job,List.of("/i",p.getProperty("rollback.msi"),"INSTALLDIR="+root),"restore-previous");
                    }
                    if(windows)UpdateFiles.restore(root,programBackup,excludes);else UpdateFiles.restoreLinuxProgram(root,programBackup);
                    UpdateFiles.restore(data,dataBackup,DATA_EXCLUDES);
                }
                Process old=null;if(service!=null)service.start();else old=start(job,p,true);
                boolean healthy=awaitHealth(p,old,p.getProperty("previous.version"),service);
                UpdateFiles.state(job,healthy?(installed?"ROLLED_BACK":"FAILED"):"ROLLBACK_FAILED",
                        healthy?"更新失败，旧版本已恢复运行；请查看更新日志后重试":"旧版本恢复启动未通过检查，请在部署机器查看更新日志及备份",0);
            } catch(Exception rollbackError) {
                rollbackError.printStackTrace();UpdateFiles.state(job,"ROLLBACK_FAILED","自动恢复未完成，请在部署机器查看更新日志及备份",0);
            }
        }finally {
            if(service!=null)try{service.close();}catch(Exception cleanupError){cleanupError.printStackTrace();UpdateFiles.state(job,"ROLLBACK_FAILED","systemd 临时配置清理失败，请在部署机器检查更新日志",0);}
        }
    }
    static void runMsi(Path job,List<String> operation,String name)throws Exception {
        List<String> command=new ArrayList<>();command.add("msiexec.exe");command.addAll(operation);
        command.addAll(List.of("/qn","/norestart","/L*v",job.resolve("msi-"+name+".log").toString()));
        Process msi=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(job.resolve("installer.log").toFile())).start();
        if(!msi.waitFor(10,TimeUnit.MINUTES)) {
            // Windows Installer can continue in its service after a client exits. Do not restore files underneath it.
            UpdateFiles.state(job,"ROLLBACK_FAILED","Windows Installer 超时，请在部署机器检查安装状态及更新日志",0);
            throw new InstallerStillRunningException();
        }
        int code=msi.exitValue();
        if(code!=0&&code!=3010&&!(name.equals("remove-candidate")&&(code==1605||code==1614)))throw new IOException("MSI failed: "+code);
    }
    static final class InstallerStillRunningException extends IOException {}
    static Process start(Path job,Properties p,boolean rollback)throws IOException {
        int count=Integer.parseInt(p.getProperty("restart.count"));List<String> command=new ArrayList<>();
        for(int i=0;i<count;i++)command.add(p.getProperty("restart."+i));
        // Application arguments carry precedence over JVM system properties in Spring Boot.
        command.add("--doumi.update.health-id="+p.getProperty("health.id"));
        return new ProcessBuilder(command).directory(new File(p.getProperty("working.directory")))
                .redirectInput(ProcessBuilder.Redirect.from(new File(System.getProperty("os.name").startsWith("Windows")?"NUL":"/dev/null")))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(job.resolve(rollback?"rollback.log":"application.log").toFile())).start();
    }
    static void stop(Process process)throws Exception {
        process.descendants().forEach(ProcessHandle::destroy);process.destroy();
        if(!process.waitFor(30,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();process.waitFor(30,TimeUnit.SECONDS);}
        if(process.isAlive())throw new IOException("Cannot stop candidate safely; backups retained");
    }
    static boolean awaitHealth(Properties p,Process process,String version)throws InterruptedException {
        return awaitHealth(p,process,version,null);
    }
    static boolean awaitHealth(Properties p,Process process,String version,ServiceControl service)throws InterruptedException {
        long deadline=System.nanoTime()+Duration.ofSeconds(Long.parseLong(p.getProperty("health.timeout.seconds","120"))).toNanos();
        while(System.nanoTime()<deadline) {
            try{if(service!=null?!service.running():!process.isAlive())return false;}catch(Exception error){return false;}
            if(healthy(p.getProperty("health.url"),p.getProperty("health.id"),version))return true;
            Thread.sleep(500);
        }
        return false;
    }
    static boolean matchesHealth(String body,String id,String version) {
        return java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\""+java.util.regex.Pattern.quote(id)+"\"").matcher(body).find()
            &&java.util.regex.Pattern.compile("\"version\"\\s*:\\s*\""+java.util.regex.Pattern.quote(version)+"\"").matcher(body).find();
    }
    static boolean healthy(String address,String id,String version) {
        HttpURLConnection c=null;
        try {
            c=(HttpURLConnection)new URL(address+"?id="+id).openConnection(Proxy.NO_PROXY);c.setConnectTimeout(1000);c.setReadTimeout(1000);c.setInstanceFollowRedirects(false);
            if(c.getResponseCode()!=200)return false;
            String body;try(InputStream in=c.getInputStream()){body=new String(in.readNBytes(2048),StandardCharsets.UTF_8);}
            return matchesHealth(body,id,version);
        }catch(IOException e){return false;}finally{if(c!=null)c.disconnect();}
    }
}
