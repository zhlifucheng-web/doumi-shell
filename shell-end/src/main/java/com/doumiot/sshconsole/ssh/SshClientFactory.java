package com.doumiot.sshconsole.ssh;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.model.SshAuthType;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import net.schmizz.sshj.userauth.password.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.StringReader;
import java.util.concurrent.TimeUnit;

@SshConsoleEnabled
@Component
public class SshClientFactory {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int AUTH_TIMEOUT_MS = 15_000;

    public SSHClient connectAndAuth(String host, int port, String username, String authType,
                                    CredentialCipher.SshSecretPayload secret) throws IOException {
        SSHClient client = new SSHClient();
        client.addHostKeyVerifier(new PromiscuousVerifier());
        client.setConnectTimeout(CONNECT_TIMEOUT_MS);
        client.setTimeout(AUTH_TIMEOUT_MS);
        try {
            client.connect(host, port);
            authenticate(client, username, authType, secret);
            return client;
        } catch (IOException | RuntimeException error) {
            disconnectQuietly(client);
            throw error;
        }
    }

    public void authenticate(SSHClient client, String username, String authType,
                             CredentialCipher.SshSecretPayload secret) throws IOException {
        if (secret == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "凭据未配置");
        }
        if (SshAuthType.PASSWORD.equals(authType)) {
            if (!StringUtils.hasText(secret.getPassword())) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "密码不能为空");
            }
            client.authPassword(username, secret.getPassword());
            return;
        }
        if (SshAuthType.PRIVATE_KEY.equals(authType) || SshAuthType.PRIVATE_KEY_WITH_PASSPHRASE.equals(authType)) {
            if (!StringUtils.hasText(secret.getPrivateKeyPem())) {
                throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "私钥不能为空");
            }
            KeyProvider keys = loadPrivateKey(secret.getPrivateKeyPem(), authType, secret.getPassphrase());
            client.authPublickey(username, keys);
            return;
        }
        throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "不支持的 authType: " + authType);
    }

    private static KeyProvider loadPrivateKey(String pem, String authType, String passphrase) throws IOException {
        PasswordFinder finder = passwordFinder(authType, passphrase);
        String trimmed = pem.trim();
        if (trimmed.contains("BEGIN OPENSSH PRIVATE KEY")) {
            OpenSSHKeyFile keyFile = new OpenSSHKeyFile();
            keyFile.init(new StringReader(trimmed), finder);
            return keyFile;
        }
        PKCS8KeyFile keyFile = new PKCS8KeyFile();
        keyFile.init(new StringReader(trimmed), finder);
        return keyFile;
    }

    private static PasswordFinder passwordFinder(String authType, String passphrase) {
        if (!SshAuthType.PRIVATE_KEY_WITH_PASSPHRASE.equals(authType) || !StringUtils.hasText(passphrase)) {
            return null;
        }
        final char[] pass = passphrase.toCharArray();
        return new PasswordFinder() {
            @Override
            public char[] reqPassword(Resource<?> resource) {
                return pass;
            }

            @Override
            public boolean shouldRetry(Resource<?> resource) {
                return false;
            }
        };
    }

    public void disconnectQuietly(SSHClient client) {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (IOException ignored) {
            // ignore
        }
        try {
            client.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    public static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
