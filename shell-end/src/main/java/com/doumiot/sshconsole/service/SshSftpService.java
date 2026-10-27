package com.doumiot.sshconsole.service;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.sftp.FileTailRecoveryException;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
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
            String home = resolveHomeDir(sftp, entry);
            String requested = StringUtils.hasText(path) ? path : home;
            String resolved = SshSftpPathResolver.resolve(requested, entry.getDefaultCwd(), home);
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
        List<RemoteResourceInfo> infos;
        String resolved;
        try (SFTPClient sftp = openSftp(entry)) {
            String home = needHome(path) ? resolveHomeDir(sftp, entry) : (entry != null ? entry.getHomeDir() : null);
            resolved = SshSftpPathResolver.resolveForList(path, entry != null ? entry.getDefaultCwd() : null, home);
            infos = sftp.ls(resolved);
        }
        // Use SFTP attributes as the source of truth, including for unusual filenames.
        // Finish the SFTP request before using separate exec channels for name resolution.
        IdNameMaps idNames = loadIdNameMaps(entry, infos);
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
            long mtime = attrs != null ? attrs.getMtime() : 0L;
            long atime = attrs != null ? attrs.getAtime() : 0L;
            fe.setMtime(mtime);
            fe.setAtime(atime);
            fe.setCreateTime(atime > 0 ? atime : mtime);
            boolean symlink = attrs != null && attrs.getType() == FileMode.Type.SYMLINK;
            boolean hasIds = attrs != null && attrs.has(FileAttributes.Flag.UIDGID);
            Integer uid = hasIds ? attrs.getUID() : null;
            Integer gid = hasIds ? attrs.getGID() : null;
            fe.setOwner(SshSftpAttributeFormatter.resolveIdName(uid, idNames.uidToName));
            fe.setGroup(SshSftpAttributeFormatter.resolveIdName(gid, idNames.gidToName));
            fe.setPermissions(SshSftpAttributeFormatter.formatPermissions(attrs, info.isDirectory(), symlink));
            items.add(fe);
        }
        items.sort(Comparator.comparing(FileEntry::isDir).reversed().thenComparing(FileEntry::getName));
        ListResult result = new ListResult();
        result.setPath(resolved);
        result.setItems(items);
        return result;
    }

    /** The response owns these resources until its stream is closed. */
    public ResponseEntity<InputStreamResource> streamDownload(String token, String path) throws IOException {
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(token);
        SFTPClient sftp = openSftp(entry);
        String home = needHome(path) ? resolveHomeDir(sftp, entry) : (entry != null ? entry.getHomeDir() : null);
        String resolved = SshSftpPathResolver.resolve(path, entry != null ? entry.getDefaultCwd() : null, home);
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

    /**
     * 流式分块读取大文件片段：支持从文件头向后读，或从文件末尾（Tail 模式）向前读。
     *
     * @param sessionToken 会话凭证
     * @param path         远程文件路径
     * @param offset       字节偏移量（若 fromEnd 为 true 或 offset < 0，则从文件末尾倒数计算）
     * @param length       需要读取的字节数（默认 256KB，单次上限 2MB）
     * @param fromEnd      是否从文件末尾开始读取（适用于查看最新日志）
     * @return 分块读取结果（包含总大小、当前分块起止与 Base64 编码的字节数据）
     */
    public ChunkResult readChunk(String sessionToken, String path, long offset, int length, boolean fromEnd) throws IOException {
        if (length <= 0) {
            length = 256 * 1024;
        }
        // 单次分片上限保护：最高 2MB
        length = Math.min(length, 2 * 1024 * 1024);

        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        try (SFTPClient sftp = openSftp(entry)) {
            String home = needHome(path) ? resolveHomeDir(sftp, entry) : (entry != null ? entry.getHomeDir() : null);
            String resolved = SshSftpPathResolver.resolve(path, entry != null ? entry.getDefaultCwd() : null, home);
            if (sftp.type(resolved) != FileMode.Type.REGULAR) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "仅支持读取普通文件");
            }
            try (RemoteFile remote = sftp.open(resolved)) {
                long totalSize = remote.length();
                long startOffset;
                if (fromEnd || offset < 0) {
                    startOffset = Math.max(0, totalSize - length);
                } else {
                    startOffset = Math.min(Math.max(0, offset), totalSize);
                }
                int toRead = (int) Math.min((long) length, totalSize - startOffset);
                byte[] buf = new byte[toRead];
                int totalRead = 0;
                while (totalRead < toRead) {
                    int r = remote.read(startOffset + totalRead, buf, totalRead, toRead - totalRead);
                    if (r <= 0) {
                        break;
                    }
                    totalRead += r;
                }
                byte[] actualBytes = totalRead == toRead ? buf : Arrays.copyOf(buf, totalRead);
                ChunkResult result = new ChunkResult();
                result.setTotalSize(totalSize);
                result.setOffset(startOffset);
                result.setLength(actualBytes.length);
                result.setHasMoreBefore(startOffset > 0);
                result.setHasMoreAfter(startOffset + actualBytes.length < totalSize);
                result.setContentBase64(Base64.getEncoder().encodeToString(actualBytes));
                sessionRegistry.touchSession(sessionToken);
                return result;
            }
        }
    }

    /** Authenticated regular-file reader, owned and closed by one HTTP request or file Tail. */
    public ReadFileHandle openReadHandle(String token, String path) throws IOException {
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(token);
        SFTPClient sftp = openSftp(entry);
        RemoteFile remote = null;
        try {
            if (sftp.getSFTPEngine() != null) sftp.getSFTPEngine().setTimeoutMs(5000);
            String home = needHome(path) ? resolveHomeDir(sftp, entry) : entry.getHomeDir();
            String resolved = SshSftpPathResolver.resolve(path, entry.getDefaultCwd(), home);
            if (sftp.type(resolved) != FileMode.Type.REGULAR) throw new IOException("仅支持读取普通文件");
            remote = sftp.open(resolved);
            return new ReadFileHandle(resolved, sftp, remote);
        } catch (IOException | RuntimeException error) {
            if (remote != null) try { remote.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            try { sftp.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            if (error instanceof SFTPException && missingPath((SFTPException)error))
                throw new FileTailRecoveryException("FILE_CHANGED", "文件路径暂时不存在，等待轮转后的文件");
            throw error;
        }
    }

    private static boolean missingPath(SFTPException error) {
        return error.getStatusCode() == net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE || error.getStatusCode() == net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_PATH;
    }

    <T> T withReadFile(String token, String path, ReadFileAction<T> action) throws IOException {
        try (ReadFileHandle handle = openReadHandle(token, path)) {
            T result = action.read(handle.path, handle.remote, handle.attributes());
            sessionRegistry.touchSession(token);
            return result;
        }
    }
    interface ReadFileAction<T> {
        T read(String resolvedPath, RemoteFile remote, FileAttributes attrs) throws IOException;
    }
    public static final class ReadFileHandle implements java.io.Closeable {
        private final String path;
        private final SFTPClient sftp;
        private final RemoteFile remote;
        private boolean closed;
        private long divergentRemoteSize = -1;
        private int divergentReads;
        private ReadFileHandle(String path, SFTPClient sftp, RemoteFile remote) { this.path=path; this.sftp=sftp; this.remote=remote; }
        public String path() { return path; }
        public long length() throws IOException { return remote.length(); }
        public FileAttributes attributes() throws IOException {
            try { return sftp.stat(path); }
            catch (SFTPException error) {
                if (missingPath(error))
                    throw new FileTailRecoveryException("FILE_CHANGED", "文件路径暂时不存在，等待轮转后的文件");
                throw error;
            }
        }
        /** Retry a racing append; consistently read the smaller observed snapshot while a writer remains active. */
        public ReadSnapshot snapshot() throws IOException {
            FileAttributes attrs = attributes(); long remoteSize = length();
            if (attrs.getSize() != remoteSize) { attrs = attributes(); remoteSize = length(); }
            boolean stable = attrs.getSize() == remoteSize;
            if (!stable) {
                divergentReads = remoteSize == divergentRemoteSize ? divergentReads + 1 : 1;
                divergentRemoteSize = remoteSize;
                if (divergentReads >= 3) throw new FileTailRecoveryException("FILE_CHANGED", "文件路径与打开的文件不一致，正在重新读取");
            } else { divergentReads = 0; divergentRemoteSize = -1; }
            return new ReadSnapshot(Math.min(attrs.getSize(), remoteSize), attrs.getMtime(), stable);
        }
        public int read(long offset, byte[] bytes, int begin, int length) throws IOException { return remote.read(offset,bytes,begin,length); }
        @Override public synchronized void close() throws IOException {
            if (closed) return; closed=true;
            try { remote.close(); } finally { sftp.close(); }
        }
    }

    @lombok.Value public static class ReadSnapshot { long size; long mtime; boolean stable; }

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
        Path tmp = Files.createTempFile("ssh-sftp-ul-", ".part");
        try (SFTPClient sftp = openSftp(entry)) {
            String home = needHome(path) ? resolveHomeDir(sftp, entry) : (entry != null ? entry.getHomeDir() : null);
            String dir = SshSftpPathResolver.resolveForList(path, entry != null ? entry.getDefaultCwd() : null, home);
            String original = file.getOriginalFilename();
            String name = StringUtils.hasText(original) ? original.trim() : "upload.bin";
            if (name.contains("/") || name.contains("\\") || "..".equals(name)) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "无效的文件名");
            }
            String remote = dir.endsWith("/") ? dir + name : dir + "/" + name;
            remote = SshSftpPathResolver.resolve(remote, entry != null ? entry.getDefaultCwd() : null, home);
            file.transferTo(tmp);
            SftpStreams.put(sftp, tmp, remote);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public void mkdir(String sessionToken, String path) throws IOException {
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        try (SFTPClient sftp = openSftp(entry)) {
            String home = needHome(path) ? resolveHomeDir(sftp, entry) : (entry != null ? entry.getHomeDir() : null);
            String resolved = SshSftpPathResolver.resolve(path, entry != null ? entry.getDefaultCwd() : null, home);
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
        try (SFTPClient sftp = openSftp(entry)) {
            String home = needHome(path) ? resolveHomeDir(sftp, entry) : (entry != null ? entry.getHomeDir() : null);
            String resolved = SshSftpPathResolver.resolve(path, entry != null ? entry.getDefaultCwd() : null, home);
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
        try (SFTPClient sftp = openSftp(entry)) {
            String home = needHome(path) ? resolveHomeDir(sftp, entry) : (entry != null ? entry.getHomeDir() : null);
            String resolved = SshSftpPathResolver.resolve(path, entry != null ? entry.getDefaultCwd() : null, home);
            int slash = resolved.lastIndexOf('/');
            String parent = slash >= 0 ? resolved.substring(0, slash) : "/";
            if (parent.isEmpty()) {
                parent = "/";
            }
            String target = SshSftpPathResolver.resolveChildDir(parent, newName.trim(), entry != null ? entry.getDefaultCwd() : null, home);
            try {
                sftp.rename(resolved, target);
            } catch (SFTPException e) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY,
                        "重命名失败: " + e.getMessage());
            }
        }
    }

    public void chown(String sessionToken, String path, String owner, String group, boolean recursive) throws IOException {
        String cleanOwner = owner != null ? owner.trim() : "";
        String cleanGroup = group != null ? group.trim() : "";

        if (!StringUtils.hasText(cleanOwner) && !StringUtils.hasText(cleanGroup)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "请至少指定用户或用户组");
        }
        String pattern = "^[a-zA-Z0-9_.][a-zA-Z0-9_.-]*$";
        if (StringUtils.hasText(cleanOwner) && !cleanOwner.matches(pattern)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "无效的用户名格式");
        }
        if (StringUtils.hasText(cleanGroup) && !cleanGroup.matches(pattern)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "无效的用户组格式");
        }

        SystemUsersAndGroups ug = systemUsersAndGroups(sessionToken);
        if (!ug.isCanChown()) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "当前用户无权限修改文件所有者或无法查看其他用户");
        }

        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        String home;
        try (SFTPClient sftp = openSftp(entry)) {
            home = needHome(path) ? resolveHomeDir(sftp, entry) : (entry != null ? entry.getHomeDir() : null);
        }
        String resolved = SshSftpPathResolver.resolve(path, entry != null ? entry.getDefaultCwd() : null, home);

        StringBuilder spec = new StringBuilder();
        if (StringUtils.hasText(cleanOwner)) {
            spec.append(cleanOwner);
            if (StringUtils.hasText(cleanGroup)) {
                spec.append(':').append(cleanGroup);
            }
        } else {
            spec.append(':').append(cleanGroup);
        }

        StringBuilder cmd = new StringBuilder();
        cmd.append("chown ");
        if (recursive) {
            cmd.append("-R ");
        }
        cmd.append(spec).append(" ").append(quote(resolved));

        int timeoutSec = recursive ? 120 : 30;
        SshRemoteExec.ExecResult result = SshRemoteExec.exec(
                entry.getClient(), entry.getExecLock(), cmd.toString(), timeoutSec);

        if (result.getExitStatus() != 0) {
            String err = StringUtils.hasText(result.getStderr()) ? result.getStderr().trim() : result.getStdout().trim();
            if (!StringUtils.hasText(err)) {
                err = "命令退出码: " + result.getExitStatus();
            }
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "修改归属用户失败: " + err);
        }
    }

    public SystemUsersAndGroups systemUsersAndGroups(String sessionToken) throws IOException {
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        String command = "echo '===ID==='; "
                + "id -u 2>/dev/null || true; "
                + "id -un 2>/dev/null || true; "
                + "echo '===USERS==='; "
                + "if command -v getent >/dev/null 2>&1; then getent passwd; else cat /etc/passwd; fi 2>/dev/null | awk -F: '$1 ~ /^[a-zA-Z0-9_.-]+$/ {print $1}'; "
                + "echo '===GROUPS==='; "
                + "if command -v getent >/dev/null 2>&1; then getent group; else cat /etc/group; fi 2>/dev/null | awk -F: '$1 ~ /^[a-zA-Z0-9_.-]+$/ {print $1}'";

        SystemUsersAndGroups result = new SystemUsersAndGroups();
        try {
            SshRemoteExec.ExecResult exec = SshRemoteExec.exec(
                    entry.getClient(), entry.getExecLock(), command, 8);
            if (exec.getExitStatus() == 0 && StringUtils.hasText(exec.getStdout())) {
                String[] lines = exec.getStdout().split("\r?\n");
                String section = "";
                List<String> idLines = new ArrayList<>();
                Set<String> userSet = new LinkedHashSet<>();
                Set<String> groupSet = new LinkedHashSet<>();
                for (String line : lines) {
                    String trimmed = line.trim();
                    if ("===ID===".equals(trimmed)) {
                        section = "ID";
                        continue;
                    }
                    if ("===USERS===".equals(trimmed)) {
                        section = "USERS";
                        continue;
                    }
                    if ("===GROUPS===".equals(trimmed)) {
                        section = "GROUPS";
                        continue;
                    }
                    if (trimmed.isEmpty()) continue;
                    if ("ID".equals(section)) {
                        idLines.add(trimmed);
                    } else if ("USERS".equals(section)) {
                        userSet.add(trimmed);
                    } else if ("GROUPS".equals(section)) {
                        groupSet.add(trimmed);
                    }
                }
                if (idLines.size() >= 1) {
                    try {
                        result.setCurrentUid(Integer.parseInt(idLines.get(0)));
                    } catch (NumberFormatException ignored) {}
                }
                if (idLines.size() >= 2) {
                    result.setCurrentUser(idLines.get(1));
                }
                result.setUsers(new ArrayList<>(userSet));
                result.setGroups(new ArrayList<>(groupSet));

                boolean isRoot = (result.getCurrentUid() == 0 || "root".equalsIgnoreCase(result.getCurrentUser()));
                long otherUsers = userSet.stream()
                        .filter(u -> !u.equalsIgnoreCase(result.getCurrentUser()))
                        .count();
                boolean canViewOther = isRoot && (otherUsers > 0);
                result.setCanViewOtherUsers(canViewOther);
                result.setCanChown(canViewOther);
            }
        } catch (Exception e) {
            log.debug("获取系统用户与用户组失败: {}", e.getMessage());
        }
        return result;
    }

    private String resolveHomeDir(SFTPClient sftp, SshSessionRegistry.SessionEntry entry) {
        if (entry != null && StringUtils.hasText(entry.getHomeDir())) {
            return entry.getHomeDir();
        }
        if (entry != null && StringUtils.hasText(entry.getDefaultCwd())) {
            entry.setHomeDir(entry.getDefaultCwd().trim());
            return entry.getHomeDir();
        }
        if (sftp != null) {
            try {
                String canon = sftp.canonicalize(".");
                if (StringUtils.hasText(canon)) {
                    if (entry != null) {
                        entry.setHomeDir(canon.trim());
                    }
                    return canon.trim();
                }
            } catch (Exception e) {
                log.debug("SFTP canonicalize home failed", e);
            }
        }
        return "/";
    }

    private static boolean needHome(String path) {
        if (!StringUtils.hasText(path)) return true;
        String t = path.trim();
        return "~".equals(t) || t.startsWith("~/") || t.startsWith("~\\");
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

    static String quote(String text) {
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private IdNameMaps loadIdNameMaps(SshSessionRegistry.SessionEntry entry, List<RemoteResourceInfo> infos) {
        Set<Integer> uids = new LinkedHashSet<>(), gids = new LinkedHashSet<>();
        for (RemoteResourceInfo info : infos) {
            if (".".equals(info.getName()) || "..".equals(info.getName())) continue;
            FileAttributes attrs = info.getAttributes();
            if (attrs != null && attrs.has(FileAttributes.Flag.UIDGID)) {
                uids.add(attrs.getUID());
                gids.add(attrs.getGID());
            }
        }
        IdNameMaps maps = new IdNameMaps();
        maps.uidToName = loadIdNames(entry, "passwd", uids);
        maps.gidToName = loadIdNames(entry, "group", gids);
        return maps;
    }

    private Map<Integer, String> loadIdNames(SshSessionRegistry.SessionEntry entry, String database, Set<Integer> ids) {
        Map<Integer, String> names = new HashMap<>();
        List<Integer> requested = new ArrayList<>(ids);
        // Query only IDs present in the directory. This also supports NSS providers
        // that allow keyed lookups but disable whole-directory enumeration.
        for (int start = 0; start < requested.size(); start += 128) {
            StringBuilder keys = new StringBuilder();
            for (int i = start; i < Math.min(start + 128, requested.size()); i++) {
                keys.append(' ').append(Integer.toUnsignedString(requested.get(i)));
            }
            // Only fixed database names and numeric IDs enter this command; paths
            // and filenames never pass through a shell. Local files are a fallback
            // for minimal Unix systems without getent, not a replacement for NSS.
            String command = "if command -v getent >/dev/null 2>&1; then getent " + database + keys
                    + " 2>/dev/null; else cat /etc/" + database + " 2>/dev/null; fi"
                    + " | awk -F: 'NF >= 4 && $3 ~ /^[0-9]+$/ {print $3\":\"$1}'";
            try {
                SshRemoteExec.ExecResult result = SshRemoteExec.exec(
                        entry.getClient(), entry.getExecLock(), command, 5);
                // getent may return partial results with exit code 2 for missing IDs.
                names.putAll(SshSftpAttributeFormatter.parseIdNameLines(result.getStdout()));
            } catch (IOException e) {
                log.debug("SFTP {} 名称查询失败: {}", database, e.getMessage());
                break;
            }
        }
        names.keySet().retainAll(ids);
        return names;
    }

    private static final class IdNameMaps {
        private Map<Integer, String> uidToName = Collections.emptyMap();
        private Map<Integer, String> gidToName = Collections.emptyMap();
    }

    @Data
    public static class SystemUsersAndGroups {
        private String currentUser = "";
        private int currentUid = -1;
        private boolean canViewOtherUsers = false;
        private boolean canChown = false;
        private List<String> users = new ArrayList<>();
        private List<String> groups = new ArrayList<>();
    }

    @Data
    public static class FileEntry {
        private String name;
        private boolean dir;
        private long size;
        /** Unix epoch seconds */
        private long mtime;
        /** Unix epoch seconds (access time) */
        private long atime;
        /** Unix epoch seconds (create / add time fallback) */
        private long createTime;
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

    @Data
    public static class ChunkResult {
        private long totalSize;
        private long offset;
        private int length;
        private boolean hasMoreBefore;
        private boolean hasMoreAfter;
        private String contentBase64;
    }

}
