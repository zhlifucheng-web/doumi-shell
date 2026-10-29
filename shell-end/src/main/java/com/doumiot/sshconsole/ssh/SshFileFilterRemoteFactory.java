package com.doumiot.sshconsole.ssh;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.sftp.SshFileFilterScanner;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Signal;
import org.springframework.stereotype.Component;
import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Native historical grep on an isolated exec group; capability failures explicitly use Java/SFTP. */
@Component
@SshConsoleEnabled
public class SshFileFilterRemoteFactory {
    private final SshSessionRegistry registry;
    private final Set<SshFileFilterRemoteCursor> cursors=Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Control> liveControls=Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Semaphore nativeSlots=new Semaphore(32);
    private final AtomicBoolean shuttingDown=new AtomicBoolean();
    private final ExecutorService controls=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),task->{Thread t=new Thread(task,"native-filter-control");t.setDaemon(true);return t;});
    private final ThreadPoolExecutor cleanupWorkers=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),task->{Thread t=new Thread(task,"native-filter-cleanup");t.setDaemon(true);return t;});
    private final String command,controlCommand;
    public SshFileFilterRemoteFactory(SshSessionRegistry registry) throws IOException {
        this.registry=registry;
        command="if command -v bash >/dev/null 2>&1 && command -v setsid >/dev/null 2>&1; then exec bash -c "+quote(resource("file-filter-native.sh"))+"; else printf 'UNSUPPORTED\\tmissing-bash-setsid\\n'; fi";
        controlCommand="exec bash -c "+quote(resource("file-filter-control.sh"));
    }
    public static String eligibility(String keyword,String encoding,boolean caseSensitive) {
        if(!caseSensitive)return "不区分大小写时使用 SFTP 扫描";
        try{if(!StandardCharsets.UTF_8.equals(Charset.forName(encoding==null||encoding.isEmpty()?"UTF-8":encoding)))return "所选编码使用 SFTP 扫描";}
        catch(IllegalArgumentException unsupported){return "所选编码使用 SFTP 扫描";}
        if(keyword.indexOf('\n')>=0 || keyword.indexOf('\r')>=0 || keyword.indexOf('\0')>=0 || keyword.getBytes(StandardCharsets.UTF_8).length>65536)return "关键字使用 SFTP 兼容扫描";
        return null;
    }
    public Attempt openNative(String token,String path,String keyword,String encoding,boolean caseSensitive,int context,
            NativeFilterSnapshot snapshot,SshFileFilterScanner scanner) throws IOException {
        String reason=eligibility(keyword,encoding,caseSensitive);if(reason!=null)return Attempt.fallback(reason);
        if(snapshot==null)return Attempt.fallback("文件末行未结束或末尾上下文超过 64 KiB，使用 SFTP 扫描");
        if(shuttingDown.get()||!nativeSlots.tryAcquire())return Attempt.fallback("原生进程正在运行或清理，使用 SFTP 兼容扫描");
        Session session=null;Session.Command process=null;Control control=null;boolean releaseReservation=true;
        try {
            SSHClient client=registry.get(token).getClient();
            session=client.startSession();process=session.exec(command);
            String nonce=UUID.randomUUID().toString().replace("-","");
            OutputStream stdin=process.getOutputStream();
            String records=b64(path.getBytes(StandardCharsets.UTF_8))+"\n"+b64(keyword.getBytes(StandardCharsets.UTF_8))+"\n"+snapshot.size+"\n"+context+"\n"+b64(snapshot.prefix)+"\n"+b64(snapshot.boundary)+"\n"+b64(snapshot.seed)+"\n"+nonce+"\n";
            stdin.write(records.getBytes(StandardCharsets.US_ASCII));stdin.flush();
            String ready=readLine(process.getInputStream(),process,10_000);
            if(ready.startsWith("UNSUPPORTED\t"))return Attempt.fallback("远端能力不足（"+ready.substring(12)+"），使用 SFTP 扫描");
            String[] fields=ready.split("\\t");
            if(fields.length!=4 || !"READY".equals(fields[0]) || !fields[1].matches("[0-9]+") || !fields[2].matches("[0-9]+") || !fields[3].matches("[0-9]+"))return Attempt.fallback("远端原生筛选启动不受支持，使用 SFTP 扫描");
            control=new Control(client,session,process,fields[1],fields[2],nonce,fields[3]);
            liveControls.add(control);releaseReservation=false;
            if(shuttingDown.get())return Attempt.fallback("原生扫描正在关闭，使用 SFTP 扫描");
            if(!control.send("PROBE"))return Attempt.fallback("远端进程组暂停控制不可用，使用 SFTP 扫描");
            final Control owner=control;final Session.Command nativeProcess=process;
            SshFileFilterRemoteCursor cursor=new SshFileFilterRemoteCursor(process.getInputStream(),()->!nativeProcess.isOpen() || nativeProcess.isEOF(),()->owner.request(2),snapshot,scanner,keyword,paused->owner.request(paused?1:0));
            cursor.guard=()->{
                final boolean valid;
                try{valid=owner.send("CHECK");}
                catch(IOException failure){
                    com.doumiot.sshconsole.sftp.FileTailRecoveryException recovery=new com.doumiot.sshconsole.sftp.FileTailRecoveryException(
                            owner.client.isConnected()?"TAIL_EXPIRED":"SSH_DISCONNECTED","原生筛选文件校验通道已中断，请重新筛选");
                    recovery.initCause(failure);throw recovery;
                }
                if(!valid)throw new com.doumiot.sshconsole.sftp.FileTailRecoveryException("FILE_CHANGED","原生筛选文件身份已改变，请重新筛选");
            };
            cursor.cleanupGuard=()->{try{if(!owner.done.await(12,TimeUnit.SECONDS))throw new IOException("原生进程组清理超时，请重新筛选");if(owner.cleanupFailure!=null)throw owner.cleanupFailure;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("原生进程清理中断",interrupted);}};
            control.cursor=cursor;cursors.add(cursor);
            stdin.write("go\n".getBytes(StandardCharsets.US_ASCII));stdin.flush();
            control=null;session=null;process=null;
            return new Attempt(cursor,null);
        } catch(IOException failure) {
            return Attempt.fallback("远端 exec 或能力探测不可用，使用 SFTP 扫描");
        } finally {
            if(control!=null)control.finish();
            else if(session!=null){if(process!=null){try{process.signal(Signal.TERM);}catch(IOException ignored){}try{process.close();}catch(IOException ignored){}}try{session.close();}catch(IOException ignored){}}
            if(releaseReservation)nativeSlots.release();
        }
    }
    public static final class Attempt {
        public final SshFileFilterRemoteCursor cursor;public final String reason;
        public Attempt(SshFileFilterRemoteCursor cursor,String reason){this.cursor=cursor;this.reason=reason;}
        public static Attempt fallback(String reason){return new Attempt(null,reason);}
    }
    private final class Control {
        final SSHClient client;final Session session;final Session.Command process;final String pid,ticks,nonce,child;
        final AtomicInteger desired=new AtomicInteger(0);final AtomicBoolean scheduled=new AtomicBoolean(),closed=new AtomicBoolean();
        final AtomicBoolean emergencyScheduled=new AtomicBoolean();
        volatile SshFileFilterRemoteCursor cursor;int applied;
        volatile IOException cleanupFailure;
        final CountDownLatch done=new CountDownLatch(1);
        Control(SSHClient client,Session session,Session.Command process,String pid,String ticks,String nonce,String child){this.client=client;this.session=session;this.process=process;this.pid=pid;this.ticks=ticks;this.nonce=nonce;this.child=child;}
        boolean send(String action) throws IOException {
            try(Session channel=client.startSession()){
                Session.Command command=channel.exec(controlCommand);
                command.getOutputStream().write((pid+"\n"+ticks+"\n"+nonce+"\n"+action+"\n"+child+"\n").getBytes(StandardCharsets.US_ASCII));command.getOutputStream().flush();
                String answer=null;
                try{answer=readLine(command.getInputStream(),command,5000);}
                catch(ReplyEndedException ended){
                    // CHECK deliberately exits nonzero without a reply when identity changed.
                    command.join(5,TimeUnit.SECONDS);
                    Integer status=command.getExitStatus();if(status!=null&&status!=0)return false;
                    throw ended;
                }
                command.join(5,TimeUnit.SECONDS);Integer status=command.getExitStatus();
                if(status!=null&&status!=0)return false;
                if(Integer.valueOf(0).equals(status)&&"OK".equals(answer))return true;
                throw new IOException("远端原生进程控制响应或退出状态无效");
            }
        }
        void request(int state) {
            desired.updateAndGet(old->old==2?2:state);
            if(closed.get()||emergencyScheduled.get()||!scheduled.compareAndSet(false,true))return;
            try{controls.execute(this::drain);}catch(RejectedExecutionException busy){
                scheduled.set(false);emergencyClose();
                if(cursor!=null)cursor.controlFailed(new IOException("原生进程控制繁忙，请重新筛选"));
            }
        }
        /** Bypass scheduled: shutdownNow may have discarded the queued drain that owned it. */
        void emergencyClose(){
            desired.set(2);if(closed.get())return;
            if(emergencyScheduled.compareAndSet(false,true)){
                try{cleanupWorkers.execute(this::finish);}catch(RejectedExecutionException stopping){finish();}
            }
        }
        void drain(){
            try{while(!closed.get()){int target=desired.get();if(target==2){finish();return;}if(target==applied)return;
                if(!send(target==1?"STOP":"CONT"))throw new IOException("原生进程组控制失败，请重新筛选");applied=target;}}
            catch(IOException failure){if(cursor!=null)cursor.controlFailed(failure);finish();}
            finally{scheduled.set(false);if(!closed.get()&&desired.get()!=applied)request(desired.get());}
        }
        void finish(){if(closed.compareAndSet(false,true)){
            try{if(!send("CLOSE")&&process.isOpen()&&!process.isEOF())cleanupFailure=new IOException("无法确认原生进程组已清理，请重新连接后重试");}
            catch(IOException failure){if(process.isOpen()&&!process.isEOF())cleanupFailure=new IOException("原生进程组清理失败，请重新连接后重试",failure);}
            try{process.close();}catch(IOException ignored){}try{session.close();}catch(IOException ignored){}finally{
                liveControls.remove(this);if(cursor!=null)cursors.remove(cursor);nativeSlots.release();done.countDown();
            }
        }}
    }
    private static String readLine(InputStream input,Session.Command process,long timeout) throws IOException {
        long end=System.nanoTime()+timeout*1_000_000L;ByteArrayOutputStream out=new ByteArrayOutputStream();
        while(System.nanoTime()<end){
            if(input.available()>0){int c=input.read();if(c=='\n')return new String(out.toByteArray(),StandardCharsets.US_ASCII);if(c<0)throw new ReplyEndedException();if(out.size()>=4096)throw new IOException("远端能力响应过大");out.write(c);}
            else{if(!process.isOpen()||process.isEOF())throw new ReplyEndedException();try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("原生筛选启动中断",interrupted);}}
        }throw new IOException("远端能力探测超时");
    }
    private static final class ReplyEndedException extends IOException {ReplyEndedException(){super("远端进程已结束但未返回完整响应");}}
    private static String b64(byte[] bytes){return Base64.getEncoder().encodeToString(bytes);}
    private static String quote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
    private static String resource(String name) throws IOException {
        try(InputStream input=SshFileFilterRemoteFactory.class.getResourceAsStream("/ssh/"+name)){
            if(input==null)throw new IOException("缺少原生筛选程序");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    @PreDestroy public void closeAll(){
        boolean interrupted=Thread.interrupted();
        try {
        shuttingDown.set(true);for(Control control:liveControls)control.request(2);
        controls.shutdown();try{controls.awaitTermination(50,TimeUnit.SECONDS);}catch(InterruptedException stopped){interrupted=true;}
        controls.shutdownNow();for(Control control:liveControls)control.emergencyClose();
        cleanupWorkers.shutdown();try{cleanupWorkers.awaitTermination(10,TimeUnit.SECONDS);}catch(InterruptedException stopped){interrupted=true;}
        // Never discard queued CLOSEs or interrupt the final cleanup already in progress.
        // At most 32 reserved controllers remain; each finish has bounded channel waits.
        List<Runnable> remaining=new ArrayList<>();cleanupWorkers.getQueue().drainTo(remaining);
        for(Runnable cleanup:remaining){if(Thread.interrupted())interrupted=true;cleanup.run();}
        try{cleanupWorkers.awaitTermination(12,TimeUnit.SECONDS);}catch(InterruptedException stopped){interrupted=true;}
        } finally {if(interrupted)Thread.currentThread().interrupt();}
    }
}
