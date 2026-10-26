package com.doumiot.sshconsole.crypto;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Random;

/**
 * FinalShell password decryption utility.
 * Supports:
 * 1. FinalShell 3.x / 4.x DES with MD5 random-seed derived key
 * 2. FinalShell 2.x legacy fixed key "finashell"
 * 3. Fallback for unencrypted / plaintext values
 */
@Slf4j
public class FinalShellCipher {

    private static final long MAGIC_NUMBER = 3680984568597093857L;

    public static String decodePassword(String data) {
        if (data == null || data.trim().isEmpty()) {
            return null;
        }
        String trimmed = data.trim();

        // 1. Try FinalShell 3.x / 4.x DES with MD5-derived Random key
        try {
            byte[] buf = Base64.getDecoder().decode(trimmed);
            if (buf.length > 8) {
                byte[] head = new byte[8];
                System.arraycopy(buf, 0, head, 0, head.length);
                byte[] ciphertext = new byte[buf.length - head.length];
                System.arraycopy(buf, head.length, ciphertext, 0, ciphertext.length);

                byte[] key = ranDomKey(head);
                if (key != null) {
                    byte[] decrypted = desDecode(ciphertext, key);
                    if (decrypted != null && decrypted.length > 0) {
                        return new String(decrypted, StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("FinalShell 3.x/4.x password decode failed: {}", e.getMessage());
        }

        // 2. Try FinalShell 2.x legacy fixed key "finashell"
        try {
            byte[] buf = Base64.getDecoder().decode(trimmed);
            byte[] key = "finashell".getBytes(StandardCharsets.UTF_8);
            byte[] decrypted = desDecode(buf, key);
            if (decrypted != null && decrypted.length > 0) {
                return new String(decrypted, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("FinalShell 2.x legacy password decode failed: {}", e.getMessage());
        }

        // 3. Fallback: If not base64 or failed to decrypt, return original
        return trimmed;
    }

    private static byte[] desDecode(byte[] data, byte[] keyBytes) throws Exception {
        SecureRandom sr = new SecureRandom();
        DESKeySpec dks = new DESKeySpec(keyBytes);
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("DES");
        SecretKey securekey = keyFactory.generateSecret(dks);
        Cipher cipher = Cipher.getInstance("DES");
        cipher.init(Cipher.DECRYPT_MODE, securekey, sr);
        return cipher.doFinal(data);
    }

    private static byte[] ranDomKey(byte[] head) {
        try {
            int seedByte = head[5];
            Random seedRandom = new Random((long) seedByte);
            int nextInt = seedRandom.nextInt(127);
            if (nextInt == 0) {
                nextInt = 1;
            }
            long ks = MAGIC_NUMBER / (long) nextInt;
            Random random = new Random(ks);
            int t = head[0];
            for (int i = 0; i < t; ++i) {
                random.nextLong();
            }
            long n = random.nextLong();
            Random r2 = new Random(n);
            long[] ld = new long[]{
                    (long) head[4],
                    r2.nextLong(),
                    (long) head[7],
                    (long) head[3],
                    r2.nextLong(),
                    (long) head[1],
                    random.nextLong(),
                    (long) head[2]
            };
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            for (long l : ld) {
                dos.writeLong(l);
            }
            dos.close();
            return md5(bos.toByteArray());
        } catch (Exception e) {
            log.debug("FinalShell ranDomKey generation failed: {}", e.getMessage());
            return null;
        }
    }

    private static byte[] md5(byte[] data) throws Exception {
        MessageDigest m = MessageDigest.getInstance("MD5");
        m.update(data, 0, data.length);
        return m.digest();
    }
}
