package com.doumiot.sshconsole.update;

import com.doumiot.ShellEndApplication;
import com.doumiot.config.*;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.*;
import com.doumiot.updater.UpdateFiles;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Service @SshConsoleEnabled
public class UpdateService {
    private final UpdateSettings settings;
    private final SshReleaseService releases;
    private final UpdateMaintenance maintenance;
    private final SshScriptExecutionService scripts;
    private final SshUploadService uploads;
    private final Environment env;
    private final ApplicationArguments arguments;
    private final ConfigurableApplicationContext context;
    private final Path data;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"doumi-update-prepare");t.setDaemon(true);return t;});
    private volatile boolean preparing;
    private volatile String candidateHealthId;
    public UpdateService(UpdateSettings settings,SshReleaseService releases,UpdateMaintenance maintenance,SshScriptExecutionService scripts,
                         SshUploadService uploads,DoumiShellStorageProperties storage,Environment env,ApplicationArguments arguments,ConfigurableApplicationContext context) {
        this.settings=settings;this.releases=releases;this.maintenance=maintenance;this.scripts=scripts;this.uploads=uploads;
        this.env=env;this.arguments=arguments;this.context=context;data=Path.of(storage.getDataDir()).toAbsolutePath().normalize();
    }
    @javax.annotation.PostConstruct public void guardCandidate() throws IOException {
        String id=env.getProperty("doumi.update.health-id", "");Path job=currentJob();
        if(!id.isEmpty()&&job!=null&&Files.isRegularFile(job.resolve("job.properties"))
            &&id.equals(UpdateFiles.load(job.resolve("job.properties")).getProperty("health.id"))) {
            // Do not accept user writes or scheduled scripts before the updater commits or restores data.
            candidateHealthId=id;scripts.beginUpdate();maintenance.begin();releaseCandidate();
        }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000)
    public void releaseCandidate() {
        if(candidateHealthId==null)return;
        try {
            Path job=currentJob();
            String phase=job==null?"IDLE":UpdateFiles.load(job.resolve("state.properties")).getProperty("phase","");
            if(Set.of("IDLE","SUCCEEDED","ROLLED_BACK","FAILED").contains(phase)) {
                scripts.endUpdate();maintenance.end();candidateHealthId=null;
            }
        }catch(IOException ignored){ /* Remain in maintenance until a durable result is available. */ }
    }
    public static Path installRoot(Path jar,String os,String arch) {
        if(jar==null||!Files.isRegularFile(jar)||!jar.getFileName().toString().equals("doumi-shell.jar")
                ||!(arch.equals("amd64")||arch.equals("x86_64"))||!(os.startsWith("Windows")||os.equals("Linux")))
            throw new IllegalStateException("当前运行方式不支持一键更新，请使用 Windows x64 MSI 或 Linux x64 正式安装包");
        if(!Files.isRegularFile(jar.getParent().resolve("doumi-updater.jar")))throw new IllegalStateException("当前程序未附带更新器，请先手动安装带更新器的版本");
        Path root=os.startsWith("Windows")?jar.getParent().getParent():jar.getParent();
        if(os.startsWith("Windows")&&(!Files.isDirectory(root.resolve("runtime"))||!Files.isRegularFile(root.resolve("Doumi Shell.exe"))))
            throw new IllegalStateException("未识别 Windows MSI 安装目录，请手动安装正式版本");
        if(!Files.isWritable(root))throw new IllegalStateException("运行账户没有安装目录写入权限");
        return root;
    }
    public static boolean newer(String next,String previous) {
        if(next==null||previous==null||!next.matches("[0-9]+\\.[0-9]+\\.[0-9]+")||!previous.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IllegalArgumentException("版本号无效");
        String[] a=next.split("\\."),b=previous.split("\\.");for(int i=0;i<3;i++){int c=new BigInteger(a[i]).compareTo(new BigInteger(b[i]));if(c!=0)return c>0;}return false;
    }
    private Path jar(){File source=new ApplicationHome(ShellEndApplication.class).getSource();return source==null?null:source.toPath().toAbsolutePath().normalize();}
    private Path root() {
        if(!env.getProperty("doumi.update.enabled",Boolean.class,true))throw new IllegalStateException("当前部署已关闭一键更新，请通过部署管理工具升级");
        if(Files.exists(Path.of("/.dockerenv"))||System.getenv("INVOCATION_ID")!=null||System.getenv("NOTIFY_SOCKET")!=null)
            throw new IllegalStateException("容器或托管服务请通过部署管理工具更新");
        if(env.getProperty("server.ssl.enabled",Boolean.class,false))throw new IllegalStateException("当前直接 HTTPS 部署请使用手动升级");
        Path root=installRoot(jar(),System.getProperty("os.name"),System.getProperty("os.arch"));
        if(data.equals(root)||root.startsWith(data))throw new IllegalStateException("数据目录与安装目录重叠，无法安全更新");
        return root;
    }
    public Map<String,Object> status() throws IOException {
        Map<String,Object> result=new LinkedHashMap<>();result.put("currentVersion",version());result.put("proxy",settings.proxy());
        result.put("platform",System.getProperty("os.name").startsWith("Windows")?"Windows x64":(System.getProperty("os.name").equals("Linux")?"Linux x64":System.getProperty("os.name")));
        try{root();result.put("supported",true);}catch(IllegalStateException e){result.put("supported",false);result.put("reason",e.getMessage());}
        Path job=currentJob();String phase="IDLE";
        if(job!=null&&Files.isRegularFile(job.resolve("state.properties"))) {
            Properties state=UpdateFiles.load(job.resolve("state.properties"));phase=state.getProperty("phase","IDLE");
            result.put("message",state.getProperty("message",""));result.put("progress",Integer.parseInt(state.getProperty("progress","0")));
            if(Files.exists(job.resolve("job.properties")))result.put("targetVersion",UpdateFiles.load(job.resolve("job.properties")).getProperty("version",""));
            if(!terminal(phase)&&!preparing&&!locked(settings.directory().resolve("update.lock"))&&!locked(job.resolve("installer.lock"))) {
                phase=Set.of("STOPPING","BACKING_UP","INSTALLING","RESTARTING","ROLLING_BACK").contains(phase)?"ROLLBACK_FAILED":"FAILED";
                result.put("message","上次更新被中断，请在部署机器检查更新日志与备份后重试");
            }
        }
        if(phase.equals("ROLLBACK_FAILED")){result.put("supported",false);result.put("reason","上次更新需要人工检查，请在部署机器处理后再启用更新");}
        result.put("phase",phase);result.put("busy",preparing||!terminal(phase));return result;
    }
    static boolean terminal(String phase){return Set.of("IDLE","FAILED","SUCCEEDED","ROLLED_BACK","ROLLBACK_FAILED").contains(phase);}
    private static boolean locked(Path file)throws IOException {
        try(FileChannel c=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            try(FileLock lock=c.tryLock()){return lock==null;}catch(OverlappingFileLockException e){return true;}
        }
    }
    private Path currentJob()throws IOException {
        Path current=settings.directory().resolve("current.properties");if(!Files.exists(current))return null;
        String id=UpdateFiles.load(current).getProperty("job","");if(!id.matches("[a-f0-9-]{36}"))return null;
        return settings.directory().resolve(id);
    }
    public synchronized Map<String,Object> start(String expectedVersion)throws Exception {
        Path root=root();if("ROLLBACK_FAILED".equals(status().get("phase")))throw new IllegalStateException("上次更新需要人工检查，请先处理更新日志与备份");
        if(!newer(expectedVersion,version()))throw new IllegalArgumentException("只能安装高于当前版本的正式版本");
        if(preparing||Boolean.TRUE.equals(status().get("busy")))throw new IllegalStateException("已有更新正在进行");
        FileChannel channel=FileChannel.open(settings.directory().resolve("update.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException e){channel.close();throw new IllegalStateException("已有更新正在进行");}
        if(lock==null){channel.close();throw new IllegalStateException("已有更新正在进行");}
        Path job=settings.directory().resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectory(job);UpdateSettings.privateMode(job,true);
            Properties current=new Properties();current.setProperty("job",job.getFileName().toString());UpdateFiles.store(settings.directory().resolve("current.properties"),current);
            UpdateFiles.state(job,"CHECKING","正在确认最新正式版本",0);preparing=true;
            worker.execute(()->prepare(job,root,expectedVersion,channel,lock));return status();
        }catch(Exception e){preparing=false;lock.release();channel.close();throw e;}
    }
    private void prepare(Path job,Path root,String expectedVersion,FileChannel channel,FileLock lock) {
        Process helper=null;boolean handedOff=false;
        try {
            SshReleaseService.Latest release=releases.latest(true);
            if(release.getError()!=null)throw new IOException(release.getError());
            if(!expectedVersion.equals(release.getVersion()))throw new IOException("最新版本已变化，请重新检查后再更新");
            boolean windows=System.getProperty("os.name").startsWith("Windows");
            String asset=windows?release.getWindowsUrl():release.getLinuxUrl(),checksum=windows?release.getWindowsChecksumUrl():release.getLinuxChecksumUrl();
            Path artifact=job.resolve(asset.substring(asset.lastIndexOf('/')+1)),hash=job.resolve("checksum.sha256");
            String assetRoute=settings.route(asset),checksumRoute=settings.route(checksum);
            UpdateFiles.state(job,"DOWNLOADING","正在下载安装包",5);
            UpdateDownload.download(checksumRoute,hash,4096,n->{});
            UpdateDownload.download(assetRoute,artifact,1024L*1024*1024,n->{try{UpdateFiles.state(job,"DOWNLOADING","正在下载安装包",5+n/2);}catch(IOException e){throw new UncheckedIOException(e);}});
            UpdateFiles.state(job,"VERIFYING","正在校验安装包",56);UpdateFiles.verify(artifact,Files.readString(hash));
            if(!windows) {
                UpdateFiles.extractLinux(artifact,job.resolve("expanded"),expectedVersion);
                UpdateFiles.verifyJarVersion(job.resolve("expanded/doumi-shell.jar"),expectedVersion);
                if(!Files.isRegularFile(job.resolve("expanded/doumi-updater.jar"))||!Files.isRegularFile(job.resolve("expanded/run-linux.sh")))throw new IOException("安装包缺少更新器或启动脚本");
            }
            if(windows) {
                // MSI rollback needs the previous registered product, not only old files.
                String previous=version();
                String rollbackBase=UpdateSettings.REPOSITORY+"/releases/download/v"+previous+"/Doumi.Shell-"+previous+".msi";
                Path rollbackMsi=job.resolve("Doumi.Shell-"+previous+".msi"),rollbackHash=job.resolve("rollback.sha256");
                UpdateFiles.state(job,"PREPARING","正在准备旧版本安装包以便失败恢复",57);
                UpdateDownload.download(settings.route(rollbackBase+".sha256"),rollbackHash,4096,n->{});
                UpdateDownload.download(settings.route(rollbackBase),rollbackMsi,1024L*1024*1024,n->{});
                UpdateFiles.verify(rollbackMsi,Files.readString(rollbackHash));
            }
            Path helperJar=job.resolve("doumi-updater.jar");Files.copy(jar().getParent().resolve("doumi-updater.jar"),helperJar);
            String helperJava=Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java").toString();
            if(windows) {
                UpdateFiles.state(job,"PREPARING","正在准备独立更新环境",58);
                UpdateFiles.snapshot(Path.of(System.getProperty("java.home")),job.resolve("runtime"),Collections.emptySet());
                helperJava=job.resolve("runtime/bin/java.exe").toString();
            }
            Properties p=new Properties();p.setProperty("install.root",root.toString());p.setProperty("data.root",data.toString());
            p.setProperty("parent.pid",Long.toString(ProcessHandle.current().pid()));p.setProperty("version",expectedVersion);p.setProperty("previous.version",version());
            p.setProperty("platform",windows?"windows":"linux");
            if(windows)p.setProperty("rollback.msi",job.resolve("Doumi.Shell-"+version()+".msi").toString());p.setProperty("artifact",artifact.toString());p.setProperty("app.jar",jar().toString());
            p.setProperty("working.directory",System.getProperty("user.dir"));p.setProperty("health.id",UUID.randomUUID().toString());
            String address=env.getProperty("server.address","127.0.0.1");
            if(address.equals("0.0.0.0")||address.equals("::"))address="127.0.0.1";
            if(address.contains(":")&&!address.startsWith("["))address="["+address+"]";
            p.setProperty("health.url","http://"+address+":"+env.getProperty("local.server.port",env.getProperty("server.port","80"))+env.getProperty("server.servlet.context-path","")+"/api/ssh-console/update/health");
            List<String> restart=new ArrayList<>();restart.add(Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java").toString());
            restart.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());restart.add("-Ddoumi.desktop.open-browser=false");restart.add("-jar");restart.add(jar().toString());
            restart.addAll(restartArguments(arguments.getSourceArgs(),data,env.getProperty("local.server.port",env.getProperty("server.port","80"))));
            p.setProperty("restart.count",Integer.toString(restart.size()));for(int i=0;i<restart.size();i++)p.setProperty("restart."+i,restart.get(i));
            UpdateFiles.store(job.resolve("job.properties"),p);
            // Freeze scheduled starts and HTTP admission before checking existing uploads/draining requests.
            scripts.beginUpdate();maintenance.begin();UpdateFiles.state(job,"DRAINING","正在等待进行中的请求结束",60);
            long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(maintenance.active()>0&&System.nanoTime()<deadline)Thread.sleep(200);
            if(maintenance.active()>0||uploads.activeCount()>0)throw new IOException("仍有文件传输或请求未结束，请等待完成后重试更新");
            helper=new ProcessBuilder(helperJava,"-jar",helperJar.toString(),job.resolve("job.properties").toString())
                    .directory(job.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(job.resolve("installer.log").toFile())).start();
            long readyDeadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(!Files.exists(job.resolve("helper-ready"))&&helper.isAlive()&&System.nanoTime()<readyDeadline)Thread.sleep(100);
            if(!Files.exists(job.resolve("helper-ready"))||!helper.isAlive())throw new IOException("独立更新器未能启动，当前服务继续运行");
            UpdateFiles.state(job,"STOPPING","更新器已就绪，正在重启服务",62);handedOff=true;
            context.close();System.exit(0);
        }catch(Exception e) {
            if(helper!=null&&!handedOff)helper.destroyForcibly();
            try {try(PrintWriter log=new PrintWriter(Files.newBufferedWriter(job.resolve("prepare.log")))){e.printStackTrace(log);}
                String message=e instanceof IOException?e.getMessage():"更新准备失败，请查看部署机器上的更新日志";
                UpdateFiles.state(job,"FAILED",message,0);
            }catch(IOException ignored){}
        }finally {
            if(!handedOff){maintenance.end();scripts.endUpdate();}
            preparing=false;try{lock.release();channel.close();}catch(IOException ignored){}
        }
    }
    public static List<String> restartArguments(String[] original,Path data,String port) {
        List<String> result=new ArrayList<>();
        for(String argument:original) {
            if(argument.startsWith("--server.port=")||argument.equals("--server.port")
                ||argument.startsWith("--doumi.storage.data-dir=")||argument.equals("--doumi.storage.data-dir")
                ||argument.startsWith("--doumi.update.health-id=")||argument.equals("--doumi.update.health-id"))continue;
            result.add(argument);
        }
        result.add("--doumi.storage.data-dir="+data);result.add("--server.port="+port);return result;
    }
    public String version(){
        String url=ApplicationVersion.browserUrl("/");return url.startsWith("/?v=")?url.substring(4):"0.0.0";
    }
    @javax.annotation.PreDestroy public void close(){worker.shutdown();}
}
