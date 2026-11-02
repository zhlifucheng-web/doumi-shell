package com.doumiot.sshconsole.update;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
/** Bounded downloads. Each retry starts afresh; incomplete files never reach installation. */
public final class UpdateDownload {
    private static final int ATTEMPTS=3;
    private UpdateDownload(){}
    public static void download(String address,Path target,long limit,IntConsumer progress)throws IOException {
        download(address,"",target,limit,progress);
    }
    public static void download(String address,String httpProxy,Path target,long limit,IntConsumer progress)throws IOException {
        download(address,httpProxy,target,limit,progress,30000,1000);
    }
    static void download(String address,String httpProxy,Path target,long limit,IntConsumer progress,int readTimeout,long retryDelay)throws IOException {
        if(Files.exists(target))throw new FileAlreadyExistsException(target.toString());
        String normalized=UpdateSettings.normalizeHttpProxy(httpProxy);
        Proxy proxy=Proxy.NO_PROXY;
        if(!normalized.isEmpty()) {
            URI uri=URI.create(normalized);
            proxy=new Proxy(Proxy.Type.HTTP,new InetSocketAddress(uri.getHost(),uri.getPort()));
        }
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(15);
        for(int attempt=1;;attempt++) {
            try {downloadOnce(address,proxy,target,limit,progress,readTimeout,deadline);return;}
            catch(TransientFailure error) {
                if(attempt==ATTEMPTS)throw new IOException(error.getMessage()+"，已尝试 "+attempt+" 次；请检查 HTTP 下载代理或服务器网络",error);
                long delay=retryDelay*attempt;
                if(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(delay)>=deadline)throw new IOException("下载超过时间限制",error);
                try {Thread.sleep(delay);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("下载已中断");}
            }
        }
    }
    private static void downloadOnce(String address,Proxy proxy,Path target,long limit,IntConsumer progress,int readTimeout,long deadline)throws IOException {
        URL url=new URL(address);HttpURLConnection c=null;boolean created=false,complete=false;
        try {
            for(int redirects=0;;redirects++) {
                if(redirects>6||(!url.getProtocol().equals("https")&&!url.getProtocol().equals("http"))||url.getUserInfo()!=null)throw new IOException("下载重定向无效");
                int remaining=(int)Math.min(Integer.MAX_VALUE,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
                if(remaining<=0)throw new IOException("下载超过时间限制");
                c=(HttpURLConnection)url.openConnection(proxy);c.setConnectTimeout(Math.min(10000,remaining));c.setReadTimeout(Math.min(readTimeout,remaining));
                c.setInstanceFollowRedirects(false);c.setUseCaches(false);
                c.setRequestProperty("User-Agent","Doumi-Shell-Updater");int status=c.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308) {
                    String location=c.getHeaderField("Location");if(location==null)throw new IOException("下载重定向缺少地址");
                    URL next=new URL(url,location);if(url.getProtocol().equals("https")&&!next.getProtocol().equals("https"))throw new IOException("拒绝下载 HTTPS 降级重定向");
                    c.disconnect();c=null;url=next;continue;
                }
                if(status==408||status==429||status==502||status==503||status==504)throw new TransientFailure("下载节点暂时不可用（"+url.getHost()+"，HTTP "+status+"）",null);
                if(status!=200)throw new IOException("GitHub 或加速代理下载返回 HTTP "+status);
                break;
            }
            long length=c.getContentLengthLong();if(length>limit)throw new IOException("下载文件超过大小限制");
            try(InputStream in=c.getInputStream();OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
                created=true;progress.accept(0);
                byte[] b=new byte[65536];long size=0;int n,last=0;
                while((n=in.read(b))!=-1) {
                    size+=n;if(size>limit||System.nanoTime()>deadline)throw new IOException("下载超过大小或时间限制");out.write(b,0,n);
                    int percent=length>0?(int)Math.min(100,size*100/length):0;
                    if(percent!=last){progress.accept(percent);last=percent;}
                }
                if(size==0||(length>=0&&length!=size))throw new TransientFailure("下载文件不完整（"+url.getHost()+"）",null);
            }
            complete=true;
        }catch(SocketTimeoutException error){throw new TransientFailure("下载超时（"+url.getHost()+"）",error);}
        catch(SocketException|EOFException error){throw new TransientFailure("下载连接中断（"+url.getHost()+"）",error);}
        finally{if(c!=null)c.disconnect();if(created&&!complete)Files.deleteIfExists(target);}
    }
    private static final class TransientFailure extends IOException {
        TransientFailure(String message,Throwable cause){super(message,cause);}
    }
}
