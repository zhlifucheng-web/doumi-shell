package com.doumiot.updater;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Dependency-free offline file operations shared by the installer and its tests. */
public final class UpdateFiles {
    private UpdateFiles() {}
    public static String hex(byte[] bytes) {StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString();}
    public static void verify(Path file,String checksum) throws Exception {
        String[] parts=checksum.trim().split("\\s+");
        if(parts.length!=2||!parts[0].matches("[a-fA-F0-9]{64}")||!parts[1].replaceFirst("^\\*", "").equals(file.getFileName().toString()))
            throw new IOException("校验文件格式或文件名不匹配");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        if(!MessageDigest.isEqual(digest.digest(),HexFormat.of().parseHex(parts[0])))throw new IOException("安装包 SHA-256 校验失败");
    }
    public static boolean programFile(String name) {
        return Set.of("doumi-shell.jar","doumi-updater.jar","run-linux.sh","README.md","LICENSE","config.example.yml").contains(name)
                ||name.startsWith("docs/");
    }
    public static void extractLinux(Path archive,Path target,String version) throws IOException {
        if(!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IOException("版本号无效");
        Files.createDirectories(target);String prefix="doumi-shell-"+version+"/";long total=0;int count=0;
        try(InputStream in=new GZIPInputStream(Files.newInputStream(archive))) {
            byte[] header=new byte[512];
            while(true) {
                int read=in.readNBytes(header,0,512);if(read!=512)throw new IOException("安装包不完整");
                boolean empty=true;for(byte b:header)if(b!=0){empty=false;break;}if(empty)break;
                long sum=0;for(int i=0;i<512;i++)sum+=(i>=148&&i<156)?32:header[i]&255;
                if(sum!=octal(header,148,8))throw new IOException("TAR 文件头校验失败");
                String name=text(header,0,100),parent=text(header,345,155);if(!parent.isEmpty())name=parent+"/"+name;
                if(++count>20000||!name.startsWith(prefix)||name.contains("\\")||name.contains("//"))throw new IOException("安装包路径无效");
                String relative=name.substring(prefix.length());
                if(Arrays.asList(relative.split("/",-1)).contains("..")||Arrays.asList(relative.split("/",-1)).contains("."))throw new IOException("安装包包含越界路径");
                Path output=target.resolve(relative).normalize();if(!output.startsWith(target)||relative.startsWith("/"))throw new IOException("安装包包含越界路径");
                long size=octal(header,124,12);total+=size;if(size<0||total>1024L*1024*1024)throw new IOException("安装包解压大小超过限制");
                byte type=header[156];
                if(type=='5') {
                    if(size!=0||(!relative.isEmpty()&&!relative.equals("docs/")&&!relative.startsWith("docs/")))throw new IOException("安装包目录无效");
                    Files.createDirectories(output);
                } else if(type==0||type=='0') {
                    if(!programFile(relative))throw new IOException("安装包包含非程序文件");
                    Files.createDirectories(output.getParent());
                    try(OutputStream out=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer=new byte[65536];long left=size;
                        while(left>0){int n=in.read(buffer,0,(int)Math.min(left,buffer.length));if(n<0)throw new EOFException();out.write(buffer,0,n);left-=n;}
                    }
                    if(relative.equals("run-linux.sh")&&!output.toFile().setExecutable(true,false))throw new IOException("无法设置启动脚本权限");
                } else throw new IOException("安装包包含链接或不支持的文件类型");
                long padding=(512-size%512)%512;in.skipNBytes(padding);
            }
        }
    }
    private static long octal(byte[] b,int start,int size)throws IOException {
        String v=text(b,start,size).trim();try{return v.isEmpty()?0:Long.parseLong(v,8);}catch(NumberFormatException e){throw new IOException("TAR 数字字段无效",e);}
    }
    private static String text(byte[] b,int start,int size){int end=start;while(end<start+size&&b[end]!=0)end++;return new String(b,start,end-start,StandardCharsets.UTF_8);}
    public static void verifyJarVersion(Path jar,String version) throws IOException {
        try(ZipFile zip=new ZipFile(jar.toFile())) {
            ZipEntry entry=zip.getEntry("BOOT-INF/classes/static/version.json");if(entry==null)throw new IOException("安装包缺少版本信息");
            String json;try(InputStream in=zip.getInputStream(entry)){json=new String(in.readNBytes(1024),StandardCharsets.UTF_8);}
            if(!java.util.regex.Pattern.compile("\"version\"\\s*:\\s*\""+java.util.regex.Pattern.quote(version)+"\"").matcher(json).find())throw new IOException("安装包内置版本与 Release 不一致");
        }
    }
    public static void snapshot(Path source,Path target,Set<String> excluded) throws IOException {
        if(!Files.isDirectory(source)||Files.isSymbolicLink(source))throw new IOException("备份源目录无效");
        Files.createDirectories(target);
        Files.walkFileTree(source,new SimpleFileVisitor<Path>() {
            boolean skip(Path path){Path r=source.relativize(path);return !r.toString().isEmpty()&&excluded.contains(r.getName(0).toString());}
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException {
                if(skip(dir))return FileVisitResult.SKIP_SUBTREE;Files.createDirectories(target.resolve(source.relativize(dir)));return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                if(!skip(file)){if(!attrs.isRegularFile())throw new IOException("备份目录包含链接或特殊文件");
                    Files.copy(file,target.resolve(source.relativize(file)),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);}
                return FileVisitResult.CONTINUE;
            }
        });
    }
    public static void restore(Path target,Path backup,Set<String> excluded)throws IOException {
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(target)){for(Path entry:entries)if(!excluded.contains(entry.getFileName().toString()))delete(entry);}
        snapshot(backup,target,Collections.emptySet());
    }
    public static void delete(Path path)throws IOException {
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;
        Files.walkFileTree(path,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{Files.delete(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException{if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE;}
        });
    }
    public static Properties load(Path file)throws IOException{Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}return p;}
    public static void store(Path file,Properties p)throws IOException {
        Path tmp=Files.createTempFile(file.getParent(),"update-",".tmp");
        try {try(OutputStream out=Files.newOutputStream(tmp)){p.store(out,"Doumi updater");}
            try{Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}}
        finally{Files.deleteIfExists(tmp);}
    }
    public static void state(Path directory,String phase,String message,int progress)throws IOException {
        Properties state=new Properties();state.setProperty("phase",phase);state.setProperty("message",message);state.setProperty("progress",Integer.toString(progress));
        store(directory.resolve("state.properties"),state);
    }
}
