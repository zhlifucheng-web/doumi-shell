package com.doumiot.sshconsole.ssh;

import com.doumiot.sshconsole.service.SshFileFilterService;
import com.doumiot.sshconsole.sftp.FileTailRecoveryException;
import com.doumiot.sshconsole.sftp.SshFileFilterScanner;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One demand/one reply on an independent SSH exec channel. No background prefetch. */
public class SshFileFilterRemoteCursor implements Closeable {
    private static final int MAX_FRAME = 4 * 1024 * 1024;
    private static final long READ_TIMEOUT_MS = 10000;
    private final InputStream input;
    private final OutputStream output;
    private final Closeable channel;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long deadline = Long.MAX_VALUE;
    private long offset, size;

    SshFileFilterRemoteCursor(InputStream input, OutputStream output, Closeable channel, Clock clock) {
        this.input=new BufferedInputStream(input); this.output=output; this.channel=channel; this.clock=clock;
    }
    boolean initialize(Map<String,Object> config) throws IOException {
        begin();
        try {
            output.write(json.writeValueAsBytes(config));output.write('\n');output.flush();
            JsonNode ready=frame();
            if("unsupported".equals(ready.path("type").asText())) {close();return false;}
            if(!"ready".equals(ready.path("type").asText())) throw new IOException("远端筛选启动响应无效");
            size=number(ready,"totalSize");return true;
        } catch(IOException | RuntimeException error) {close();throw error;}
        finally {deadline=Long.MAX_VALUE;}
    }
    public long size() {return size;}
    public SshFileFilterService.Batch next(String id) throws IOException {
        begin();
        try {
            output.write("next\n".getBytes(StandardCharsets.US_ASCII));output.flush();
            JsonNode page=frame();
            long end=number(page,"offset"), total=number(page,"totalSize");
            if(!"batch".equals(page.path("type").asText()) || end<offset || end>total || end-offset>256*1024
                    || !page.path("eof").isBoolean() || !page.path("limited").isBoolean() || page.path("eof").asBoolean()!=(end==total)
                    || !page.path("rows").isArray() || page.path("rows").size()>10000)
                throw new IOException("远端筛选数据范围无效");
            List<SshFileFilterScanner.Row> rows=new ArrayList<>();long previous=0;int chars=0;
            for(JsonNode row:page.path("rows")) {
                long line=number(row,"lineNumber");
                if(line<=previous || !row.path("text").isTextual() || !row.path("matched").isBoolean()
                        || row.path("text").textValue().length()>65536) throw new IOException("远端筛选行数据无效");
                String text=row.path("text").textValue();chars+=text.length();
                if(chars>4*1024*1024) throw new IOException("远端筛选结果超过内存限制");
                rows.add(new SshFileFilterScanner.Row(line,text,row.path("matched").asBoolean()));previous=line;
            }
            offset=end;size=total;
            return new SshFileFilterService.Batch(id,rows,end,total,page.path("eof").asBoolean(),page.path("limited").asBoolean());
        } catch(IOException error) {
            boolean interrupted=closed.get();close();
            if(interrupted && !(error instanceof FileTailRecoveryException)) throw new FileTailRecoveryException("TAIL_EXPIRED","远端筛选通道已关闭或读取超时，正在重新筛选");
            throw error;
        } catch(RuntimeException error) {close();throw error;}
        finally {deadline=Long.MAX_VALUE;}
    }
    private void begin() throws IOException {
        if(closed.get()) throw new IOException("远端筛选通道已关闭，请重新筛选");
        deadline=clock.millis()+READ_TIMEOUT_MS;
    }
    private JsonNode frame() throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        int ch;
        while((ch=input.read())!='\n') {
            if(ch<0) throw new FileTailRecoveryException("TAIL_EXPIRED","远端筛选通道已结束，正在重新筛选");
            if(bytes.size()>=MAX_FRAME) throw new IOException("远端筛选响应超过内存限制");
            bytes.write(ch);
        }
        JsonNode frame=json.readTree(bytes.toByteArray());
        if(frame==null || !frame.isObject()) throw new IOException("远端筛选返回无效数据");
        if("error".equals(frame.path("type").asText())) {
            String message=frame.path("message").asText("远端筛选失败，请重新筛选");
            if("FILE_CHANGED".equals(frame.path("code").asText())) throw new FileTailRecoveryException("FILE_CHANGED",message);
            throw new IOException(message);
        }
        return frame;
    }
    private static long number(JsonNode node,String name) throws IOException {
        JsonNode value=node.path(name);
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong()<0) throw new IOException("远端筛选数值无效: "+name);
        return value.asLong();
    }
    void expire(long now) {if(now>=deadline) try {close();}catch(IOException ignored) {}}
    boolean isClosed() {return closed.get();}
    @Override public void close() throws IOException {
        if(closed.compareAndSet(false,true)) {deadline=Long.MAX_VALUE;channel.close();}
    }
}
