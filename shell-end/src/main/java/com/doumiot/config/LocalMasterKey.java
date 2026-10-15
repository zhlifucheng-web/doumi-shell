package com.doumiot.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/** 首次启动生成密钥，后续启动复用；绝不记录密钥内容。 */
public final class LocalMasterKey {
    private LocalMasterKey() { }

    public static String loadOrCreate(String dataDir) {
        Path directory = Paths.get(dataDir).toAbsolutePath().normalize();
        Path keyFile = directory.resolve("master.key");
        try {
            Files.createDirectories(directory);
            if (!Files.exists(keyFile)) {
                byte[] key = new byte[32];
                new SecureRandom().nextBytes(key);
                // 临时文件写完后再发布，避免并发启动读到半个密钥。
                Path temporary = Files.createTempFile(directory, "master-", ".tmp");
                try {
                    try { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")); }
                    catch (UnsupportedOperationException ignored) { /* Windows 使用用户数据目录 ACL */ }
                    Files.write(temporary, Base64.getEncoder().encode(key));
                    try { Files.move(temporary, keyFile); }
                    catch (FileAlreadyExistsException ignored) { /* 另一个进程先生成了密钥 */ }
                } finally { Files.deleteIfExists(temporary); }
            }
            return new String(Files.readAllBytes(keyFile), StandardCharsets.US_ASCII).trim();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取或创建凭据密钥文件: " + keyFile, e);
        }
    }
}
