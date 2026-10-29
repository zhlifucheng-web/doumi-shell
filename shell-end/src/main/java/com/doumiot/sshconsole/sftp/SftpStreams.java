package com.doumiot.sshconsole.sftp;

import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.RenameFlags;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.UUID;

public final class SftpStreams {
    private SftpStreams() {}
    /** Do not copy local Windows permissions/timestamps onto a remote server. */
    public static void put(SFTPClient sftp,Path local,String target) throws IOException {
        put(sftp, local, target, Ownership.of(null, null));
    }

    public static UploadResult put(SFTPClient sftp, Path local, String target, Ownership owner) throws IOException {
        String temp = target.substring(0, target.lastIndexOf('/') + 1) + ".doumi-upload-" + UUID.randomUUID() + ".part";
        boolean created = false;
        try {
            try (RemoteFile file = createTemporary(sftp, temp)) {
                created = true;
                try (OutputStream out = file.new RemoteFileOutputStream()) { Files.copy(local, out); }
            }
            if (sftp.size(temp) != Files.size(local)) throw new IOException("上传文件大小不完整");
            return commit(sftp, temp, target, owner);
        } catch (IOException | RuntimeException error) {
            if (created) {
                try { sftp.rm(temp); } catch (IOException | RuntimeException cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        }
    }

    public static RemoteFile createTemporary(SFTPClient sftp, String temp) throws IOException {
        return sftp.open(temp, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL),
                new FileAttributes.Builder().withPermissions(0600).build());
    }

    public static void commit(SFTPClient sftp, String temp, String target) throws IOException {
        commit(sftp, temp, target, Ownership.of(null, null));
    }

    public static UploadResult commit(SFTPClient sftp, String temp, String target, Ownership owner) throws IOException {
        FileAttributes existing = sftp.statExistence(target);
        if (existing != null && (existing.getType() != FileMode.Type.REGULAR
                || !existing.has(FileAttributes.Flag.MODE) || !existing.has(FileAttributes.Flag.UIDGID))) {
            throw new IOException("无法完整保留目标文件的 uid/gid/权限，已停止覆盖");
        }
        if (existing != null && existing.getMode() != null && existing.getType() == FileMode.Type.REGULAR) {
            if (existing.has(FileAttributes.Flag.UIDGID)) {
                sftp.setattr(temp, new FileAttributes.Builder()
                        .withUIDGID(existing.getUID(), existing.getGID()).build());
            }
            // chown may clear setuid/setgid, so restore the original mode last.
            sftp.chmod(temp, existing.getMode().getPermissionsMask());
        }
        // Never emulate overwrite by deleting the original. Old servers may only create new files.
        if (sftp.version() >= 5 || sftp.getSFTPEngine().supportsServerExtension("posix-rename", "openssh.com")) {
            sftp.rename(temp, target, EnumSet.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC));
        } else {
            if (existing != null) throw new IOException("远程 SFTP 服务不支持安全覆盖，请先重命名目标文件或升级服务端");
            sftp.rename(temp, target);
        }
        return applyOwnership(sftp, target, owner);
    }

    /** Applied only after content commit. Ownership failures must never turn an upload into a retry. */
    public static UploadResult applyOwnership(SFTPClient sftp, String target, Ownership owner) {
        if (!owner.isRequested()) return UploadResult.success(false, null);
        try {
            FileAttributes attrs = sftp.stat(target);
            if (attrs == null || !attrs.has(FileAttributes.Flag.MODE)) throw new IOException("无法读取上传目标权限");
            int mode = attrs.getMode().getPermissionsMask() & ~06000;
            // Clear privileged bits even on servers whose chown does not clear them itself.
            if ((attrs.getMode().getPermissionsMask() & 06000) != 0) sftp.chmod(target, mode);
            sftp.setattr(target, new FileAttributes.Builder().withUIDGID(owner.uid, owner.gid).build());
            sftp.chmod(target, mode);
            return UploadResult.success(true, null);
        } catch (IOException | RuntimeException error) {
            return UploadResult.success(false, "文件已上传，但无法应用指定的 uid/gid（请检查原 SSH 登录用户的权限）");
        }
    }

    /** Immutable, validated snapshot; never accepts names or creates a new SSH identity. */
    public static final class Ownership {
        private final Integer uid, gid;
        private Ownership(Integer uid, Integer gid) { this.uid = uid; this.gid = gid; }
        public static Ownership of(Long uid, Long gid) throws IOException {
            if (uid == null && gid == null) return new Ownership(null, null);
            if (uid == null || gid == null || uid < 0 || gid < 0 || uid > Integer.MAX_VALUE || gid > Integer.MAX_VALUE)
                throw new IOException("ownerUid 和 ownerGid 必须同时指定，范围为 0 到 2147483647");
            return new Ownership(uid.intValue(), gid.intValue());
        }
        public boolean isRequested() { return uid != null; }
    }

    public static final class UploadResult {
        private final boolean uploaded = true;
        private final boolean ownershipApplied;
        private final String warning;
        private UploadResult(boolean ownershipApplied, String warning) {
            this.ownershipApplied = ownershipApplied; this.warning = warning;
        }
        public static UploadResult success(boolean applied, String warning) { return new UploadResult(applied, warning); }
        public boolean isUploaded() { return uploaded; }
        public boolean isOwnershipApplied() { return ownershipApplied; }
        public String getWarning() { return warning; }
        public UploadResult merge(UploadResult other) {
            String combined = warning != null ? warning : other.warning;
            return success(combined == null && (ownershipApplied || other.ownershipApplied), combined);
        }
    }
}
