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
        String temp = target.substring(0, target.lastIndexOf('/') + 1) + ".doumi-upload-" + UUID.randomUUID() + ".part";
        boolean created = false;
        try {
            try (RemoteFile file = createTemporary(sftp, temp)) {
                created = true;
                try (OutputStream out = file.new RemoteFileOutputStream()) { Files.copy(local, out); }
            }
            if (sftp.size(temp) != Files.size(local)) throw new IOException("上传文件大小不完整");
            commit(sftp, temp, target);
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
        FileAttributes existing = sftp.statExistence(target);
        if (existing != null && existing.getMode() != null && existing.getType() == FileMode.Type.REGULAR) {
            sftp.chmod(temp, existing.getMode().getPermissionsMask());
        }
        // Never emulate overwrite by deleting the original. Old servers may only create new files.
        if (sftp.version() >= 5 || sftp.getSFTPEngine().supportsServerExtension("posix-rename", "openssh.com")) {
            sftp.rename(temp, target, EnumSet.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC));
        } else {
            if (existing != null) throw new IOException("远程 SFTP 服务不支持安全覆盖，请先重命名目标文件或升级服务端");
            sftp.rename(temp, target);
        }
    }
}
