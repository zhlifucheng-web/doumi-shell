package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.config.SshConsoleProperties;
import com.doumiot.sshconsole.sftp.SshSftpPathResolver;
import com.doumiot.sshconsole.sftp.SftpStreams;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import lombok.Data;
import net.schmizz.sshj.sftp.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.annotation.Resource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Folder transfers use SFTP and local ZIP processing, without remote shell/archive dependencies. */
@Service @SshConsoleEnabled
public class SshFolderTransferService {
    @Resource private SshSessionRegistry sessions;
    @Resource private SshConsoleProperties properties;
    private long limit() { return properties.getUploadMaxSizeMb()*1024L*1024L; }
    public long maxBytes() { return limit(); }
    public void mkdir(String token,String path,String relative) throws IOException {
        validateRelative(relative);
        SshSessionRegistry.SessionEntry session=sessions.get(token);
        try(SFTPClient sftp=session.getClient().newSFTPClient()) {
            String base=sftp.canonicalize(SshSftpPathResolver.resolveForList(path,session.getDefaultCwd(),session.getHomeDir()));
            prepareTarget(sftp,base,relative,true);
        }
    }

    public Tree tree(String token,String path) throws IOException {
        SshSessionRegistry.SessionEntry session=sessions.get(token);
        try(SFTPClient sftp=session.getClient().newSFTPClient()) {
            return tree(sftp,SshSftpPathResolver.resolve(path,session.getDefaultCwd(),session.getHomeDir()));
        }
    }
    private Tree tree(SFTPClient sftp,String path) throws IOException {
        if(sftp.lstat(path).getType()!=FileMode.Type.DIRECTORY)throw new IOException("请选择普通目录，文件夹传输不跟随符号链接");
        String canonical=sftp.canonicalize(path);
        Tree tree=new Tree();tree.setPath(canonical);
        tree.setName(canonical.equals("/")?"root":canonical.substring(canonical.lastIndexOf('/')+1));
        validateRelative(tree.getName());
        walk(sftp,canonical,"",tree,0);
        return tree;
    }
    private void walk(SFTPClient sftp,String base,String relative,Tree tree,int depth) throws IOException {
        if(depth>64)throw new IOException("目录层级超过 64，请选择更小的目录");
        for(RemoteResourceInfo info:sftp.ls(relative.isEmpty()?base:base+"/"+relative)) {
            if(info.getName().equals(".")||info.getName().equals(".."))continue;
            String name=relative.isEmpty()?info.getName():relative+"/"+info.getName();
            validateRelative(name);
            FileMode.Type type=info.getAttributes().getType();
            if(type!=FileMode.Type.DIRECTORY && type!=FileMode.Type.REGULAR)throw new IOException("目录包含符号链接或特殊文件，无法通用传输："+name);
            if(tree.getItems().size()>=10000)throw new IOException("目录项超过 10000，请选择更小的目录");
            Item item=new Item();item.setPath(name);item.setDirectory(type==FileMode.Type.DIRECTORY);item.setSize(item.isDirectory()?0:info.getAttributes().getSize());
            tree.setBytes(tree.getBytes()+item.getSize());
            if(tree.getBytes()>limit())throw new IOException("文件夹超过传输上限 "+properties.getUploadMaxSizeMb()+" MB");
            tree.getItems().add(item);
            if(item.isDirectory())walk(sftp,base,name,tree,depth+1);
        }
    }
    public Path archive(String token,String path,boolean compress) throws IOException {
        SshSessionRegistry.SessionEntry session=sessions.get(token);
        Path zip=Files.createTempFile("doumi-folder-", ".zip");
        boolean success=false;
        try {
          try(SFTPClient sftp=session.getClient().newSFTPClient();ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))) {
            Tree tree=tree(sftp,SshSftpPathResolver.resolve(path,session.getDefaultCwd(),session.getHomeDir()));
            out.setLevel(compress?6:0);
            out.putNextEntry(new ZipEntry(tree.getName()+"/"));out.closeEntry();
            long total=0;
            for(Item item:tree.getItems()) {
                out.putNextEntry(new ZipEntry(tree.getName()+"/"+item.getPath()+(item.isDirectory()?"/":"")));
                if(!item.isDirectory()) {
                    String remote=tree.getPath()+"/"+item.getPath();
                    if(sftp.lstat(remote).getType()!=FileMode.Type.REGULAR)throw new IOException("文件在传输期间发生变化");
                    try(RemoteFile file=sftp.open(remote);InputStream in=file.new RemoteFileInputStream()) {
                        byte[] buffer=new byte[65536];int n;
                        while((n=in.read(buffer))!=-1){total+=n;if(total>limit())throw new IOException("文件夹超过传输大小限制");out.write(buffer,0,n);}
                    }
                }
                out.closeEntry();
            }
          }
          success=true;
        } finally {if(!success)Files.deleteIfExists(zip);}
        return zip;
    }
    public void uploadFile(String token,String path,String relative,MultipartFile file) throws IOException {
        validateRelative(relative);
        if(file.getSize()>limit())throw new IOException("文件超过传输大小限制");
        SshSessionRegistry.SessionEntry session=sessions.get(token);
        Path tmp=Files.createTempFile("doumi-folder-file-", ".tmp");
        try(SFTPClient sftp=session.getClient().newSFTPClient()) {
            String base=sftp.canonicalize(SshSftpPathResolver.resolveForList(path,session.getDefaultCwd(),session.getHomeDir()));
            prepareTarget(sftp,base,relative,false);
            file.transferTo(tmp);SftpStreams.put(sftp,tmp,base+"/"+relative);
        }finally{Files.deleteIfExists(tmp);}
    }
    public void uploadArchive(String token,String path,MultipartFile file) throws IOException {
        if(file.getSize()>limit())throw new IOException("压缩包超过传输大小限制");
        Path temp=Files.createTempDirectory("doumi-folder-upload-");
        try {
            List<String> entries=extract(file.getInputStream(),temp,limit());
            SshSessionRegistry.SessionEntry session=sessions.get(token);
            try(SFTPClient sftp=session.getClient().newSFTPClient()) {
                String base=sftp.canonicalize(SshSftpPathResolver.resolveForList(path,session.getDefaultCwd(),session.getHomeDir()));
                // Check every target before writing any file, including preexisting symlink ancestors.
                for(String name:entries)checkTarget(sftp,base,name);
                for(String name:entries) {
                    Path local=temp.resolve(name);
                    boolean dir=Files.isDirectory(local);
                    prepareTarget(sftp,base,name,dir);
                    if(!dir)SftpStreams.put(sftp,local,base+"/"+name);
                }
            }
        }finally{
            try(java.util.stream.Stream<Path> paths=Files.walk(temp)) {
                for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);
            }
        }
    }
    static List<String> extract(InputStream input,Path root,long max) throws IOException {
        List<String> names=new ArrayList<>();Set<String> seen=new HashSet<>();long total=0;
        try(ZipInputStream zip=new ZipInputStream(input)) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();if(entry.isDirectory())name=name.substring(0,name.length()-1);
                validateRelative(name);
                if(!seen.add(name))throw new IOException("压缩包包含重复路径");
                if(names.size()>=10000)throw new IOException("压缩包目录项超过 10000");
                Path target=root.resolve(name);
                if(entry.isDirectory())Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    try(OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer=new byte[65536];int n;
                        while((n=zip.read(buffer))!=-1){total+=n;if(total>max)throw new IOException("解压后的文件夹超过传输大小限制");out.write(buffer,0,n);}
                    }
                }
                names.add(name);
            }
        }
        if(names.isEmpty())throw new IOException("请选择包含文件夹内容的 ZIP 压缩包");
        return names;
    }
    static void validateRelative(String name) throws IOException {
        if(name==null||name.isEmpty()||name.length()>4096||name.contains("\\")||name.indexOf('\0')>=0||name.contains(":"))throw new IOException("无效的相对路径");
        String[] parts=name.split("/",-1);
        if(parts.length>65)throw new IOException("目录层级超过 64");
        for(String part:parts) {
            if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("路径必须位于选择的文件夹内");
            if(part.endsWith(".")||part.endsWith(" ")||part.matches(".*[\\x00-\\x1f<>\"|?*].*")||part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))
                throw new IOException("文件名无法跨平台传输："+part);
        }
    }
    private static void checkTarget(SFTPClient sftp,String base,String name) throws IOException {
        String current=base;
        String[] parts=name.split("/");
        for(int i=0;i<parts.length;i++) {
            current+="/"+parts[i];FileAttributes attrs;
            try {attrs=sftp.lstat(current);}catch(SFTPException e){if(e.getStatusCode()==Response.StatusCode.NO_SUCH_FILE)continue;throw e;}
            if(attrs.getType()==FileMode.Type.SYMLINK||i<parts.length-1&&attrs.getType()!=FileMode.Type.DIRECTORY)throw new IOException("目标路径包含符号链接或非目录："+current);
        }
    }
    private static void prepareTarget(SFTPClient sftp,String base,String name,boolean directory) throws IOException {
        checkTarget(sftp,base,name);
        int slash=name.lastIndexOf('/');
        if(directory)sftp.mkdirs(base+"/"+name);
        else if(slash>=0)sftp.mkdirs(base+"/"+name.substring(0,slash));
    }
    @Data public static class Tree {private String name,path;private long bytes;private List<Item> items=new ArrayList<>();}
    @Data public static class Item {private String path;private boolean directory;private long size;}
}
