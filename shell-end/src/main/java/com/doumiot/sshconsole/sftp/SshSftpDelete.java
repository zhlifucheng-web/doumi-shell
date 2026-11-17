package com.doumiot.sshconsole.sftp;

import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;

import java.io.IOException;

/** Deletes directory contents without following symbolic links. */
public final class SshSftpDelete {
    private SshSftpDelete() {}

    public static void delete(SFTPClient sftp, String path) throws IOException {
        if ("/".equals(path)) throw new IOException("不允许删除根目录");
        if (sftp.lstat(path).getType() != FileMode.Type.DIRECTORY) {
            sftp.rm(path);
            return;
        }
        for (RemoteResourceInfo child : sftp.ls(path)) {
            String name = child.getName();
            if (".".equals(name) || "..".equals(name)) continue;
            if (name.isEmpty() || name.contains("/") || name.indexOf('\0') >= 0) {
                throw new IOException("无效的目录项名称");
            }
            delete(sftp, path + "/" + name);
        }
        sftp.rmdir(path);
    }
}
