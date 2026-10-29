package com.doumiot.sshconsole.ssh;

import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Signal;
import net.schmizz.sshj.connection.ConnectionException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Data stays between the two servers. Uses only the source server's existing SSH identity. */
public final class SshDirectCopy {
    private SshDirectCopy() {}
    public static String command(String host,int port,String user,String source,String temporary) throws IOException {
        return ssh(host,port,user,"cat > "+quote(temporary))+" < "+quote(source);
    }
    private static String ssh(String host,int port,String user,String remote) throws IOException {
        if(host==null || !host.matches("[a-zA-Z0-9:][a-zA-Z0-9.:%_-]*") || port<1 || port>65535
                || user==null || !user.matches("[a-zA-Z0-9_.][a-zA-Z0-9_.@$-]*"))
            throw new IOException("直连目标主机、端口或用户名无效");
        return "exec ssh -T -oBatchMode=yes -oStrictHostKeyChecking=yes -oConnectTimeout=15"
                +" -oServerAliveInterval=15 -oServerAliveCountMax=3 -p "+port+" -l "+quote(user)+" "+quote(host)+" "+quote(remote);
    }
    public static void check(SshSessionRegistry.SessionEntry source,String host,int port,String user,Runnable tick) throws IOException {
        run(source,ssh(host,port,user,"test -x /bin/sh && command -v cat >/dev/null"),tick,30);
    }
    public static void copy(SshSessionRegistry.SessionEntry source,String host,int port,String user,String from,
            String temporary,Runnable tick) throws IOException {
        run(source,command(host,port,user,from,temporary),tick,24*60*60);
    }
    private static void run(SshSessionRegistry.SessionEntry source,String command,Runnable tick,int timeout) throws IOException {
        try(Session session=source.getClient().startSession()) {
            Session.Command cmd=session.exec(command);
            ByteArrayOutputStream error=new ByteArrayOutputStream();
            Thread stderr=drain(cmd.getErrorStream(),error),stdout=drain(cmd.getInputStream(),new ByteArrayOutputStream());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout);
            try {
                do {
                    tick.run();
                    try {cmd.join(250,TimeUnit.MILLISECONDS);}
                    catch(ConnectionException errorWaiting) {
                        // SSHJ throws when this polling interval expires; the command may still be healthy.
                        if(!(errorWaiting.getCause() instanceof TimeoutException))throw errorWaiting;
                    }
                    if(System.nanoTime()>deadline) throw new IOException("直连复制超时");
                } while(cmd.getExitStatus()==null && cmd.isOpen());
                try {stderr.join(1000);stdout.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("直连复制已中断",e);}
                if(cmd.getExitStatus()==null || cmd.getExitStatus()!=0) {
                    String details;
                    synchronized(error){details=new String(error.toByteArray(),StandardCharsets.UTF_8).trim();}
                    throw new IOException("直连失败，请检查源机器的 SSH 工具、免交互认证及 known_hosts，或选择本机转发。"+details);
                }
            } finally {
                if(cmd.isOpen()) try {cmd.signal(Signal.TERM);}catch(IOException ignored) { /* Channel closure also disconnects the nested SSH stdin. */ }
            }
        }
    }
    private static Thread drain(InputStream input,ByteArrayOutputStream output) {
        Thread thread=new Thread(()->{
            try {byte[] bytes=new byte[4096];int n;while((n=input.read(bytes))!=-1)
                synchronized(output){if(output.size()<8192)output.write(bytes,0,Math.min(n,8192-output.size()));}}
            catch(IOException ignored) { /* Command status remains authoritative. */ }
        },"ssh-copy-output");
        thread.setDaemon(true);thread.start();return thread;
    }
    public static String quote(String value) {
        if(value==null || value.indexOf('\0')>=0)throw new IllegalArgumentException("无效的 SSH 参数");
        return "'"+value.replace("'","'\"'\"'")+"'";
    }
}
