package com.doumiot.config;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** 文件锁在整个进程生命周期内持有；就绪文件保存实际监听地址。 */
public final class DesktopInstance implements AutoCloseable {
    private final FileChannel channel;
    private final Path readyFile;
    private FileLock lock;

    public DesktopInstance(Path directory) throws IOException {
        Files.createDirectories(directory);
        channel=FileChannel.open(directory.resolve("doumi.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        readyFile=directory.resolve("desktop-instance.properties");
    }

    public boolean tryAcquire() throws IOException {
        if(lock!=null && lock.isValid()) return true;
        try {lock=channel.tryLock();}catch(OverlappingFileLockException e){return false;}
        if(lock==null)return false;
        Files.deleteIfExists(readyFile);
        return true;
    }

    public void publish(String url) throws IOException {
        if(lock==null || !lock.isValid())throw new IllegalStateException("当前进程不持有启动锁");
        if(!isLocalRoot(url))throw new IllegalArgumentException("应用地址须为本机 HTTP 根路径");
        Properties info=new Properties();info.setProperty("url",url);
        Path temporary=Files.createTempFile(readyFile.getParent(),"desktop-instance-",".tmp");
        try {
            try(OutputStream out=Files.newOutputStream(temporary)){info.store(out,"Doumi Shell running instance");}
            try {Files.move(temporary,readyFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,readyFile,StandardCopyOption.REPLACE_EXISTING);}
        }finally {Files.deleteIfExists(temporary);}
    }

    public String runningUrl() {
        Properties info=new Properties();
        try(InputStream in=Files.newInputStream(readyFile)) {
            info.load(in);String url=info.getProperty("url");
            if(isReady(url))return url;
        }catch(IOException ignored) { /* Wait for the owning instance to publish readiness. */ }
        return null;
    }

    private static boolean isLocalRoot(String value) {
        try {
            URI uri=URI.create(value);
            return "http".equals(uri.getScheme()) && ("localhost".equalsIgnoreCase(uri.getHost())
                    || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()))
                    && uri.getUserInfo()==null && uri.getQuery()==null && uri.getFragment()==null
                    && (uri.getPort()==-1 || uri.getPort()>0 && uri.getPort()<=65535) && "/".equals(uri.getPath());
        }catch(Exception e){return false;}
    }

    private static boolean isReady(String value) {
        if(!isLocalRoot(value))return false;
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)new URL(value).openConnection();
            connection.setConnectTimeout(300);connection.setReadTimeout(300);connection.setInstanceFollowRedirects(false);
            if(connection.getResponseCode()!=200)return false;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=connection.getInputStream()) {
                byte[] buffer=new byte[2048];int n;
                while(bytes.size()<65536 && (n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
            }
            String page=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            return page.contains("<title>Doumi Shell");
        }catch(IOException e){return false;}
        finally {if(connection!=null)connection.disconnect();}
    }

    @Override public void close() throws IOException {
        try {if(lock!=null && lock.isValid()) {Files.deleteIfExists(readyFile);lock.release();}}
        finally {channel.close();}
    }
}
