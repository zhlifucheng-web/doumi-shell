package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.entity.SshHost;
import com.doumiot.sshconsole.sftp.*;
import com.doumiot.sshconsole.ssh.*;
import lombok.Data;
import net.schmizz.sshj.sftp.*;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Service
@SshConsoleEnabled
public class SshCopyService {
    @Resource private SshSessionRegistry sessions;
    @Resource private SshAccountService accounts;
    @Resource private SshHostService hosts;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),r->{Thread thread=new Thread(r,"ssh-remote-copy");thread.setDaemon(true);return thread;});
    private Executor executor=workers;
    private final Map<String,Job> jobs=new LinkedHashMap<>();

    @Data public static class Request {
        private String sessionToken,sourcePath,targetPath,owner;
        private Long targetAccountId;
        private String mode="RELAY";
        private boolean overwrite;
    }
    @Data public static class State {
        private String id,status,currentPath,error,warning;
        private long loaded,total;
    }
    private static final class Job {
        final String id=UUID.randomUUID().toString();
        final Request request;
        final long sourceAccountId;
        String status="QUEUED",currentPath="",error,warning;
        long loaded,total,finishedAt,lastSeen=System.currentTimeMillis();
        volatile boolean cancelled;
        Job(Request request,long accountId){this.request=request;sourceAccountId=accountId;}
        synchronized State state(){State state=new State();state.setId(id);state.setStatus(status);state.setLoaded(loaded);
            state.setTotal(total);state.setCurrentPath(currentPath);state.setError(error);state.setWarning(warning);return state;}
    }
    public synchronized State start(Request input) throws IOException {
        Request request=validate(input);
        SshSessionRegistry.SessionEntry source=sessions.get(request.sessionToken);
        long now=System.currentTimeMillis();
        jobs.values().removeIf(job->job.finishedAt>0 && now-job.finishedAt>TimeUnit.HOURS.toMillis(1));
        long active=jobs.values().stream().filter(job->job.finishedAt==0).count();
        if(active>=32)throw new IOException("复制任务过多，请等待已有任务完成");
        while(jobs.size()>=256) {
            String oldest=jobs.values().stream().filter(job->job.finishedAt>0).map(job->job.id).findFirst().orElse(null);
            if(oldest==null)break;jobs.remove(oldest);
        }
        Job job=new Job(request,source.getAccountId());jobs.put(job.id,job);
        try {executor.execute(()->run(job));}
        catch(RuntimeException error){jobs.remove(job.id);throw new IOException("复制队列已满，请稍后重试",error);}
        return job.state();
    }
    public State status(String token,String id) throws IOException {
        Job job=requireJob(token,id);
        synchronized(job){job.lastSeen=System.currentTimeMillis();return job.state();}
    }
    public State cancel(String token,String id) throws IOException {
        Job job=requireJob(token,id);
        synchronized(job){if(job.finishedAt==0){job.cancelled=true;job.status="CANCELLING";}return job.state();}
    }
    private synchronized Job requireJob(String token,String id) throws IOException {
        sessions.get(token);
        Job job=jobs.get(id);
        if(job==null || !Objects.equals(token,job.request.sessionToken))throw new IOException("复制任务不存在或不属于当前会话");
        return job;
    }
    private void run(Job job) {
        String sourceToken=null,targetToken=null;
        try {
            check(job);
            synchronized(job){job.status="RUNNING";}
            sourceToken=accounts.openSession(job.sourceAccountId).getSessionToken();
            check(job);
            targetToken=accounts.openSession(job.request.targetAccountId).getSessionToken();
            SshSessionRegistry.SessionEntry source=sessions.get(sourceToken),target=sessions.get(targetToken);
            String pinnedSource=sourceToken,pinnedTarget=targetToken;
            Runnable heartbeat=()->{check(job);sessions.touchSession(pinnedSource);sessions.touchSession(pinnedTarget);};
            String warning;
            try(SFTPClient from=source.getClient().newSFTPClient();SFTPClient to=target.getClient().newSFTPClient()) {
                // A trailing slash prevents the resolver's input trim from changing a literal filename ending in spaces.
                String sourcePath=SshSftpPathResolver.resolve(job.request.sourcePath+"/",source.getDefaultCwd(),source.getHomeDir());
                String targetPath=SshSftpPathResolver.resolve(job.request.targetPath+"/",target.getDefaultCwd(),target.getHomeDir());
                SshAccountService.SshAccountVO sourceAccount=accounts.getById(job.sourceAccountId);
                SshAccountService.SshAccountVO targetAccount=accounts.getById(job.request.targetAccountId);
                SshHost sourceHost=hosts.getById(sourceAccount.getHostId()),targetHost=hosts.getById(targetAccount.getHostId());
                boolean sameMachine=Objects.equals(sourceAccount.getHostId(),targetAccount.getHostId())
                        || sourceHost.getHost().equalsIgnoreCase(targetHost.getHost()) && port(sourceHost)==port(targetHost)
                        || source.getClient().getRemoteSocketAddress()!=null
                        && source.getClient().getRemoteSocketAddress().equals(target.getClient().getRemoteSocketAddress());
                SftpStreams.Ownership owner=resolveOwner(target,job.request.owner);
                heartbeat.run();
                SshCopyEngine.Plan plan=SshCopyEngine.plan(from,to,sourcePath,targetPath,job.request.overwrite,sameMachine);
                synchronized(job){job.total=plan.getTotal();}
                boolean direct="DIRECT".equals(job.request.mode);
                if(direct)SshDirectCopy.check(source,targetHost.getHost(),port(targetHost),targetAccount.getUsername(),heartbeat);
                SshCopyEngine.ContentCopier copier=(path,temp,size,progress)->{
                    heartbeat.run();
                    if(!direct){SshCopyEngine.relay(from,to,path,temp,size,progress);return;}
                    FileAttributes attrs=from.lstat(path);
                    if(attrs.getType()!=FileMode.Type.REGULAR || attrs.getSize()!=size)throw new IOException("源文件在复制期间发生变化");
                    SshDirectCopy.copy(source,targetHost.getHost(),port(targetHost),targetAccount.getUsername(),path,temp,()->{
                        heartbeat.run();
                        try {long count=to.size(temp);if(count>size)throw new IOException("源文件在直连期间增长");progress.accept(count);}
                        catch(IOException error){throw new CopyFailure(error);}
                    });
                };
                warning=SshCopyEngine.execute(plan,to,owner,copier,(loaded,total,path)->{
                    heartbeat.run();synchronized(job){job.loaded=loaded;job.total=total;job.currentPath=path;}
                });
            }
            synchronized(job){job.warning=warning;job.status="COMPLETED";}
        } catch(CancellationException error) {
            synchronized(job){job.status="CANCELLED";job.warning="复制已停止，已完成的文件和新建目录会保留。";}
        } catch(Exception error) {
            synchronized(job){job.status="FAILED";job.error=error.getMessage()==null?"复制失败":error.getMessage();
                job.warning="已完成的文件和新建目录会保留，请检查目标后再重试。";}
        } finally {
            if(targetToken!=null)sessions.close(targetToken);
            if(sourceToken!=null)sessions.close(sourceToken);
            synchronized(job){job.finishedAt=System.currentTimeMillis();}
        }
    }
    private static int port(SshHost host){return host.getPort()==null?22:host.getPort();}
    private static final class CopyFailure extends RuntimeException {CopyFailure(IOException error){super(error.getMessage(),error);}}
    private static void check(Job job) {
        synchronized(job){
            if(job.cancelled || Thread.currentThread().isInterrupted()
                    || System.currentTimeMillis()-job.lastSeen>TimeUnit.SECONDS.toMillis(60))
                throw new CancellationException("复制已取消或页面失去联系");
        }
    }
    private static SftpStreams.Ownership resolveOwner(SshSessionRegistry.SessionEntry target,String owner) throws IOException {
        if(owner==null || owner.isEmpty())return SftpStreams.Ownership.of(null,null);
        String quoted=SshDirectCopy.quote(owner);
        SshRemoteExec.ExecResult result=SshRemoteExec.exec(target.getClient(),target.getExecLock(),"id -u "+quoted+" && id -g "+quoted,15);
        String[] ids=result.getStdout().trim().split("\\s+");
        if(result.getExitStatus()!=0 || ids.length!=2)throw new IOException("目标机器无法解析指定用户，请检查用户名和查询权限");
        try{return SftpStreams.Ownership.of(Long.parseLong(ids[0]),Long.parseLong(ids[1]));}
        catch(NumberFormatException error){throw new IOException("目标用户 UID/GID 无效",error);}
    }
    private static Request validate(Request input) throws IOException {
        if(input==null || input.sessionToken==null || input.sessionToken.trim().isEmpty()
                || input.targetAccountId==null || input.targetAccountId<=0)throw new IOException("请选择源会话和目标机器账户");
        if(!"DIRECT".equals(input.mode) && !"RELAY".equals(input.mode))throw new IOException("无效的复制方式");
        for(String path:Arrays.asList(input.sourcePath,input.targetPath)) {
            if(path==null || !path.startsWith("/") || path.indexOf('\0')>=0 || path.length()>4096)
                throw new IOException("请填写有效的绝对路径");
            SshSftpPathResolver.resolve(path,null,null);
        }
        String owner=input.owner==null?"":input.owner.trim();
        if(!owner.isEmpty() && !owner.matches("[a-zA-Z0-9_.][a-zA-Z0-9_.$-]*"))throw new IOException("无效的接收方用户名");
        Request copy=new Request();copy.sessionToken=input.sessionToken;copy.sourcePath=input.sourcePath;copy.targetPath=input.targetPath;
        copy.targetAccountId=input.targetAccountId;copy.mode=input.mode;copy.overwrite=input.overwrite;copy.owner=owner;return copy;
    }
    @PreDestroy public void shutdown(){synchronized(this){for(Job job:jobs.values())job.cancelled=true;}workers.shutdownNow();}
}
