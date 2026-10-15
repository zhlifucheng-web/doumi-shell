package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.*;
import com.doumiot.sshconsole.entity.*;
import com.doumiot.sshconsole.mapper.*;
import com.doumiot.sshconsole.ssh.*;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import javax.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 独立 SSH 连接执行，历史不随配置删除；维护锁协调启动、结束和清理。 */
@Service
@SshConsoleEnabled
public class SshScriptExecutionService {
    @Resource private JdbcTemplate jdbc;
    @Resource private SshScriptMapper scripts;
    @Resource private SshHostMapper hosts;
    @Resource private SshAccountService accounts;
    @Resource private SshClientFactory clients;
    @Resource private SshConsoleProperties properties;
    public final Object maintenanceLock = new Object();
    private final ConcurrentMap<String, Control> active = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> { Thread t=new Thread(r,"script-execution"); t.setDaemon(true); return t; });
    private volatile boolean closing;
    private static final String ACTIVE = "('QUEUED','RUNNING','CANCELLING')";

    @Order(2) @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        jdbc.update("UPDATE ssh_script_execution SET status='INTERRUPTED',finished_at=?,error=? WHERE status IN "+ACTIVE,
                Instant.now().toString(),"服务重启，执行状态无法继续追踪，请检查远端进程");
    }

    public Map<String,Object> start(long scriptId, long accountId, String source) {
        synchronized (maintenanceLock) {
            if (closing) throw new IllegalArgumentException("服务正在关闭");
            SshScript script=scripts.selectById(scriptId);
            if(script==null) throw new IllegalArgumentException("脚本不存在");
            SshAccount account=accounts.requireAccount(accountId);
            SshHost host=hosts.selectById(account.getHostId());
            if(host==null || Integer.valueOf(0).equals(host.getEnabled()) || Integer.valueOf(0).equals(account.getEnabled()))
                throw new IllegalArgumentException("主机或账户不可用");
            if(script.getHostId()!=null && !script.getHostId().equals(host.getId()))
                throw new IllegalArgumentException("脚本与执行账户不在同一主机");
            SshScriptService.buildRunShellCommand(script.getInterpreter(),script.getWorkDir());
            if("CRON".equals(source) && jdbc.queryForObject("SELECT COUNT(*) FROM ssh_script_execution WHERE script_id=? AND status IN "+ACTIVE,Integer.class,scriptId)>0)
                return null;
            String id=UUID.randomUUID().toString();
            Control c=new Control();
            jdbc.update("INSERT INTO ssh_script_execution(id,script_id,script_name,account_id,host_id,host_name,username,source,status,started_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id,scriptId,script.getName(),accountId,host.getId(),host.getName(),account.getUsername(),source,"QUEUED",Instant.now().toString());
            active.put(id,c);
            c.task=() -> execute(id,c,script,account,host,source);
            try { workers.execute(c.task); }
            catch(RejectedExecutionException e) {
                active.remove(id);
                finish(id,"ERROR",null,"执行队列已满",c);
            }
            return detail(id);
        }
    }

    public Map<String,Object> list(Long scriptId,String status,String source,int page,int size) {
        size=Math.max(1,Math.min(size,100)); page=Math.max(1,page);
        String where=" WHERE 1=1"; List<Object> args=new ArrayList<>();
        if(scriptId!=null) {where+=" AND script_id=?";args.add(scriptId);}
        if(status!=null && !status.isEmpty()) {where+=" AND status=?";args.add(status);}
        if(source!=null && !source.isEmpty()) {where+=" AND source=?";args.add(source);}
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("total",jdbc.queryForObject("SELECT COUNT(*) FROM ssh_script_execution"+where,Long.class,args.toArray()));
        args.add(size); args.add(((long)page-1)*size);
        result.put("rows",jdbc.queryForList("SELECT id,script_id,script_name,host_name,username,source,status,started_at,finished_at,duration_ms,exit_status FROM ssh_script_execution"+where+" ORDER BY started_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray()));
        return result;
    }

    public Map<String,Object> detail(String id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM ssh_script_execution WHERE id=?",id);
        if(rows.isEmpty()) throw new IllegalArgumentException("执行记录不存在");
        return rows.get(0);
    }

    public Map<String,Object> stop(String id) {
        synchronized(maintenanceLock) {
            detail(id);
            Control c=active.get(id);
            if(c!=null) {
                c.cancel=true;
                if(!c.started) {
                    c.completed=true;workers.remove(c.task);
                    finish(id,"CANCELLED",null,null,c);active.remove(id);
                } else jdbc.update("UPDATE ssh_script_execution SET status='CANCELLING' WHERE id=? AND status IN "+ACTIVE,id);
            }
            return detail(id);
        }
    }

    private void execute(String id,Control c,SshScript script,SshAccount account,SshHost host,String source) {
        synchronized(maintenanceLock) {if(c.completed)return;c.started=true;}
        SSHClient client=null; String status="ERROR",error=null; Integer code=null;
        Thread out=null,err=null; boolean launched=false;
        try {
            if(c.cancel) {status="CANCELLED";return;}
            jdbc.update("UPDATE ssh_script_execution SET status='RUNNING' WHERE id=? AND status='QUEUED'",id);
            if("CRON".equals(source)) jdbc.update("UPDATE ssh_script_schedule SET last_run_at=?,last_status='RUNNING',last_output=NULL WHERE script_id=?",Instant.now().toString(),script.getId());
            client=clients.connectAndAuth(host.getHost(),host.getPort()==null?22:host.getPort(),account.getUsername(),account.getAuthType(),accounts.loadSecret(account));
            if(c.cancel) {status="CANCELLED";return;}
            try(Session session=client.startSession()) {
                Session.Command command=session.exec(remoteCommand(id,script));
                launched=true;
                out=pump(command.getInputStream(),c.stdout,c); err=pump(command.getErrorStream(),c.stderr,c);
                // 独立写线程避免大脚本写入阻塞取消处理。
                Thread writer=new Thread(() -> {try(OutputStream stream=command.getOutputStream()) {stream.write(script.getContent().getBytes(StandardCharsets.UTF_8));}catch(IOException ignored) {}},"script-stdin");
                writer.setDaemon(true);writer.start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(properties.getScriptRunTimeoutSec()>0?properties.getScriptRunTimeoutSec():3600);
                while(true) {
                    // SSHJ 的 join(timeout) 会在等待超时后抛异常，轮询通道状态以保留长任务。
                    Thread.sleep(200);
                    persistOutput(id,c);
                    if(!command.isOpen()) {code=command.getExitStatus();status=code!=null&&code==0?"SUCCESS":"FAILED";break;}
                    if(c.cancel || System.nanoTime()>deadline) {
                        try { terminate(client,id); }
                        catch(IOException e) { if(command.isOpen()) throw e; }
                        command.join(3,TimeUnit.SECONDS);
                        status=c.cancel?"CANCELLED":"TIMED_OUT";
                        code=command.getExitStatus(); break;
                    }
                }
                if(out!=null)out.join(1500);if(err!=null)err.join(1500);
            }
        } catch(Exception e) {error=e.getMessage();status=c.cancel?(launched?"CANCEL_FAILED":"CANCELLED"):"ERROR";}
        finally {
            clients.disconnectQuietly(client);
            synchronized(maintenanceLock) {
                finish(id,status,code,error,c);
                if("CRON".equals(source)) {
                    String output=text(c.stdout)+"\n"+text(c.stderr)+(error==null?"":"\n"+error);
                    jdbc.update("UPDATE ssh_script_schedule SET last_status=?,last_output=? WHERE script_id=?",status,output.substring(0,Math.min(16384,output.length())),script.getId());
                }
                active.remove(id);
            }
        }
    }

    static String quote(String value) {return "'"+value.replace("'","'\\''")+"'";}
    static String remoteCommand(String id,SshScript script) {
        String marker="/tmp/doumi-"+id+".pid";
        String body="umask 077; echo $$ > "+marker+"; trap 'rm -f "+marker+"' EXIT; "+SshScriptService.buildRunShellCommand(script.getInterpreter(),script.getWorkDir())+"; result=$?; exit $result";
        return "command -v setsid >/dev/null 2>&1 || { echo '执行需要远端 setsid 命令' >&2; exit 127; }; exec setsid /bin/sh -c "+quote(body);
    }

    private void terminate(SSHClient client,String id) throws IOException {
        String marker="/tmp/doumi-"+id+".pid";
        // 标记包含随机执行 ID；核验进程命令行后才向整个进程组发送信号。
        String script="i=0; while [ ! -s "+marker+" ] && [ $i -lt 20 ]; do sleep 0.1; i=$((i+1)); done; p=$(cat "+marker+" 2>/dev/null) || exit 4; case \"$p\" in ''|*[!0-9]*) exit 2;; esac; "
                +"if [ -r /proc/$p/cmdline ] && tr '\\000' ' ' < /proc/$p/cmdline | grep -F "+quote(id)+" >/dev/null; then "
                +"kill -TERM -- -$p 2>/dev/null || /bin/kill -TERM -- -$p; sleep 1; /bin/kill -KILL -- -$p 2>/dev/null || true; "
                +"elif kill -0 $p 2>/dev/null; then exit 3; fi; rm -f "+marker;
        SshRemoteExec.ExecResult result=SshRemoteExec.exec(client,new Object(),"/bin/sh -c "+quote(script),8);
        if(result.getExitStatus()!=0) throw new IOException("远端终止未确认，请检查远端进程："+result.getStderr());
    }

    private Thread pump(InputStream input,ByteArrayOutputStream buffer,Control c) {
        Thread thread=new Thread(() -> {try {byte[] bytes=new byte[4096];int n;
            while((n=input.read(bytes))!=-1) synchronized(buffer) {
                int take=Math.min(n,512*1024-buffer.size()); if(take>0)buffer.write(bytes,0,take);if(take<n)c.truncated=true;
            }
        }catch(IOException ignored) {}},"script-output");thread.setDaemon(true);thread.start();return thread;
    }
    private static String text(ByteArrayOutputStream buffer) {synchronized(buffer) {return new String(buffer.toByteArray(),StandardCharsets.UTF_8);}}
    private void persistOutput(String id,Control c) {jdbc.update("UPDATE ssh_script_execution SET stdout=?,stderr=?,truncated=? WHERE id=?",text(c.stdout),text(c.stderr),c.truncated?1:0,id);}
    private void finish(String id,String status,Integer code,String error,Control c) {
        persistOutput(id,c);
        jdbc.update("UPDATE ssh_script_execution SET status=?,exit_status=?,error=?,finished_at=?,duration_ms=? WHERE id=?",status,code,error,Instant.now().toString(),System.currentTimeMillis()-c.created,id);
    }
    @PreDestroy public void shutdown() {
        closing=true;active.values().forEach(c -> c.cancel=true);workers.shutdown();
        try {workers.awaitTermination(25,TimeUnit.SECONDS);}catch(InterruptedException e) {Thread.currentThread().interrupt();}
    }
    private static class Control {
        volatile boolean cancel,truncated; boolean started,completed; Runnable task; final long created=System.currentTimeMillis();
        final ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();
    }
}
