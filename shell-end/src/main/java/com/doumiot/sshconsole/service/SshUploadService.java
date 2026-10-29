package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.sftp.SshSftpPathResolver;
import com.doumiot.sshconsole.sftp.SftpStreams;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.sftp.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Each upload owns a temporary sibling; only a complete upload may replace the destination. */
@Slf4j
@Service
@SshConsoleEnabled
public class SshUploadService {
    private static final long IDLE_MS = 30 * 60 * 1000L;
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();
    @Resource private SshSessionRegistry sessionRegistry;

    public synchronized String start(String token, String path, String name, long total) throws IOException {
        return start(token, path, name, total, null, null);
    }

    public synchronized String start(String token, String path, String name, long total, Long ownerUid, Long ownerGid) throws IOException {
        SftpStreams.Ownership owner = SftpStreams.Ownership.of(ownerUid, ownerGid);
        if (!StringUtils.hasText(name) || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0
                || ".".equals(name) || "..".equals(name) || total < 0) throw new IOException("无效文件名或文件大小");
        if (uploads.size() >= 256) throw new IOException("未结束的上传过多，请稍后重试");
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(token);
        String dir = SshSftpPathResolver.resolveForList(path, entry.getDefaultCwd(), entry.getHomeDir());
        String id = UUID.randomUUID().toString();
        Upload upload = new Upload(token, entry, total,
                SshSftpPathResolver.resolveChildDir(dir, name, entry.getDefaultCwd(), entry.getHomeDir()),
                SshSftpPathResolver.resolveChildDir(dir, ".doumi-upload-" + id + ".part", entry.getDefaultCwd(), entry.getHomeDir()), owner);
        try (SFTPClient sftp = entry.getClient().newSFTPClient();
             RemoteFile ignored = SftpStreams.createTemporary(sftp, upload.temp)) {
            uploads.put(id, upload);
        }
        return id;
    }

    public void chunk(String token, String id, long offset, MultipartFile chunk) throws IOException {
        Upload upload = require(token, id);
        synchronized (upload) {
            checkActive(id, upload);
            long size = chunk.getSize();
            if (offset != upload.offset || size <= 0 || size > 8L * 1024 * 1024 || size > upload.total - offset)
                throw new IOException("分块偏移或大小不正确，请重新上传");
            upload.touched = System.currentTimeMillis();
            sessionRegistry.get(token);
            try (SFTPClient sftp = upload.entry.getClient().newSFTPClient();
                 RemoteFile remote = sftp.open(upload.temp, EnumSet.of(OpenMode.WRITE));
                 InputStream input = chunk.getInputStream()) {
                if (remote.length() != offset) throw new IOException("临时文件大小已改变，请重新上传");
                byte[] buffer = new byte[65536];
                long written = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > size - written) throw new IOException("分块大小不完整");
                    remote.write(offset + written, buffer, 0, read);
                    written += read;
                    sessionRegistry.touchSession(token);
                }
                if (written != size) throw new IOException("分块大小不完整");
                upload.offset += written;
            } catch (IOException | RuntimeException error) {
                upload.cancelled = true; // A partially written chunk cannot be retried at an ambiguous offset.
                throw error;
            } finally { upload.touched = System.currentTimeMillis(); }
        }
    }

    public void complete(String token, String id) throws IOException {
        completeResult(token, id);
    }

    public SftpStreams.UploadResult completeResult(String token, String id) throws IOException {
        Upload upload = require(token, id);
        synchronized (upload) {
            checkActive(id, upload);
            sessionRegistry.get(token);
            if (upload.offset != upload.total) throw new IOException("文件尚未上传完整");
            try (SFTPClient sftp = upload.entry.getClient().newSFTPClient()) {
                if (sftp.size(upload.temp) != upload.total) throw new IOException("临时文件大小不一致");
                SftpStreams.UploadResult result = SftpStreams.commit(sftp, upload.temp, upload.target, upload.owner);
                uploads.remove(id, upload);
                return result;
            } finally { upload.touched = System.currentTimeMillis(); }
        }
    }

    public void cancel(String token, String id) throws IOException {
        Upload upload = uploads.get(id);
        if (upload == null) return; // Idempotent, including cancellation after a completed request.
        if (!upload.token.equals(token)) throw new IOException("上传任务不属于当前会话");
        synchronized (upload) {
            if (uploads.get(id) != upload) return;
            upload.cancelled = true;
            cleanup(id, upload);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        uploads.forEach((id, upload) -> {
            if (!upload.cancelled && now - upload.touched < IDLE_MS) return;
            synchronized (upload) {
                if (uploads.get(id) != upload || (!upload.cancelled && now - upload.touched < IDLE_MS)) return;
                upload.cancelled = true;
                try { cleanup(id, upload); }
                catch (IOException | RuntimeException error) {
                    // Disconnected SSH connections cannot remove remote files. Bound retained state to one day.
                    if (now - upload.touched > 24 * 60 * 60 * 1000L) uploads.remove(id, upload);
                    log.debug("清理上传临时文件失败: {}", error.getMessage());
                }
            }
        });
    }

    private void cleanup(String id, Upload upload) throws IOException {
        try (SFTPClient sftp = upload.entry.getClient().newSFTPClient()) {
            if (sftp.statExistence(upload.temp) != null) sftp.rm(upload.temp);
            uploads.remove(id, upload);
        }
    }

    private Upload require(String token, String id) throws IOException {
        Upload upload = uploads.get(id);
        if (upload == null || !upload.token.equals(token)) throw new IOException("上传任务不存在或已结束");
        return upload;
    }

    private void checkActive(String id, Upload upload) throws IOException {
        if (upload.cancelled || uploads.get(id) != upload) throw new IOException("上传任务已取消或结束");
    }

    private static final class Upload {
        final String token, target, temp;
        final SshSessionRegistry.SessionEntry entry;
        final long total;
        final SftpStreams.Ownership owner;
        long offset;
        volatile long touched = System.currentTimeMillis();
        volatile boolean cancelled;
        Upload(String token, SshSessionRegistry.SessionEntry entry, long total, String target, String temp, SftpStreams.Ownership owner) {
            this.token = token; this.entry = entry; this.total = total; this.target = target; this.temp = temp; this.owner = owner;
        }
    }
}
