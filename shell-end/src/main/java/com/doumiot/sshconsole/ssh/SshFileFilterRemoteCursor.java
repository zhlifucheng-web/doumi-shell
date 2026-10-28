package com.doumiot.sshconsole.ssh;

import com.doumiot.sshconsole.service.SshFileFilterService;
import com.doumiot.sshconsole.sftp.FileTailRecoveryException;
import com.doumiot.sshconsole.sftp.SshFileFilterScanner;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Native grep is autonomous. Each poll consumes at most 256KiB of available wire data. */
public class SshFileFilterRemoteCursor implements Closeable {
    private static final int WIRE_BUDGET=256*1024;
    private final InputStream input;
    private final BooleanSupplier exited;
    private final Closeable channel;
    private final Consumer<Boolean> control;
    private final SshFileFilterScanner scanner;
    private final String keyword;
    private final NativeFilterSnapshot snapshot;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final StringBuilder header=new StringBuilder();
    private final byte[] body=new byte[8192];
    private int bodySize;
    private long rowNumber,rowOffset,rowBytes,previousLine,offset,completedLines=-1;
    private boolean matched,inBody,finished,seeded;
    private SshFileFilterScanner.NativeLine line;
    private volatile IOException controlFailure;
    private IOException deferredFailure;
    private long wireBytes;
    private final long startedAt=System.nanoTime();
    interface Guard {void check() throws IOException;}
    Guard guard=()->{};
    Guard cleanupGuard=()->{};

    SshFileFilterRemoteCursor(InputStream input,BooleanSupplier exited,Closeable channel,NativeFilterSnapshot snapshot,
            SshFileFilterScanner scanner,String keyword,Consumer<Boolean> control) {
        this.input=input;this.exited=exited;this.channel=channel;this.snapshot=snapshot;this.scanner=scanner;this.keyword=keyword;this.control=control;
    }
    public long size(){return snapshot.size;}
    public boolean finished(){return finished;}
    public NativeFilterSnapshot snapshot(){return snapshot;}
    public boolean isClosed(){return closed.get();}
    public void verifySnapshot() throws IOException {guard.check();}
    public void awaitCleanup() throws IOException {cleanupGuard.check();}
    public long wireBytes(){return wireBytes;}
    public void pause(boolean paused){if(!closed.get()&&!finished)control.accept(paused);}
    void controlFailed(IOException failure){controlFailure=failure;try{close();}catch(IOException ignored){}}
    public void seedScanner() throws IOException {
        if(!finished || completedLines<0)throw new IOException("原生历史筛选尚未完成");
        if(!seeded){scanner.seedNative(snapshot.seed,completedLines);seeded=true;}
    }
    public SshFileFilterService.Batch next(String id) throws IOException {
        if(deferredFailure!=null)throw deferredFailure;
        if(controlFailure!=null)throw controlFailure;
        if(System.nanoTime()-startedAt>600_000_000_000L)throw new IOException("原生历史扫描超过十分钟，使用兼容扫描");
        if(closed.get())throw new FileTailRecoveryException("TAIL_EXPIRED","原生筛选通道已关闭，请重新筛选");
        List<SshFileFilterScanner.Row> rows=new ArrayList<>();int consumed=0;
        long deadline=System.nanoTime()+20_000_000L;
        try {
            while(!finished && !scanner.isLimited() && consumed<WIRE_BUDGET) {
                int available=input.available();if(available<=0)break;
                int n=Math.min(Math.min(available,WIRE_BUDGET-consumed),8192);byte[] bytes=new byte[n];int read=input.read(bytes);
                if(read<0)break;consumed+=read;wireBytes+=read;
                for(int i=0;i<read;i++)accept(bytes[i],rows);
                if(System.nanoTime()>=deadline)break;
            }
            if(!finished && !scanner.isLimited() && input.available()==0 && exited.getAsBoolean())
                throw new FileTailRecoveryException("TAIL_EXPIRED","原生筛选通道意外结束，请重新筛选");
            if(scanner.isLimited())close();
            return new SshFileFilterService.Batch(id,rows,finished?snapshot.size:offset,snapshot.size,finished,scanner.isLimited(),"native",null);
        } catch(IOException error){
            close();
            // The shared limiter already counted these rows: deliver them before replay fallback.
            if(!rows.isEmpty()){deferredFailure=error;return new SshFileFilterService.Batch(id,rows,offset,snapshot.size,false,scanner.isLimited(),"native",null);}
            throw error;
        } catch(RuntimeException error){close();throw error;}
    }
    private void accept(byte value,List<SshFileFilterScanner.Row> rows) throws IOException {
        if(finished)throw new IOException("原生筛选结束后返回额外数据");
        if(inBody) {
            if(value=='\n') {
                flushBody();SshFileFilterScanner.Row row=line.finish(rowNumber);
                if(row.isMatched()!=matched)throw new IOException("原生筛选匹配标记无效");
                SshFileFilterScanner.Row accepted=scanner.acceptNativeRow(row);if(accepted!=null)rows.add(accepted);
                offset=Math.min(snapshot.size,rowOffset+rowBytes+1);previousLine=rowNumber;inBody=false;line=null;
            } else {body[bodySize++]=value;rowBytes++;offset=Math.min(snapshot.size,rowOffset+rowBytes);if(bodySize==body.length)flushBody();}
            return;
        }
        if(value=='\n'){parseControl(header.toString());header.setLength(0);return;}
        if(value<0 || header.length()>=127)throw new IOException("原生筛选行头无效");
        header.append((char)value);
        String text=header.toString();int split=text.indexOf(':');if(split<0)split=text.indexOf('-');
        if(split>0 && (value==':' || value=='-') && text.length()>split+1) {
            char delimiter=text.charAt(split);if(value!=delimiter)throw new IOException("原生筛选分隔符无效");
            rowNumber=number(text.substring(0,split));rowOffset=number(text.substring(split+1,text.length()-1));
            if(completedLines<0 || rowNumber<=previousLine || rowNumber>completedLines || rowOffset>=snapshot.size || rowOffset<offset)
                throw new IOException("原生筛选绝对行号或偏移无效");
            matched=delimiter==':';rowBytes=0;bodySize=0;line=new SshFileFilterScanner.NativeLine(keyword);inBody=true;header.setLength(0);
        }
    }
    private void flushBody() throws IOException {if(bodySize>0){line.accept(body,0,bodySize);bodySize=0;}}
    private void parseControl(String text) throws IOException {
        if("--".equals(text))return;
        if(text.startsWith("COUNT\t") && completedLines<0){completedLines=number(text.substring(6));return;}
        if("END\tOK".equals(text) && completedLines>=0){finished=true;return;}
        if("END\tCHANGED".equals(text))throw new FileTailRecoveryException("FILE_CHANGED","文件已改变或截断，正在重新筛选");
        if("END\tENCODING".equals(text))throw new IOException("文件内容与 UTF-8 编码不匹配，请更换编码");
        throw new IOException("原生筛选失败: "+text);
    }
    private static long number(String text) throws IOException {
        if(!text.matches("[0-9]{1,19}"))throw new IOException("原生筛选数值无效");
        try{return Long.parseLong(text);}catch(NumberFormatException error){throw new IOException("原生筛选数值溢出",error);}
    }
    @Override public void close() throws IOException {if(closed.compareAndSet(false,true))channel.close();}
}
