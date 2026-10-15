package com.doumiot.config;

import lombok.Data;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 可跨安装实例迁移的加密备份，不包含本机主密钥。 */
public final class ConfigurationArchive {
    public static final int ITERATIONS = 210000;
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private ConfigurationArchive() { }

    @Data
    public static class Archive {
        private String format = "doumi-backup";
        private int version = 1;
        private String algorithm = "PBKDF2-SHA256/AES-256-GCM";
        private int iterations = ITERATIONS;
        private String salt;
        private String iv;
        private String ciphertext;
        private JsonNode data;
    }

    public static Archive plain(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("配置大小超过备份上限 12 MB");
        try {
            Archive archive = new Archive();
            archive.setAlgorithm("NONE");
            archive.setIterations(0);
            archive.setData(new ObjectMapper().readTree(bytes));
            return archive;
        } catch (java.io.IOException e) { throw new IllegalArgumentException("配置格式无效", e); }
    }

    public static Archive encrypt(byte[] plain, String password) {
        validatePassword(password);
        if (plain.length > MAX_BYTES) throw new IllegalArgumentException("配置大小超过备份上限 12 MB");
        try {
            byte[] salt = random(16), iv = random(12);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv);
            Archive archive = new Archive();
            archive.setSalt(Base64.getEncoder().encodeToString(salt));
            archive.setIv(Base64.getEncoder().encodeToString(iv));
            archive.setCiphertext(Base64.getEncoder().encodeToString(cipher.doFinal(plain)));
            return archive;
        } catch (Exception e) { throw new IllegalStateException("配置备份加密失败", e); }
    }

    public static byte[] decrypt(Archive archive, String password) {
        if (archive != null && "NONE".equals(archive.getAlgorithm())) {
            if (!"doumi-backup".equals(archive.getFormat()) || archive.getVersion() != 1
                    || archive.getData() == null || !archive.getData().isObject()
                    || archive.getCiphertext() != null || archive.getSalt() != null || archive.getIv() != null)
                throw new IllegalArgumentException("明文备份格式或版本不支持");
            byte[] bytes = archive.getData().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("配置大小超过备份上限 12 MB");
            return bytes;
        }
        validatePassword(password);
        try {
            if (archive == null || !"doumi-backup".equals(archive.getFormat()) || archive.getVersion() != 1
                    || !"PBKDF2-SHA256/AES-256-GCM".equals(archive.getAlgorithm())
                    || archive.getIterations() != ITERATIONS || archive.getCiphertext() == null
                    || archive.getCiphertext().length() > (MAX_BYTES + 16) * 4 / 3 + 4)
                throw new IllegalArgumentException();
            byte[] salt = Base64.getDecoder().decode(archive.getSalt());
            byte[] iv = Base64.getDecoder().decode(archive.getIv());
            if (salt.length != 16 || iv.length != 12) throw new IllegalArgumentException();
            return cipher(Cipher.DECRYPT_MODE, password, salt, iv)
                    .doFinal(Base64.getDecoder().decode(archive.getCiphertext()));
        } catch (Exception e) { throw new IllegalArgumentException("密码错误、备份损坏或版本不支持"); }
    }

    private static Cipher cipher(int mode, String password, byte[] salt, byte[] iv) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        byte[] key;
        try { key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        try { cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv)); }
        finally { java.util.Arrays.fill(key, (byte) 0); }
        return cipher;
    }

    private static byte[] random(int length) { byte[] bytes = new byte[length]; new SecureRandom().nextBytes(bytes); return bytes; }
    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 1024)
            throw new IllegalArgumentException("备份密码长度须为 8 至 1024 个字符");
    }
}
