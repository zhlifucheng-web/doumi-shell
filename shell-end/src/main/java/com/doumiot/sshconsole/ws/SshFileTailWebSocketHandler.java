package com.doumiot.sshconsole.ws;

import com.doumiot.sshconsole.sftp.FileTailRecoveryException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshFileFilterService;
import com.doumiot.sshconsole.service.SshSftpService;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.schmizz.sshj.sftp.FileAttributes;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Independent file stream: never binds the terminal socket or owns the SSH connection. */
@Component
@SshConsoleEnabled
public class SshFileTailWebSocketHandler extends TextWebSocketHandler {
    private static final int MAX_TAILS=32, BUDGET=256*1024, ANCHOR=4096, SEED_LIMIT=65536;
    private static final long PAUSE_CLOSE_MS=30000, RESUME_MS=60000;
    private final SshSftpService files;
    private final SshFileFilterService filters;
    private final SshSessionRegistry registry;
    private final ObjectMapper json=new ObjectMapper();
    private final Map<String,Tail> tails=new ConcurrentHashMap<>();
    private final Map<String,Tail> sockets=new ConcurrentHashMap<>();
    private Clock clock=Clock.systemUTC();
    private final ExecutorService workers=new ThreadPoolExecutor(4,4,60,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_TAILS), task->{Thread t=new Thread(task,"file-tail-read");t.setDaemon(true);return t;});
    private final ExecutorService writers=pool("file-tail-send");
    private Executor executor=workers;
    private static ExecutorService pool(String name) {
        return new ThreadPoolExecutor(0,MAX_TAILS,60,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{Thread thread=new Thread(task,name);thread.setDaemon(true);return thread;});
    }

    public SshFileTailWebSocketHandler(SshSftpService files,SshFileFilterService filters,SshSessionRegistry registry) {
        this.files=files; this.filters=filters; this.registry=registry;
    }
    @Override public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(16384);
        if(session instanceof org.springframework.web.socket.adapter.NativeWebSocketSession) {
            javax.websocket.Session nativeSession=((org.springframework.web.socket.adapter.NativeWebSocketSession)session).getNativeSession(javax.websocket.Session.class);
            if(nativeSession!=null) {
                nativeSession.getAsyncRemote().setSendTimeout(5000);
                nativeSession.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT",5000L);
            }
        }
    }
    @Override protected void handleTextMessage(WebSocketSession socket,TextMessage message) {
        Tail tail=sockets.get(socket.getId());
        try {
            JsonNode frame=json.readTree(message.getPayload());
            if(frame==null || !frame.isObject()) throw new IOException("无效的 Tail 消息");
            String type=required(frame,"type");
            if(tail==null) {
                if("start".equals(type)) start(socket,frame);
                else if("resume".equals(type)) reconnect(socket,frame);
                else if("close".equals(type)) closeSocket(socket);
                else throw new IOException("请先启动或恢复文件 Tail");
                return;
            }
            if("pause".equals(type) || "close".equals(type) || "resume".equals(type)) {
                Control control=tail.control;
                if(control==null || control.socket!=socket || control.disconnected) throw new IOException("Tail 连接已被替换");
                if("pause".equals(type)) {
                    if(!control.paused) { control.pausedAt=clock.millis(); control.paused=true; }
                } else if("close".equals(type)) {
                    control.closed=true; closeSocket(socket); request(tail,true);
                } else { control.paused=false; request(tail,true); }
                return;
            }
            synchronized(tail) {
                if(tail.socket!=socket || tails.get(tail.id)!=tail) throw new IOException("Tail 连接已被替换");
                switch(type) {
                    case "ack": acknowledge(tail,sequence(frame)); if(tail.catchup) request(tail,true); break;
                                        default: throw new IOException("不支持的 Tail 消息");
                }
            }
        } catch(Exception error) {
            Tail current=sockets.get(socket.getId());
            if(current!=null) {current.failure=error;request(current,true);}
            else sendError(socket,error);
        }
    }
    private void start(WebSocketSession socket,JsonNode frame) throws IOException {
        String token=required(frame,"sessionToken"), path=required(frame,"path");
        validateSession(token);
        long offset=number(frame,"offset"); if(offset<0) throw new IOException("无效的 Tail 字节偏移");
        String encoding=frame.path("encoding").asText("UTF-8");
        String scanId=frame.path("scanId").asText("");
        Tail tail=new Tail(UUID.randomUUID().toString(),token,path,encoding,offset);
        synchronized(tail) {
            synchronized(tails) {
                if(tails.size()>=MAX_TAILS) throw new IOException("实时 Tail 数量已达上限，请关闭其他 Tail");
                tails.put(tail.id,tail);
            }
            tail.scanId=scanId; tail.lastHeartbeat=clock.millis();
            tail.control=new Control(socket); tail.socket=socket; sockets.put(socket.getId(),tail);
            deliver(tail,ready(tail)); // The client can resume even if paused before initialization.
            request(tail,true); // Opening and decoder seeding belong on the read worker.

        }
    }
    private void reconnect(WebSocketSession socket,JsonNode frame) throws IOException {
        String token=required(frame,"sessionToken"), id=required(frame,"tailId");
        validateSession(token);
        Tail tail=tails.get(id);
        if(tail==null || !tail.token.equals(token)) throw new FileTailRecoveryException("TAIL_EXPIRED", "Tail 订阅已失效，正在恢复");
        synchronized(tail) {
            if(tails.get(id)!=tail || (tail.control.disconnected && clock.millis()-tail.control.disconnectedAt>=RESUME_MS)) throw new FileTailRecoveryException("TAIL_EXPIRED", "Tail 订阅已过期，正在恢复");
            long applied=sequence(frame);
            if(applied<tail.acknowledged || applied>tail.sequence || (tail.pending==null && applied!=tail.acknowledged))
                throw new IOException("Tail 序号不一致，请重新读取文件");
            if(tail.pending!=null && applied==tail.sequence) acknowledge(tail,applied);
            if(tails.get(id)!=tail || tail.control.closed) { send(socket,ready(tail)); closeSocket(socket); return; }
            Control previous=tail.control;
            if(previous!=null && previous.socket!=socket) { sockets.remove(previous.socket.getId(),tail); SshSftpService.ReadFileHandle retired=previous.handle.getAndSet(null); if(retired!=null) tail.retired=retired; closeSocket(previous.socket); }
            tail.control=new Control(socket); tail.socket=socket; sockets.put(socket.getId(),tail); tail.disconnectedAt=0;
            if(deliver(tail,ready(tail)) && tail.pending!=null) tail.pendingSent=deliver(tail,tail.pending);
            if(tail.pending==null) request(tail,true);
        }
    }
    private void acknowledge(Tail tail,long sequence) throws IOException {
        if(sequence<=tail.acknowledged) return;
        if(tail.pending==null || sequence!=tail.sequence) throw new IOException("Tail 确认序号不正确");
        tail.acknowledged=sequence; tail.pending=null; tail.nextCheckAt=Math.max(clock.millis(),tail.readAt+tail.interval);
        if(tail.limited) { tail.control.closed=true; if(tail.socket!=null) closeSocket(tail.socket); request(tail,true); }
    }
    @Override public void afterConnectionClosed(WebSocketSession socket,CloseStatus status) {
        Tail tail=sockets.get(socket.getId()); if(tail==null) return;
        Control control=tail.control;
        if(control==null || control.socket!=socket) return;
        sockets.remove(socket.getId(),tail); markDisconnected(control);
        request(tail,true); // Close retained resources on the read worker, never on this callback.
    }

    private void markDisconnected(Control control) {
        if(!control.disconnected) {control.disconnectedAt=clock.millis();control.disconnected=true;}
    }
    private void disconnect(Tail tail,WebSocketSession socket) {
        Control control=tail.control;if(control==null || control.socket!=socket) return;
        sockets.remove(socket.getId(),tail);markDisconnected(control);
        tail.socket=null;tail.disconnectedAt=control.disconnectedAt;closeControlHandle(control);
    }
    private boolean deliver(Tail tail,String message) {
        WebSocketSession socket=tail.socket;if(socket==null)return false;
        try {send(socket,message);tail.lastHeartbeat=clock.millis();return true;}
        catch(IOException error) {disconnect(tail,socket);closeSocket(socket);return false;}
    }
    @Override public void handleTransportError(WebSocketSession socket,Throwable error) { afterConnectionClosed(socket,CloseStatus.SERVER_ERROR); closeSocket(socket); }

    /** Cheap dispatcher only. FIFO bounded workers execute one batch per turn for fairness. */
    @Scheduled(fixedDelay=50)
    public void poll() {
        long now=clock.millis();
        for(Tail tail:tails.values()) if(now>=tail.nextCheckAt) request(tail,false);
    }
    private void request(Tail tail,boolean immediate) {
        if(tails.get(tail.id)!=tail) return;
        if(immediate) {tail.nextCheckAt=clock.millis();tail.requested.set(true);}
        if(!tail.scheduled.compareAndSet(false,true)) return;
        try {executor.execute(()->{
            tail.requested.set(false);
            try {pollOne(tail);} finally {
                tail.scheduled.set(false);
                // Handles ACK arriving while the previous batch is finishing, without losing a wakeup.
                if(tail.requested.get()) request(tail,false);
            }
        });} catch(RejectedExecutionException busy) {tail.scheduled.set(false);}
    }
    private void pollOne(Tail tail) {
        synchronized(tail) {
            if(tails.get(tail.id)!=tail) return;
            Control control=tail.control;
            try {
                tail.nextCheckAt=clock.millis()+1000;
                closeRetired(tail);
                if(tail.failure!=null) {fail(tail,tail.failure);return;}
                if(control.closed) {finish(tail);return;}
                validateSession(tail.token); // Also applies while paused, disconnected or awaiting ACK.
                if(control.disconnected || tail.socket==null) {
                    tail.socket=null;tail.disconnectedAt=control.disconnectedAt;closeControlHandle(control);
                    if(clock.millis()-tail.disconnectedAt>=RESUME_MS) finish(tail);
                    return;
                }
                if(!tail.socket.isOpen()) {disconnect(tail,tail.socket);return;}
                if(clock.millis()-tail.lastHeartbeat>=15000) {
                    if(!deliver(tail,"{\"type\":\"heartbeat\"}")) return;
                }
                if(control.paused) { if(clock.millis()-control.pausedAt>=PAUSE_CLOSE_MS) closeHandle(tail); return; }
                if(tail.pending!=null) {if(!tail.pendingSent) tail.pendingSent=deliver(tail,tail.pending);return;}
                if(!tail.initialized) {
                    if(tail.lease==null && !tail.scanId.isEmpty()) {
                        tail.lease=filters.claimTail(tail.token,tail.scanId);tail.path=tail.lease.path();tail.offset=tail.lease.offset();
                    }
                    if(!open(tail)) return;
                    if(tail.lease==null) tail.normal=new NormalReader(control.handle.get(),tail.offset,tail.encoding);
                    tail.initialized=true;
                    tail.nextCheckAt=clock.millis();return;
                }
                if(!open(tail)) return;
                SshSftpService.ReadFileHandle handle=control.handle.get();if(handle==null) return;
                ObjectNode data=json.createObjectNode(); long previous=tail.offset;
                boolean limited=false;
                if(tail.lease!=null) {
                    SshFileFilterService.Batch batch=filters.nextTail(tail.lease,handle);
                    tail.offset=batch.getOffset(); limited=batch.isLimited(); tail.catchup=!batch.isEof();
                    data.put("scanId",tail.lease.scanId()); data.set("rows",json.valueToTree(batch.getRows()));
                    data.put("offset",batch.getOffset()); data.put("totalSize",batch.getTotalSize()); data.put("eof",batch.isEof()); data.put("limited",limited);
                } else {
                    NormalBatch batch=tail.normal.read(handle); tail.offset=batch.offset; tail.catchup=!batch.eof;
                    data.put("content",batch.content); data.put("offset",batch.offset); data.put("totalSize",batch.size); data.put("eof",batch.eof);
                }
                tail.interval=previous==tail.offset ? Math.min(1000,tail.interval*2) : 200;
                tail.readAt=clock.millis(); tail.nextCheckAt=tail.readAt+tail.interval;
                if(previous==tail.offset && !limited) return;
                data.put("type","data"); data.put("sequence",++tail.sequence); tail.limited=limited;
                tail.pending=json.writeValueAsString(data);tail.pendingSent=false;
                if(control.closed) {finish(tail);return;}
                if(!control.paused && !control.disconnected) tail.pendingSent=deliver(tail,tail.pending);
            } catch(Exception error) { fail(tail,error); }
        }
    }
    private void validateSession(String token) throws IOException {
        final SshSessionRegistry.SessionEntry entry;
        try {entry=registry.get(token);}
        catch(com.doumiot.spring.exception.BusinessRuntimeException expired) {throw new FileTailRecoveryException("SESSION_EXPIRED", "SSH 会话已过期，正在重新连接");}
        if(entry.getClient()==null || !entry.getClient().isConnected()) throw new FileTailRecoveryException("SSH_DISCONNECTED", "SSH 连接已断开，正在重新连接");
    }
    private boolean open(Tail tail) throws IOException {
        Control control=tail.control;
        if(control.handle.get()!=null)return true;
        SshSftpService.ReadFileHandle handle=files.openReadHandle(tail.token,tail.path);
        if(control.disconnected || control.closed || tails.get(tail.id)!=tail) {handle.close();return false;}
        control.handle.set(handle);
        if(control.disconnected || control.closed) {closeControlHandle(control);return false;}
        return true;
    }
    private String ready(Tail tail) throws IOException { ObjectNode node=json.createObjectNode(); node.put("type","ready"); node.put("tailId",tail.id); return json.writeValueAsString(node); }
    private void fail(Tail tail,Throwable error) {
        WebSocketSession socket=tail.socket;
        if(tail.lease!=null) try {filters.cancel(tail.token,tail.lease.scanId());} catch(IOException ignored) {}
        finish(tail);if(socket!=null)sendError(socket,error);
    }
    private void finish(Tail tail) {
        if(tail.pending!=null && tail.lease!=null) try {filters.cancel(tail.token,tail.lease.scanId());} catch(IOException ignored) {}
        tails.remove(tail.id,tail); if(tail.socket!=null) sockets.remove(tail.socket.getId(),tail);
        tail.socket=null; tail.pending=null; closeRetired(tail); closeHandle(tail); filters.releaseTail(tail.lease);
    }
    private void closeRetired(Tail tail) {
        SshSftpService.ReadFileHandle handle=tail.retired;tail.retired=null;
        if(handle!=null)try {handle.close();}catch(IOException ignored) {}
    }
    private void closeHandle(Tail tail) {if(tail.control!=null)closeControlHandle(tail.control);}
    private void closeControlHandle(Control control) {
        SshSftpService.ReadFileHandle handle=control.handle.getAndSet(null);
        if(handle!=null)try {handle.close();} catch(IOException ignored) {}
    }
    private void sendError(WebSocketSession socket,Throwable error) {
        try { ObjectNode node=json.createObjectNode(); node.put("type","error"); if(error instanceof FileTailRecoveryException) node.put("code",((FileTailRecoveryException)error).getCode()); node.put("message",error.getMessage()==null?"文件 Tail 已结束，请重新启动":error.getMessage()); send(socket,json.writeValueAsString(node)); }
        catch(IOException ignored) {} finally { closeSocket(socket); }
    }
    private void send(WebSocketSession socket,String message) throws IOException {
        final Future<?> task;
        try {task=writers.submit(()->{synchronized(socket) {if(!socket.isOpen())throw new java.io.UncheckedIOException(new IOException("Tail WebSocket 已关闭"));try {socket.sendMessage(new TextMessage(message));}catch(IOException error){throw new java.io.UncheckedIOException(error);}}});}
        catch(RejectedExecutionException busy) {throw new IOException("Tail 发送繁忙，请稍后恢复连接",busy);}
        try {task.get(3,TimeUnit.SECONDS);}
        catch(InterruptedException interrupted) {task.cancel(true);Thread.currentThread().interrupt();throw new IOException("Tail 发送中断",interrupted);}
        catch(ExecutionException | TimeoutException error) {task.cancel(true);throw new IOException("Tail WebSocket 发送失败或超时",error);}
    }
    private void closeSocket(WebSocketSession socket) {
        try {writers.execute(()->{try {if(socket.isOpen())socket.close(CloseStatus.NORMAL);}catch(IOException ignored){}});}
        catch(RejectedExecutionException ignored) {}
    }
    private static String required(JsonNode frame,String name) throws IOException { JsonNode value=frame.get(name); if(value==null || !value.isTextual() || value.asText().isEmpty()) throw new IOException("缺少 Tail 参数: "+name); return value.asText(); }
    private static long number(JsonNode frame,String name) throws IOException { JsonNode value=frame.get(name); if(value==null || !value.isIntegralNumber() || !value.canConvertToLong()) throw new IOException("无效的 Tail 参数: "+name); return value.asLong(); }
    private static long sequence(JsonNode frame) throws IOException { long value=number(frame,"sequence"); if(value<0) throw new IOException("无效的 Tail 序号"); return value; }
    @PreDestroy public void closeAll() { for(Tail tail:tails.values()) synchronized(tail) { finish(tail); } workers.shutdownNow();writers.shutdownNow(); }
    private static final class Tail {
        final String id,token,encoding; String path;
        long offset,sequence,acknowledged,disconnectedAt,lastHeartbeat;
        boolean limited,pendingSent,catchup,initialized; String pending,scanId="";
        int interval=200;
        volatile long nextCheckAt; long readAt;
        final AtomicBoolean requested=new AtomicBoolean();
        volatile Control control;
        volatile Throwable failure;
        SshSftpService.ReadFileHandle retired;
        final AtomicBoolean scheduled=new AtomicBoolean();
        WebSocketSession socket;
        SshFileFilterService.TailLease lease; NormalReader normal;
        Tail(String id,String token,String path,String encoding,long offset) { this.id=id;this.token=token;this.path=path;this.encoding=encoding;this.offset=offset; }
    }
    private static final class Control {
        final WebSocketSession socket;
        final AtomicReference<SshSftpService.ReadFileHandle> handle=new AtomicReference<>();
        volatile boolean paused,disconnected,closed;
        volatile long pausedAt,disconnectedAt;
        Control(WebSocketSession socket) {this.socket=socket;}
    }
    private static final class NormalBatch {
        final String content; final long offset,size; final boolean eof;
        NormalBatch(String content,long offset,long size) {this.content=content;this.offset=offset;this.size=size;this.eof=offset==size;}
    }
    private static final class NormalReader {
        final CharsetDecoder decoder;
        long offset,lastSize,mtime;
        byte[] prefix,boundary,carry=new byte[0];
        NormalReader(SshSftpService.ReadFileHandle handle,long offset,String encoding) throws IOException {
            try {decoder=Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);}
            catch(IllegalArgumentException error) {throw new IOException("不支持的文件编码: "+encoding,error);}
            this.offset=offset;SshSftpService.ReadSnapshot snapshot=handle.snapshot();lastSize=snapshot.getSize();mtime=snapshot.getMtime();
            if(offset>lastSize) throw changed();
            prefix=readExact(handle,0,(int)Math.min(offset,ANCHOR));
            boundary=readExact(handle,Math.max(0,offset-ANCHOR),(int)Math.min(offset,ANCHOR));
            seed(handle,decoder.charset().name());
        }
        void seed(SshSftpService.ReadFileHandle handle,String encoding) throws IOException {
            if(offset==0 || "ISO-8859-1".equalsIgnoreCase(encoding) || "windows-1252".equalsIgnoreCase(encoding)) return;
            if("UTF-8".equalsIgnoreCase(encoding)) {
                int start=boundary.length-1;
                while(start>=0 && (boundary[start]&0xc0)==0x80) start--;
                if(start>=0) {
                    int lead=boundary[start]&255, expected=lead>=0xf0?4:lead>=0xe0?3:lead>=0xc0?2:1;
                    if(expected>boundary.length-start) carry=Arrays.copyOfRange(boundary,start,boundary.length);
                }
                return;
            }
            if("UTF-16LE".equalsIgnoreCase(encoding) || "UTF-16BE".equalsIgnoreCase(encoding)) {
                long start=Math.max(0,offset-6); start-=start%2;
                byte[] seed=readExact(handle,start,(int)(offset-start));
                if(seed.length>=2) {
                    int unit="UTF-16LE".equalsIgnoreCase(encoding)?(seed[0]&255)|((seed[1]&255)<<8):((seed[0]&255)<<8)|(seed[1]&255);
                    if(unit>=0xdc00 && unit<=0xdfff) seed=Arrays.copyOfRange(seed,2,seed.length);
                }
                decode(seed); return;
            }
            Set<String> supported=new HashSet<>(Arrays.asList("GBK","GB18030","Big5","Shift_JIS","EUC-KR"));
            if(!supported.contains(encoding)) throw new IOException("此编码不支持实时 Tail，请切换编码后重试");
            long start=Math.max(0,offset-SEED_LIMIT); byte[] seed=readExact(handle,start,(int)(offset-start));
            int begin=0;
            for(int i=seed.length-1;i>=0;i--) if(seed[i]=='\n') {begin=i+1;break;}
            if(begin==0 && start>0) throw new IOException("无法确定编码字符边界：前 64 KiB 无换行，请从文件头重新读取或切换 UTF-8");
            decode(Arrays.copyOfRange(seed,begin,seed.length));
        }
        NormalBatch read(SshSftpService.ReadFileHandle handle) throws IOException {
            SshSftpService.ReadSnapshot snapshot=handle.snapshot();long size=snapshot.getSize();
            if(size<lastSize || (snapshot.isStable() && size==lastSize && snapshot.getMtime()!=mtime)) throw changed();
            if(!Arrays.equals(prefix,readExact(handle,0,prefix.length)) || !Arrays.equals(boundary,readExact(handle,offset-boundary.length,boundary.length))) throw changed();
            byte[] bytes=readExact(handle,offset,(int)Math.min(BUDGET-prefix.length-boundary.length,size-offset));
            if(handle.length()<size) throw changed();
            String content=decode(bytes);
            if(prefix.length<ANCHOR) {int take=Math.min(ANCHOR-prefix.length,bytes.length);byte[] next=Arrays.copyOf(prefix,prefix.length+take);System.arraycopy(bytes,0,next,prefix.length,take);prefix=next;}
            byte[] tail=new byte[boundary.length+bytes.length];System.arraycopy(boundary,0,tail,0,boundary.length);System.arraycopy(bytes,0,tail,boundary.length,bytes.length);
            boundary=Arrays.copyOfRange(tail,Math.max(0,tail.length-ANCHOR),tail.length);
            offset+=bytes.length;lastSize=size;mtime=snapshot.getMtime(); return new NormalBatch(content,offset,size);
        }
        String decode(byte[] bytes) throws IOException {
            ByteBuffer input=ByteBuffer.allocate(carry.length+bytes.length);input.put(carry).put(bytes).flip();
            CharBuffer output=CharBuffer.allocate(8192);StringBuilder text=new StringBuilder();
            while(true) {
                CoderResult result=decoder.decode(input,output,false);output.flip();text.append(output);output.clear();
                if(result.isError()) throw new IOException("文件内容与所选编码不匹配，请更换编码后重新启动 Tail");
                if(result.isUnderflow()) break;
            }
            carry=new byte[input.remaining()];input.get(carry);return text.toString();
        }
    }
    private static byte[] readExact(SshSftpService.ReadFileHandle handle,long offset,int length) throws IOException {
        byte[] bytes=new byte[length];int read=0;
        while(read<length) {int n=handle.read(offset+read,bytes,read,length-read);if(n<=0) throw changed();read+=n;}
        return bytes;
    }
    private static IOException changed() {return new FileTailRecoveryException("FILE_CHANGED", "文件已截断、替换或改变，正在重新读取");}
}
