package com.doumiot.sshconsole.sftp;

import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

public final class SftpStreams {
    private SftpStreams() {}
    /** Do not copy local Windows permissions/timestamps onto a remote server. */
    public static void put(SFTPClient sftp,Path local,String target) throws IOException {
        try(RemoteFile file=sftp.open(target,EnumSet.of(OpenMode.WRITE,OpenMode.CREAT,OpenMode.TRUNC));
            OutputStream out=file.new RemoteFileOutputStream()) {
            Files.copy(local,out);
        }
    }
}
