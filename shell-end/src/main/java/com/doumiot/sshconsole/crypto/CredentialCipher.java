package com.doumiot.sshconsole.crypto;

import com.google.gson.JsonObject;
import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.config.SshConsoleProperties;
import com.doumiot.t2.util.JsonUtil;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import com.doumiot.config.DoumiShellStorageProperties;
import com.doumiot.config.LocalMasterKey;

@SshConsoleEnabled
@Component
public class CredentialCipher {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SshConsoleProperties properties;
    private final DoumiShellStorageProperties storage;
    private byte[] masterKey;

    public CredentialCipher(SshConsoleProperties properties, DoumiShellStorageProperties storage) {
        this.properties = properties;
        this.storage = storage;
    }

    @PostConstruct
    void init() {
        String configured = properties.getCredentialMasterKey();
        masterKey = decodeMasterKey(StringUtils.hasText(configured) ? configured : LocalMasterKey.loadOrCreate(storage.getDataDir()));
    }

    public byte[] encryptSecretJson(String plainJson) {
        if (!StringUtils.hasText(plainJson)) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "凭据内容不能为空");
        }
        ensureKeyConfigured();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + cipherText.length);
            buf.put(iv);
            buf.put(cipherText);
            return buf.array();
        } catch (Exception e) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "凭据加密失败: " + e.getMessage());
        }
    }

    public String decryptToJson(byte[] secretEnc) {
        if (secretEnc == null || secretEnc.length == 0) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "凭据未配置");
        }
        ensureKeyConfigured();
        try {
            ByteBuffer buf = ByteBuffer.wrap(secretEnc);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buf.get(iv);
            byte[] cipherText = new byte[buf.remaining()];
            buf.get(cipherText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "凭据解密失败，请检查主密钥: " + e.getMessage());
        }
    }

    public void ensureKeyConfigured() {
        if (masterKey == null || masterKey.length != 32) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY,
                    "未配置 doumi.ssh-console.credential-master-key（32 字节 Base64/Hex）");
        }
    }

    static byte[] decodeMasterKey(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            if (trimmed.matches("(?i)[0-9a-f]{64}")) {
                byte[] out = new byte[32];
                for (int i = 0; i < 32; i++) {
                    out[i] = (byte) Integer.parseInt(trimmed.substring(i * 2, i * 2 + 2), 16);
                }
                return out;
            }
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            if (decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY,
                "credential-master-key 须为 32 字节 Base64 或 64 位 Hex");
    }

    public static String buildSecretJson(SshSecretPayload secret) {
        JsonObject o = new JsonObject();
        if (secret.getPassword() != null) {
            o.addProperty("password", secret.getPassword());
        }
        if (secret.getPrivateKeyPem() != null) {
            o.addProperty("privateKeyPem", secret.getPrivateKeyPem());
        }
        if (secret.getPassphrase() != null) {
            o.addProperty("passphrase", secret.getPassphrase());
        }
        return o.toString();
    }

    public static SshSecretPayload parseSecretJson(String json) {
        return JsonUtil.gson.fromJson(json, SshSecretPayload.class);
    }

    public static String hintForSecret(SshSecretPayload secret, String authType) {
        if (secret == null) {
            return null;
        }
        if ("PASSWORD".equals(authType) && StringUtils.hasText(secret.getPassword())) {
            String p = secret.getPassword();
            if (p.length() <= 4) {
                return "****";
            }
            return "****" + p.substring(p.length() - 4);
        }
        if (StringUtils.hasText(secret.getPrivateKeyPem())) {
            return "private-key";
        }
        return "****";
    }

    @lombok.Data
    public static class SshSecretPayload {
        private String password;
        private String privateKeyPem;
        private String passphrase;
    }
}
