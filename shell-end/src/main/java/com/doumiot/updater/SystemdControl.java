package com.doumiot.updater;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Controls only a server-discovered root system unit; compatible with systemd 219. */
public final class SystemdControl implements UpdateInstaller.ServiceControl {
    @FunctionalInterface interface Runner {String run(List<String> command)throws Exception;}
    private final String unit,content;
    private final Path dropin;
    private final Runner runner;
    private boolean created;

    public SystemdControl(String unit,String nonce) {
        this(unit,nonce,Path.of("/run/systemd/system"),SystemdControl::execute);
    }
    SystemdControl(String unit,String nonce,Path runtime,Runner runner) {
        validateUnit(unit);if(!nonce.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("更新健康标识无效");
        this.unit=unit;this.runner=runner;
        dropin=runtime.resolve(unit+".d/90-doumi-update-health.conf");
        content="# Doumi update "+nonce+"\n[Service]\nEnvironment=\"DOUMI_UPDATE_HEALTH_ID="+nonce+"\"\n";
    }
    public static void validateUnit(String unit) {
        if(unit==null||!unit.matches("[A-Za-z0-9_@][A-Za-z0-9_@.:\\-]*\\.service")||unit.contains(".."))throw new IllegalArgumentException("systemd 服务名无效");
    }
    public static String owningUnit(String cgroup) {
        for(String line:cgroup.split("\\R")) {
            String[] fields=line.split(":",3);if(fields.length!=3)continue;
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("(?:^|/)system\\.slice/([^/]+\\.service)(?:/|$)").matcher(fields[2]);
            if(match.find())try{validateUnit(match.group(1));return match.group(1);}catch(IllegalArgumentException ignored){}
        }
        return "";
    }
    public static String currentUnit(Path root,long applicationPid,long launcherPid)throws IOException {
        Path cgroup=Path.of("/proc/self/cgroup");if(!Files.isRegularFile(cgroup))return "";
        String unit=owningUnit(Files.readString(cgroup));if(unit.isEmpty())return "";
        if(!Files.isExecutable(Path.of(binary("systemctl")))||!Files.isExecutable(Path.of(binary("systemd-run"))))throw new IOException("systemd 更新需要 systemctl 和 systemd-run");
        int uid=((Number)Files.getAttribute(Path.of("/proc/self"),"unix:uid")).intValue();
        try{verifyOwnership(unit,show(unit),root,applicationPid,launcherPid,uid);}
        catch(IOException error){throw error;}catch(Exception error){throw new IOException("无法确认 systemd 服务归属",error);}
        if(Files.exists(Path.of("/run/systemd/system",unit+".d/90-doumi-update-health.conf")))throw new IOException("上次 systemd 更新临时配置尚未清理，请检查更新日志");
        return unit;
    }
    public static void verifyOwnership(String unit,Properties properties,Path root,long applicationPid,long launcherPid,int uid)throws IOException {
        validateUnit(unit);String user=properties.getProperty("User","");
        if(uid!=0||!(user.isEmpty()||user.equals("root")||user.equals("0")))throw new IOException("systemd 一键更新目前仅支持 root 运行的系统服务");
        if(!Set.of("control-group","mixed").contains(properties.getProperty("KillMode","")))throw new IOException("systemd 更新需要 KillMode=control-group 或 mixed，确保旧进程完全退出");
        long main;try{main=Long.parseLong(properties.getProperty("MainPID","0"));}catch(NumberFormatException error){throw new IOException("systemd 主进程无效",error);}
        if(main<=0||(main!=applicationPid&&main!=launcherPid))throw new IOException("systemd 主进程与当前应用不匹配");
        String directory=properties.getProperty("WorkingDirectory","");
        if(directory.isEmpty()||!Path.of(directory).toAbsolutePath().normalize().equals(root.toAbsolutePath().normalize()))throw new IOException("systemd 工作目录与安装目录不匹配");
    }
    public static List<String> launchCommand(String java,Path helper,Path metadata) {
        Path job=metadata.toAbsolutePath().normalize().getParent();String id=job.getFileName().toString();
        if(!id.matches("[a-f0-9-]{36}")||!helper.toAbsolutePath().normalize().getParent().equals(job))throw new IllegalArgumentException("更新任务目录无效");
        // systemd 219 rejects transient WorkingDirectory/UMask assignments. All helper paths
        // are absolute and its files stay beneath the server-created private job directory.
        return List.of(binary("systemd-run"),"--unit=doumi-update-"+id+".service","--description=Doumi Shell updater",
                java,"-jar",helper.toString(),metadata.toString());
    }
    private static String binary(String name) {
        for(String directory:new String[]{"/usr/bin/","/bin/"})if(Files.isExecutable(Path.of(directory+name)))return directory+name;
        return "/usr/bin/"+name;
    }
    public static Properties show(String unit)throws Exception {validateUnit(unit);return parse(execute(showCommand(unit)));}
    private static List<String> showCommand(String unit){return List.of(binary("systemctl"),"show",unit,"-p","MainPID","-p","User","-p","WorkingDirectory","-p","ActiveState","-p","SubState","-p","KillMode");}
    private static Properties parse(String output)throws IOException {Properties p=new Properties();p.load(new StringReader(output));return p;}
    public void stop()throws Exception {
        runner.run(List.of(binary("systemctl"),"stop",unit));Properties p=parse(runner.run(showCommand(unit)));
        if(!"0".equals(p.getProperty("MainPID"))||"deactivating".equals(p.getProperty("ActiveState")))throw new IOException("systemd 服务尚未完全停止，保留程序和数据");
    }
    public void start()throws Exception {
        if(!created) {
            if(Files.isSymbolicLink(dropin.getParent()))throw new IOException("systemd 临时配置目录不能为链接");
            Files.createDirectories(dropin.getParent());Files.writeString(dropin,content,StandardOpenOption.CREATE_NEW);
            created=true;Files.setPosixFilePermissions(dropin,PosixFilePermissions.fromString("rw-------"));
            runner.run(List.of(binary("systemctl"),"daemon-reload"));
        }
        runner.run(List.of(binary("systemctl"),"start",unit));
    }
    public boolean running()throws Exception {
        Properties p=parse(runner.run(showCommand(unit)));
        return !"0".equals(p.getProperty("MainPID","0"))&&"active".equals(p.getProperty("ActiveState"));
    }
    public void close()throws Exception {
        if(!created)return;
        if(!Files.readString(dropin).equals(content))throw new IOException("systemd 临时配置被修改，拒绝清理");
        Files.delete(dropin);created=false;runner.run(List.of(binary("systemctl"),"daemon-reload"));
    }
    private static String execute(List<String> command)throws Exception {
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        int timeout=command.contains("show")?5:90;
        if(!process.waitFor(timeout,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("systemd 操作超时");}
        String output=new String(process.getInputStream().readNBytes(65536),java.nio.charset.StandardCharsets.UTF_8);
        if(process.exitValue()!=0)throw new IOException("systemd 操作失败（"+command.get(1)+"）");
        return output;
    }
}
