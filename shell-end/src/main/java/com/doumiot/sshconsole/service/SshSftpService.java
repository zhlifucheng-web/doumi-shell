package com.doumiot.sshconsole.service;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.config.SshConsoleProperties;
import com.doumiot.sshconsole.sftp.SshSftpAttributeFormatter;
import com.doumiot.sshconsole.sftp.SshSftpPathResolver;
import com.doumiot.sshconsole.sftp.SftpStreams;
import com.doumiot.sshconsole.ssh.SshRemoteExec;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.SFTPException;
import org.springframework.stereotype.Service;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@SshConsoleEnabled
@Service
public class SshSftpService {

    @Resource
    private SshSessionRegistry sessionRegistry;
    @Resource
    private SshConsoleProperties properties;

    /** Directory picker: only list real directories; default to the SSH user's home. */
    public ListResult directories(String sessionToken, String path) throws IOException {
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        try (SFTPClient sftp = openSftp(entry)) {
            String requested = StringUtils.hasText(path) ? path :
                    (StringUtils.hasText(entry.getDefaultCwd()) ? entry.getDefaultCwd() : sftp.canonicalize("."));
            String resolved = SshSftpPathResolver.resolve(requested, entry.getDefaultCwd());
            List<FileEntry> items = new ArrayList<>();
            for (RemoteResourceInfo info : sftp.ls(resolved)) {
                if (!info.isDirectory() || ".".equals(info.getName()) || "..".equals(info.getName())) continue;
                FileEntry row = new FileEntry(); row.setName(info.getName()); row.setDir(true); items.add(row);
            }
            items.sort(Comparator.comparing(FileEntry::getName));
            ListResult result = new ListResult(); result.setPath(resolved); result.setItems(items); return result;
        }
    }

    public ListResult list(String sessionToken, String path) throws IOException {
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        String resolved = SshSftpPathResolver.resolveForList(path, entry.getDefaultCwd());
        // exec 与 SFTP 不宜同连接并发；先拉取属主/属组名再 ls
        Map<String, String[]> ownerByName = loadOwnerMetadata(entry, resolved);
        IdNameMaps idNames = loadIdNameMaps(entry);
        try (SFTPClient sftp = openSftp(entry)) {
            List<RemoteResourceInfo> infos = sftp.ls(resolved);
            List<FileEntry> items = new ArrayList<>();
            for (RemoteResourceInfo info : infos) {
                String name = info.getName();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                FileEntry fe = new FileEntry();
                fe.setName(name);
                fe.setDir(info.isDirectory());
                FileAttributes attrs = info.getAttributes();
                fe.setSize(attrs != null ? attrs.getSize() : 0L);
                fe.setMtime(attrs != null ? attrs.getMtime() : 0L);
                boolean symlink = attrs != null && attrs.getType() == FileMode.Type.SYMLINK;
                String[] meta = ownerByName.get(name);
                if (meta != null) {
                    fe.setOwner(meta[0]);
                    fe.setGroup(meta[1]);
                    int mask = SshSftpAttributeFormatter.parseOctalMode(meta[2]);
                    fe.setPermissions(SshSftpAttributeFormatter.formatPermissionMask(mask, info.isDirectory(), symlink));
                } else {
                    int uid = attrs != null ? attrs.getUID() : -1;
                    int gid = attrs != null ? attrs.getGID() : -1;
                    fe.setOwner(SshSftpAttributeFormatter.resolveIdName(uid, idNames.uidToName));
                    fe.setGroup(SshSftpAttributeFormatter.resolveIdName(gid, idNames.gidToName));
                    fe.setPermissions(SshSftpAttributeFormatter.formatPermissions(attrs, info.isDirectory(), symlink));
                }
                items.add(fe);
            }
            items.sort(Comparator.comparing(FileEntry::isDir).reversed().thenComparing(FileEntry::getName));
            ListResult result = new ListResult();
            result.setPath(resolved);
            result.setItems(items);
            return result;
        }
    }

    /** The response owns these resources until its stream is closed. */
    public ResponseEntity<InputStreamResource> streamDownload(String token, String path) throws IOException {
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(token);
        String resolved = SshSftpPathResolver.resolve(path, entry.getDefaultCwd());
        SFTPClient sftp = openSftp(entry);
        RemoteFile remote = null;
        try {
            if (sftp.type(resolved) != FileMode.Type.REGULAR) throw new IOException("仅支持下载普通文件");
            remote = sftp.open(resolved);
            long size = remote.length();
            final RemoteFile opened = remote;
            InputStream stream = new FilterInputStream(remote.new RemoteFileInputStream()) {
                private boolean closed;
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    sessionRegistry.touchSession(token);
                    return super.read(bytes, offset, length);
                }
                @Override public synchronized void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    try { super.close(); } finally { try { opened.close(); } finally { sftp.close(); } }
                }
            };
            String name = URLEncoder.encode(fileNameOf(resolved), "UTF-8").replace("+", "%20");
            return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + name)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size).body(new InputStreamResource(stream));
        } catch (IOException | RuntimeException e) {
            try { if (remote != null) remote.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            try { sftp.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    public void upload(String sessionToken, String path, MultipartFile file) throws IOException {
        if (file == null || !StringUtils.hasText(file.getOriginalFilename())) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "请选择要上传的文件");
        }
        long max = properties.getUploadMaxSizeMb() * 1024L * 1024L;
        if (file.getSize() > max) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY,
                    "文件超过大小限制 " + properties.getUploadMaxSizeMb() + " MB");
        }
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        String dir = SshSftpPathResolver.resolveForList(path, entry.getDefaultCwd());
        String original = file.getOriginalFilename();
        String name = StringUtils.hasText(original) ? original.trim() : "upload.bin";
        if (name.contains("/") || name.contains("\\") || "..".equals(name)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "无效的文件名");
        }
        String remote = dir.endsWith("/") ? dir + name : dir + "/" + name;
        remote = SshSftpPathResolver.resolve(remote, entry.getDefaultCwd());
        Path tmp = Files.createTempFile("ssh-sftp-ul-", ".part");
        try (SFTPClient sftp = openSftp(entry)) {
            file.transferTo(tmp);
            SftpStreams.put(sftp, tmp, remote);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public void mkdir(String sessionToken, String path) throws IOException {
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        String resolved = SshSftpPathResolver.resolve(path, entry.getDefaultCwd());
        try (SFTPClient sftp = openSftp(entry)) {
            try {
                sftp.mkdirs(resolved);
            } catch (SFTPException e) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY,
                        "创建目录失败: " + e.getMessage());
            }
        }
    }

    public void delete(String sessionToken, String path) throws IOException {
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        String resolved = SshSftpPathResolver.resolve(path, entry.getDefaultCwd());
        try (SFTPClient sftp = openSftp(entry)) {
            try {
                net.schmizz.sshj.sftp.FileMode.Type type = sftp.type(resolved);
                if (type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
                    sftp.rmdir(resolved);
                } else {
                    sftp.rm(resolved);
                }
            } catch (SFTPException e) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY,
                        "删除失败（目录须为空）: " + e.getMessage());
            }
        }
    }

    public void rename(String sessionToken, String path, String newName) throws IOException {
        if (!StringUtils.hasText(newName)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "新名称不能为空");
        }
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        String resolved = SshSftpPathResolver.resolve(path, entry.getDefaultCwd());
        int slash = resolved.lastIndexOf('/');
        String parent = slash >= 0 ? resolved.substring(0, slash) : "/";
        if (parent.isEmpty()) {
            parent = "/";
        }
        String target = SshSftpPathResolver.resolveChildDir(parent, newName.trim(), entry.getDefaultCwd());
        try (SFTPClient sftp = openSftp(entry)) {
            try {
                sftp.rename(resolved, target);
            } catch (SFTPException e) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY,
                        "重命名失败: " + e.getMessage());
            }
        }
    }

    private static SFTPClient openSftp(SshSessionRegistry.SessionEntry entry) throws IOException {
        if (entry.getClient() == null || !entry.getClient().isConnected()) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "SSH 连接已断开");
        }
        return entry.getClient().newSFTPClient();
    }

    private static String fileNameOf(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private Map<String, String[]> loadOwnerMetadata(SshSessionRegistry.SessionEntry entry, String resolved) {
        String quoted = SshSftpAttributeFormatter.shellSingleQuote(resolved);
        String[] commands = {
                "LC_ALL=C find " + quoted + " -mindepth 1 -maxdepth 1 -printf '%f\\t%U\\t%G\\t%#m\\n' 2>/dev/null",
                "LC_ALL=C sh -c 'cd " + quoted + " && for f in * .[!.]* ..?*; do "
                        + "[ -e \"$f\" ] || continue; stat -c \"%f\\t%U\\t%G\\t%a\" \"$f\"; done' 2>/dev/null"
        };
        for (String cmd : commands) {
            Map<String, String[]> map = execOwnerLines(entry, cmd);
            if (!map.isEmpty()) {
                return map;
            }
        }
        return Collections.emptyMap();
    }

    private Map<String, String[]> execOwnerLines(SshSessionRegistry.SessionEntry entry, String cmd) {
        try {
            SshRemoteExec.ExecResult result = SshRemoteExec.exec(entry.getClient(), entry.getExecLock(), cmd, 25);
            if (!StringUtils.hasText(result.getStdout())) {
                return Collections.emptyMap();
            }
            return SshSftpAttributeFormatter.parseFindOwnerLines(result.getStdout());
        } catch (IOException e) {
            log.debug("SFTP 列表属主命令失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private IdNameMaps loadIdNameMaps(SshSessionRegistry.SessionEntry entry) {
        IdNameMaps maps = new IdNameMaps();
        try {
            SshRemoteExec.ExecResult pw = SshRemoteExec.exec(
                    entry.getClient(), entry.getExecLock(),
                    "getent passwd 2>/dev/null | awk -F: '{print $3\":\"$1}'", 20);
            maps.uidToName = SshSftpAttributeFormatter.parseIdNameLines(pw.getStdout());
            SshRemoteExec.ExecResult gr = SshRemoteExec.exec(
                    entry.getClient(), entry.getExecLock(),
                    "getent group 2>/dev/null | awk -F: '{print $3\":\"$1}'", 20);
            maps.gidToName = SshSftpAttributeFormatter.parseIdNameLines(gr.getStdout());
        } catch (IOException e) {
            log.debug("getent 解析属主/属组失败: {}", e.getMessage());
        }
        return maps;
    }

    private static final class IdNameMaps {
        private Map<Integer, String> uidToName = Collections.emptyMap();
        private Map<Integer, String> gidToName = Collections.emptyMap();
    }

    @Data
    public static class FileEntry {
        private String name;
        private boolean dir;
        private long size;
        /** Unix epoch seconds */
        private long mtime;
        /** 属主用户名（无法解析时为 uid 数字） */
        private String owner;
        /** 属组名（无法解析时为 gid 数字） */
        private String group;
        /** ls 风格权限，如 -rw-r--r-- */
        private String permissions;
    }

    @Data
    public static class ListResult {
        private String path;
        private List<FileEntry> items;
    }

}
