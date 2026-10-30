package com.doumiot.sshconsole.rdp;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import org.apache.guacamole.net.GuacamoleSocket;
import org.apache.guacamole.protocol.GuacamoleInstruction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@SshConsoleEnabled
public class RdpConnectionTestService implements AutoCloseable {
    private final RdpProperties properties;
    private final RdpConnectionService connections;
    private final int timeoutMs;
    private final Map<Socket,Future<TestResult>> active = new HashMap<>();
    private final ThreadPoolExecutor workers;
    private boolean stopped;

    @Autowired
    public RdpConnectionTestService(RdpProperties properties, RdpConnectionService connections) {
        this(properties,connections,15000,4);
    }
    RdpConnectionTestService(RdpProperties properties, RdpConnectionService connections, int timeoutMs, int maxConcurrent) {
        this.properties=properties; this.connections=connections; this.timeoutMs=timeoutMs;
        workers=new ThreadPoolExecutor(maxConcurrent,maxConcurrent,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),task->{
            Thread thread=new Thread(task,"rdp-test"); thread.setDaemon(true); return thread;
        });
    }

    public TestResult test(RdpConnectionService.TestRequest request) {
        long started=System.nanoTime();
        Socket transport=new Socket();
        AtomicBoolean expired=new AtomicBoolean();
        Future<TestResult> task=null;
        try {
            synchronized (active) {
                if (stopped || !properties.isEnabled()) return result(false,"远程桌面已禁用",started);
                // No task queue: a slow DNS lookup can occupy only one of four bounded
                // workers, never block the caller beyond its deadline or grow a backlog.
                task=workers.submit(()->probe(request,transport,expired,started));
                active.put(transport,task);
            }
            return task.get(timeoutMs,TimeUnit.MILLISECONDS);
        } catch(TimeoutException error) {
            expired.set(true);
            return result(false,"连接测试超时，请检查主机、端口和网络",started);
        } catch(RejectedExecutionException error) {
            return result(false,"测试连接繁忙，请稍后重试",started);
        } catch(InterruptedException error) {
            Thread.currentThread().interrupt();
            return result(false,"连接测试已取消",started);
        } catch(CancellationException error) {
            return result(false,"连接测试已取消",started);
        } catch(ExecutionException error) {
            return result(false,"无法建立远程桌面连接，请检查主机、端口、凭据和网关",started);
        } finally {
            closeSocket(transport);
            if(task!=null) task.cancel(true);
            synchronized(active) {active.remove(transport);}
        }
    }

    private TestResult probe(RdpConnectionService.TestRequest request,Socket transport,AtomicBoolean expired,long started) {
        try {
            RdpTarget target=connections.resolveTest(request);
            GuacamoleSocket socket=RdpSocketFactory.open(transport,properties,target,1024,768,96,timeoutMs);
            boolean desktop=false;
            for (;;) {
                GuacamoleInstruction instruction=socket.getReader().readInstruction();
                if (instruction==null) return result(false,"远程桌面连接已关闭，请检查主机和端口",started);
                List<String> args=instruction.getArgs();
                switch(instruction.getOpcode()) {
                    case "error":
                        return result(false,remoteFailure(args.isEmpty()?"":args.get(0),args.size()>1?args.get(1):""),started);
                    case "required":
                        return result(false,"远程桌面认证失败，请检查用户名、密码和域",started);
                    case "disconnect":
                        return result(false,"远程桌面连接已关闭，请检查主机和端口",started);
                    case "size":
                        if (args.size()>=3 && "0".equals(args.get(0)))
                            desktop=positive(args.get(1)) && positive(args.get(2));
                        break;
                    case "sync":
                        socket.getWriter().writeInstruction(new GuacamoleInstruction("sync",args));
                        // In guacd 1.6 RDP, ready starts an asynchronous connection. Its render
                        // thread starts ONLY after freerdp_connect succeeds (rdp.c:548-565).
                        // A pre-auth pending-user snapshot has no committed default layer. A
                        // positive default-layer size in a completed frame proves rendering
                        // began after NLA/TLS connection establishment; ready/sync alone do not.
                        if (desktop && !expired.get()) return result(true,"远程桌面连接成功",started);
                        break;
                    default: break;
                }
                if (expired.get()) return result(false,"连接测试超时，请检查主机、端口和网络",started);
            }
        } catch (Exception error) {
            if (expired.get() || timedOut(error)) return result(false,"连接测试超时，请检查主机、端口和网络",started);
            return result(false,remoteFailure(error.getMessage(),""),started);
        }
    }
    private static boolean timedOut(Throwable error) {
        for (Throwable cause=error;cause!=null;cause=cause.getCause()) if(cause instanceof java.net.SocketTimeoutException) return true;
        return false;
    }
    private static boolean positive(String value) {
        try {return Integer.parseInt(value)>0;} catch(NumberFormatException ignored){return false;}
    }
    /** Use remote details only for classification; never return/log remote text or exception causes. */
    private static String remoteFailure(String details,String code) {
        String text=details==null?"":details.toLowerCase(Locale.ROOT);
        if (text.contains("cert") || text.contains("证书")) return "远程桌面证书验证失败，请检查证书或在此连接中选择忽略证书";
        if ("769".equals(code) || "771".equals(code) || text.contains("auth") || text.contains("logon") || text.contains("credential"))
            return "远程桌面认证失败，请检查用户名、密码和域";
        return "无法建立远程桌面连接，请检查主机、端口、凭据和网关";
    }
    private static TestResult result(boolean success,String message,long started) {
        return new TestResult(success,message,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
    }
    private static void closeSocket(Socket socket) {try {socket.close();} catch(Exception ignored){}}
    @Override @PreDestroy public void close() {
        synchronized(active) {
            stopped=true;
            for(Map.Entry<Socket,Future<TestResult>> entry:active.entrySet()) {
                closeSocket(entry.getKey()); entry.getValue().cancel(true);
            }
        }
        workers.shutdownNow();
    }
    @lombok.Value
    public static class TestResult { boolean success; String message; long elapsedMs; }
}
