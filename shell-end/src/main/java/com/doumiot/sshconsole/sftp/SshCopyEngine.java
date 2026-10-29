package com.doumiot.sshconsole.sftp;

import net.schmizz.sshj.sftp.*;
import java.io.*;
import java.util.*;
import java.util.function.LongConsumer;

/** Shared preflight and commit rules for relay and direct copies. Never follows symlinks. */
public final class SshCopyEngine {
    private SshCopyEngine() {}
    @FunctionalInterface public interface ContentCopier {
        void copy(String source, String temporary, long size, LongConsumer report) throws IOException;
    }
    @FunctionalInterface public interface Observer {
        void progress(long loaded, long total, String path);
    }
    public static final class Plan {
        final List<Item> items = new ArrayList<>();
        String source, directory;
        boolean overwrite;
        long total;
        public long getTotal() { return total; }
    }
    private static final class Item {
        String source, target;
        boolean directory;
        long size;
        Item(String source, String target, FileAttributes a) {
            this.source=source; this.target=target;
            directory=a.getType()==FileMode.Type.DIRECTORY; size=directory?0:a.getSize();
        }
    }
    public static Plan plan(SFTPClient source, SFTPClient target, String from, String directory,
            boolean overwrite, boolean sameMachine) throws IOException {
        validateAbsolute(from); validateAbsolute(directory);
        // Check every existing ancestor before canonicalization can hide a symlink.
        checkAncestors(source, from, false);
        checkAncestors(target, directory, true);
        String canonical=source.canonicalize(from);
        String base=canonicalDestination(target,directory);
        String name=canonical.substring(canonical.lastIndexOf('/')+1);
        validateName(name);
        String destination=child(base,name);
        FileAttributes attrs=source.lstat(canonical);
        if (sameMachine && (canonical.equals(destination)
                || attrs.getType()==FileMode.Type.DIRECTORY && destination.startsWith(canonical+"/")))
            throw new IOException("不能将文件或文件夹复制到自身或其子目录");
        Plan plan=new Plan(); plan.source=canonical; plan.directory=base; plan.overwrite=overwrite;
        scan(source,canonical,destination,attrs,plan,0);
        for (Item item:plan.items) checkTarget(target,item,overwrite);
        return plan;
    }
    private static void scan(SFTPClient source,String path,String target,FileAttributes attrs,Plan plan,int depth) throws IOException {
        if(depth>64 || plan.items.size()>=10000) throw new IOException("复制最多支持 10000 项、64 层目录");
        if(attrs.getType()!=FileMode.Type.DIRECTORY && attrs.getType()!=FileMode.Type.REGULAR)
            throw new IOException("复制不支持符号链接或特殊文件："+path);
        if(attrs.getType()==FileMode.Type.REGULAR && (!attrs.has(FileAttributes.Flag.SIZE) || attrs.getSize()<0))
            throw new IOException("无法读取源文件长度："+path);
        Item item=new Item(path,target,attrs); plan.items.add(item);
        if(Long.MAX_VALUE-plan.total<item.size) throw new IOException("复制总大小超出支持范围");
        plan.total+=item.size;
        if(item.directory) for(RemoteResourceInfo entry:source.ls(path)) {
            if(".".equals(entry.getName()) || "..".equals(entry.getName())) continue;
            validateName(entry.getName());
            scan(source,child(path,entry.getName()),child(target,entry.getName()),entry.getAttributes(),plan,depth+1);
        }
    }
    public static String execute(Plan plan,SFTPClient target,SftpStreams.Ownership owner,
            ContentCopier copier,Observer observer) throws IOException {
        long loaded=0;
        List<String> created=new ArrayList<>();
        String warning=null;
        // Create with the SSH identity; defer directory chown until descendants are written.
        try {
            observer.progress(0,plan.total,plan.source);
            mkdirs(target,plan.directory,created);
            for(Item item:plan.items) {
                observer.progress(loaded,plan.total,item.source);
                checkTarget(target,item,plan.overwrite);
                if(item.directory) { mkdirs(target,item.target,created); continue; }
                final long before=loaded;
                String temporary=item.target.substring(0,item.target.lastIndexOf('/')+1)+".doumi-copy-"+UUID.randomUUID()+".part";
                boolean staged=false;
                try {
                    try(RemoteFile ignored=SftpStreams.createTemporary(target,temporary)) { staged=true; }
                    copier.copy(item.source,temporary,item.size,n->observer.progress(before+n,plan.total,item.source));
                    if(target.size(temporary)!=item.size) throw new IOException("复制文件长度不完整："+item.source);
                    observer.progress(before+item.size,plan.total,item.source); // Cancellation gate before commit.
                    checkTarget(target,item,plan.overwrite);
                    if(plan.overwrite) {
                        SftpStreams.UploadResult result=SftpStreams.commit(target,temporary,item.target,owner);
                        if(result.getWarning()!=null) warning="内容已复制，但部分文件属主设置失败，请检查目标账户权限";
                    } else {
                        // SFTP v3 RENAME without overwrite flags fails if a racing target appears.
                        target.rename(temporary,item.target);
                        if(SftpStreams.applyOwnership(target,item.target,owner).getWarning()!=null)
                            warning="内容已复制，但部分文件属主设置失败，请检查目标账户权限";
                    }
                    staged=false;
                } catch(IOException | RuntimeException error) {
                    if(staged) try {target.rm(temporary);} catch(IOException | RuntimeException cleanup) {
                        error.addSuppressed(cleanup);
                        throw new IOException("复制中断，临时文件清理失败，请检查："+temporary,error);
                    }
                    throw error;
                }
                loaded+=item.size;
            }
        } finally {
            // Existing destination directories and unrelated contents keep their owner.
            Collections.reverse(created);
            for(String directory:created) {
                if(SftpStreams.applyOwnership(target,directory,owner).getWarning()!=null)
                    warning="内容已复制，但部分新建目录属主设置失败，请检查目标账户权限";
            }
        }
        return warning;
    }
    public static void relay(SFTPClient source,SFTPClient target,String from,String temporary,long expected,
            LongConsumer report) throws IOException {
        FileAttributes attrs=source.lstat(from);
        if(attrs.getType()!=FileMode.Type.REGULAR || attrs.getSize()!=expected) throw new IOException("源文件在复制期间发生变化");
        try(RemoteFile input=source.open(from); RemoteFile output=target.open(temporary,EnumSet.of(OpenMode.WRITE));
                InputStream in=input.new RemoteFileInputStream(); OutputStream out=output.new RemoteFileOutputStream()) {
            byte[] buffer=new byte[65536]; long count=0; int n;
            while((n=in.read(buffer))!=-1) {
                if(n==0) continue;
                if(n>expected-count) throw new IOException("源文件在复制期间增长，已停止复制");
                out.write(buffer,0,n); count+=n; report.accept(count);
            }
            if(count!=expected) throw new IOException("源文件在复制期间发生变化");
        }
    }
    private static void checkTarget(SFTPClient target,Item item,boolean overwrite) throws IOException {
        checkAncestors(target,item.target,false);
        FileAttributes existing=lstatOrNull(target,item.target);
        if(existing==null) return;
        if(item.directory && existing.getType()==FileMode.Type.DIRECTORY) return;
        if(item.directory || existing.getType()!=FileMode.Type.REGULAR) throw new IOException("目标类型冲突："+item.target);
        if(!overwrite) throw new IOException("目标文件已存在，请选择覆盖或更换目录："+item.target);
    }
    private static void mkdirs(SFTPClient target,String path,List<String> created) throws IOException {
        String current="";
        for(String part:path.substring(1).split("/")) {
            if(part.isEmpty()) continue;
            current+="/"+part;
            FileAttributes existing=lstatOrNull(target,current);
            if(existing!=null) {if(existing.getType()!=FileMode.Type.DIRECTORY) throw new IOException("目标路径不是普通目录："+current);continue;}
            try {target.mkdir(current);created.add(current);}
            catch(SFTPException error) {
                if(target.lstat(current).getType()!=FileMode.Type.DIRECTORY) throw error;
            }
        }
    }
    private static String canonicalDestination(SFTPClient target,String path) throws IOException {
        String current=path; String suffix="";
        while(lstatOrNull(target,current)==null) {
            int split=current.lastIndexOf('/');suffix=current.substring(split)+suffix;
            current=split==0?"/":current.substring(0,split);
        }
        String base=target.canonicalize(current);
        return "/".equals(base)?(suffix.isEmpty()?"/":suffix):base+suffix;
    }
    private static void checkAncestors(SFTPClient sftp,String path,boolean lastIsDirectory) throws IOException {
        String[] parts=path.substring(1).split("/"); String current="";
        for(int i=0;i<parts.length;i++) {
            if(parts[i].isEmpty()) continue;
            validateName(parts[i]);current+="/"+parts[i];
            FileAttributes attrs=lstatOrNull(sftp,current);
            if(attrs==null) continue;
            if(attrs.getType()==FileMode.Type.SYMLINK || (i<parts.length-1 || lastIsDirectory) && attrs.getType()!=FileMode.Type.DIRECTORY)
                throw new IOException("路径包含符号链接或非目录："+current);
        }
    }
    private static FileAttributes lstatOrNull(SFTPClient sftp,String path) throws IOException {
        try{return sftp.lstat(path);}catch(SFTPException error){if(error.getStatusCode()==Response.StatusCode.NO_SUCH_FILE)return null;throw error;}
    }
    private static void validateAbsolute(String path) throws IOException {
        if(path==null || !path.startsWith("/") || path.indexOf('\0')>=0 || path.length()>4096)
            throw new IOException("复制路径必须是有效的绝对路径");
    }
    private static void validateName(String name) throws IOException {
        if(name==null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.indexOf('\0')>=0)
            throw new IOException("无效的复制文件名");
    }
    private static String child(String parent,String name) {return parent.endsWith("/")?parent+name:parent+"/"+name;}
}
