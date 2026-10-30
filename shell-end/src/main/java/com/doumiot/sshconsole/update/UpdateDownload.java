package com.doumiot.sshconsole.update;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.function.IntConsumer;
/** Streaming bounded downloads; never install an incomplete or unverified file. */
public final class UpdateDownload {
    private UpdateDownload(){}
    public static void download(String address,Path target,long limit,IntConsumer progress)throws IOException {
        URL url=new URL(address);HttpURLConnection c=null;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(15);
        try {
            for(int redirects=0;;redirects++) {
                if(redirects>6||(!url.getProtocol().equals("https")&&!url.getProtocol().equals("http"))||url.getUserInfo()!=null)throw new IOException("下载重定向无效");
                c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(10000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent","Doumi-Shell-Updater");int status=c.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308) {
                    String location=c.getHeaderField("Location");if(location==null)throw new IOException("下载重定向缺少地址");
                    URL next=new URL(url,location);if(url.getProtocol().equals("https")&&!next.getProtocol().equals("https"))throw new IOException("拒绝下载 HTTPS 降级重定向");
                    c.disconnect();url=next;continue;
                }
                if(status!=200)throw new IOException("GitHub 或加速代理下载返回 HTTP "+status);
                break;
            }
            long length=c.getContentLengthLong();if(length>limit)throw new IOException("下载文件超过大小限制");
            try(InputStream in=c.getInputStream();OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
                byte[] b=new byte[65536];long size=0;int n,last=-1;
                while((n=in.read(b))!=-1) {
                    size+=n;if(size>limit||System.nanoTime()>deadline)throw new IOException("下载超过大小或时间限制");out.write(b,0,n);
                    int percent=length>0?(int)Math.min(100,size*100/length):0;
                    if(percent!=last){progress.accept(percent);last=percent;}
                }
                if(size==0||(length>=0&&length!=size))throw new IOException("下载文件不完整");
            }
        }catch(RuntimeException e){Files.deleteIfExists(target);throw e;}
        catch(IOException e){Files.deleteIfExists(target);throw e;}
        finally{if(c!=null)c.disconnect();}
    }
}
