package com.doumiot.sshconsole.ai;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.*;
import com.doumiot.sshconsole.sftp.SshSftpPathResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import net.schmizz.sshj.sftp.*;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service @SshConsoleEnabled
public class AiFiles {
    private static final long TTL = 2 * 60 * 60 * 1000L;
    private static final int TOTAL_BYTES = 50 * 1024 * 1024, FILE_BYTES = 20 * 1024 * 1024;
    @Resource private AiConnections connections;
    @Resource private SshUploadService uploads;
    @Resource private SshSftpService sftpService;
    @Resource private SshFolderTransferService folders;
    private final Map<String,Attachment> attachments = new ConcurrentHashMap<>();
    private final Map<String,Download> downloads = new ConcurrentHashMap<>();
    private static class Attachment {
        String id, owner, name; Path file; long size, expires;
    }
    private static class Download {
        String owner, name, remote; byte[] bytes; long accountId, expires; boolean folder, compress;
    }
    public synchronized JsonNode add(String owner, List<MultipartFile> files, List<String> paths) throws IOException {
        cleanup();
        if (files == null || files.isEmpty() || files.size() > 1000 || paths == null || paths.size() != files.size())
            throw new IllegalArgumentException("请选择不超过 1000 个文件");
        long total = attachments.values().stream().filter(a -> a.owner.equals(owner)).mapToLong(a -> a.size).sum();
        long global = attachments.values().stream().mapToLong(a -> a.size).sum();
        List<String> normalized = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            long size = files.get(i).getSize();
            if (size > FILE_BYTES) throw new IllegalArgumentException("AI 附件单文件上限 20 MB，大文件请使用文件管理上传");
            total += size; global += size; normalized.add(relativePath(paths.get(i)));
        }
        if (total > TOTAL_BYTES || global > 200L * 1024 * 1024 || attachments.size() + files.size() > 2000)
            throw new IllegalArgumentException("AI 附件超过容量上限：单会话 50 MB，请清空会话或减少文件");
        ArrayNode result = AiJson.JSON.createArrayNode();
        List<Attachment> added = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                Attachment value = new Attachment(); value.id = UUID.randomUUID().toString(); value.owner = owner;
                value.name = normalized.get(i); value.size = files.get(i).getSize(); value.expires = System.currentTimeMillis() + TTL;
                value.file = Files.createTempFile("doumi-ai-", ".attachment"); added.add(value);
                files.get(i).transferTo(value.file); attachments.put(value.id, value);
                result.add(AiJson.object("id",value.id,"name",value.name,"size",value.size,"text",isText(value.name)));
            }
        } catch (IOException | RuntimeException e) {
            for (Attachment a : added) { attachments.remove(a.id); Files.deleteIfExists(a.file); }
            throw e;
        }
        return result;
    }
    private Attachment require(String owner, String id) {
        Attachment value = attachments.get(id);
        if (value == null || !value.owner.equals(owner) || value.expires < System.currentTimeMillis()) throw new IllegalArgumentException("附件不存在或已过期，请重新选择文件");
        return value;
    }
    JsonNode context(String owner, List<String> ids) throws IOException {
        ArrayNode result = AiJson.JSON.createArrayNode(); int remaining = 128 * 1024;
        if (ids == null || ids.size() > 1000) throw new IllegalArgumentException("附件列表无效");
        for (String id : ids) {
            Attachment a = require(owner,id);
            com.fasterxml.jackson.databind.node.ObjectNode info = AiJson.object("attachmentId",id,"path",a.name,"bytes",a.size);
            if (isText(a.name) && remaining > 0) {
                String text = read(owner,id,0,Math.min(remaining, 8192)).path("content").asText();
                remaining -= text.length(); info.put("content",text); info.put("truncated",a.size > text.getBytes(StandardCharsets.UTF_8).length);
            }
            result.add(info);
        }
        return result;
    }
    JsonNode read(String owner, String id, int offset, int limit) throws IOException {
        Attachment a = require(owner,id);
        if (!isText(a.name)) throw new IllegalArgumentException("此附件按二进制文件处理，可通过上传工具传至服务器");
        if (offset < 0 || offset > 2 * 1024 * 1024 || limit < 1 || limit > 32768) throw new IllegalArgumentException("读取范围无效");
        try (Reader reader = Files.newBufferedReader(a.file,StandardCharsets.UTF_8)) {
            long skipped = 0;
            while (skipped < offset) { long count = reader.skip(offset - skipped); if (count == 0) break; skipped += count; }
            char[] buffer = new char[limit]; int count = reader.read(buffer);
            return AiJson.object("path",a.name,"offset",offset,"content",count < 0 ? "" : new String(buffer,0,count),"hasMore",reader.read() >= 0);
        } catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("附件不是 UTF-8 文本，请先转换编码"); }
    }
    Object upload(String owner, long accountId, String directory, String id, boolean preserveDirectories, boolean archive) throws IOException {
        Attachment a = require(owner,id); String token = connections.token(accountId);
        if (archive) { folders.uploadArchive(token,directory,new LocalFile(a)); }
        else if (preserveDirectories) { folders.uploadFile(token,directory,a.name,new LocalFile(a)); }
        else {
            String name = a.name.substring(a.name.lastIndexOf('/') + 1);
            String upload = uploads.start(token,directory,name,a.size);
            boolean complete = false;
            try (InputStream input = Files.newInputStream(a.file)) {
                long offset = 0; byte[] buffer = new byte[4 * 1024 * 1024];
                while (offset < a.size) {
                    int count = input.read(buffer);
                    if (count < 0) throw new IOException("附件长度发生变化");
                    uploads.chunk(token,upload,offset,new BytesFile(name,Arrays.copyOf(buffer,count))); offset += count;
                }
                uploads.complete(token,upload); complete = true;
            } finally { if (!complete) uploads.cancel(token,upload); }
        }
        return AiJson.object("uploaded",true,"name",a.name,"bytes",a.size);
    }
    Object remoteRead(long accountId, String path, int limit) throws IOException {
        if (limit < 1 || limit > 65536) throw new IllegalArgumentException("文本读取上限为 65536 字节");
        com.doumiot.sshconsole.ssh.SshSessionRegistry.SessionEntry entry = connections.session(accountId);
        String resolved = SshSftpPathResolver.resolve(path,entry.getDefaultCwd());
        try (SFTPClient sftp = entry.getClient().newSFTPClient()) {
            if (sftp.stat(resolved).getType() != FileMode.Type.REGULAR) throw new IllegalArgumentException("只能读取普通文本文件");
            try (RemoteFile file = sftp.open(resolved); InputStream input = file.new RemoteFileInputStream()) {
                byte[] bytes = new byte[limit]; int size = 0, count;
                while (size < limit && (count = input.read(bytes,size,limit-size)) > 0) size += count;
                for (int i=0;i<size;i++) if(bytes[i]==0) throw new IllegalArgumentException("文件包含二进制数据，请使用下载功能");
                return AiJson.object("path",resolved,"content",new String(bytes,0,size,StandardCharsets.UTF_8),"truncated",file.length() > size);
            }
        }
    }
    Object remoteWrite(long accountId, String path, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("文本写入上限 1 MB，请用附件上传较大文件");
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length()-1) throw new IllegalArgumentException("请提供包含文件名的远程绝对路径");
        String directory = slash == 0 ? "/" : path.substring(0,slash), name = path.substring(slash+1);
        String token = connections.token(accountId), upload = uploads.start(token,directory,name,bytes.length);
        boolean complete = false;
        try { if (bytes.length > 0) uploads.chunk(token,upload,0,new BytesFile(name,bytes)); uploads.complete(token,upload); complete = true; }
        finally { if (!complete) uploads.cancel(token,upload); }
        return AiJson.object("written",true,"path",path,"bytes",bytes.length);
    }
    Object export(String owner, Object archive) throws IOException {
        Download value = new Download(); value.owner = owner; value.name = "doumi-ai-backup.json";
        value.bytes = AiJson.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(archive);
        return remember(value);
    }
    Object remoteDownload(String owner, long accountId, String path, boolean folder, boolean compress) {
        Download value = new Download(); value.owner = owner; value.accountId = accountId; value.remote = path;
        value.folder = folder; value.compress = compress;
        value.name = path.substring(path.lastIndexOf('/')+1) + (folder ? ".zip" : "");
        return remember(value);
    }
    private Object remember(Download value) {
        cleanup();
        if (downloads.size() >= 100) throw new IllegalArgumentException("下载链接过多，请稍后重试");
        String id = UUID.randomUUID().toString(); value.expires = System.currentTimeMillis()+TTL; downloads.put(id,value);
        return AiJson.object("downloadUrl","/api/ssh-console/ai/downloads/"+id,"filename",value.name,"expiresInMinutes",120);
    }
    public ResponseEntity<InputStreamResource> download(String id) throws IOException {
        Download value = downloads.get(id);
        if (value == null || value.expires < System.currentTimeMillis()) return ResponseEntity.notFound().build();
        if (value.bytes != null) return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition","attachment; filename=doumi-ai-backup.json").cacheControl(CacheControl.noStore())
                .contentLength(value.bytes.length).body(new InputStreamResource(new ByteArrayInputStream(value.bytes)));
        String token = connections.token(value.accountId);
        if (!value.folder) return sftpService.streamDownload(token,value.remote);
        Path archive = folders.archive(token,value.remote,value.compress);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition","attachment; filename=folder.zip").contentLength(Files.size(archive))
                .body(new InputStreamResource(new FilterInputStream(Files.newInputStream(archive)) {
                    @Override public void close() throws IOException { try { super.close(); } finally { Files.deleteIfExists(archive); } }
                }));
    }
    synchronized void removeOwner(String owner) {
        attachments.values().removeIf(a -> {
            if (!a.owner.equals(owner)) return false;
            try { Files.deleteIfExists(a.file); } catch (IOException ignored) { a.expires = 0; return false; }
            return true;
        });
        downloads.values().removeIf(a -> a.owner.equals(owner));
    }
    @Scheduled(fixedDelay = 60000) public synchronized void cleanup() {
        long now = System.currentTimeMillis();
        attachments.values().removeIf(a -> {
            if (a.expires >= now) return false;
            try { Files.deleteIfExists(a.file); return true; } catch (IOException e) { return false; }
        });
        downloads.values().removeIf(d -> d.expires < now);
    }
    @PreDestroy void close() { new HashSet<>(attachments.values()).forEach(a -> removeOwner(a.owner)); }
    static String relativePath(String path) {
        if (path == null || path.isEmpty() || path.length()>4096) throw new IllegalArgumentException("附件路径无效");
        String normalized = path.replace('\\','/'); String[] parts = normalized.split("/",-1);
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*") || parts.length>65) throw new IllegalArgumentException("附件须使用不超过 64 层目录的相对路径");
        for (String part:parts) if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('\0')>=0) throw new IllegalArgumentException("附件路径无效");
        return normalized;
    }
    private static boolean isText(String name) { return name.toLowerCase(Locale.ROOT).matches(".*\\.(json|txt|md|csv|yaml|yml|ini|conf|config|xml|sh|py|js|properties|log|pem|key)$"); }
    private static class LocalFile implements MultipartFile {
        final Attachment value;
        LocalFile(Attachment value) { this.value = value; }
        public String getName(){return "file";} public String getOriginalFilename(){return value.name.substring(value.name.lastIndexOf('/')+1);}
        public String getContentType(){return "application/octet-stream";} public boolean isEmpty(){return value.size==0;} public long getSize(){return value.size;}
        public byte[] getBytes() throws IOException{return Files.readAllBytes(value.file);} public InputStream getInputStream() throws IOException{return Files.newInputStream(value.file);}
        public void transferTo(File file) throws IOException{Files.copy(value.file,file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    private static class BytesFile implements MultipartFile {
        final String name; final byte[] bytes;
        BytesFile(String name,byte[] bytes){this.name=name;this.bytes=bytes;}
        public String getName(){return "file";} public String getOriginalFilename(){return name;} public String getContentType(){return "application/octet-stream";}
        public boolean isEmpty(){return bytes.length==0;} public long getSize(){return bytes.length;} public byte[] getBytes(){return bytes;}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);} public void transferTo(File file) throws IOException{Files.write(file.toPath(),bytes);}
    }
}
