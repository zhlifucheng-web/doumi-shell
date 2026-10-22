package com.doumiot.sshconsole.service;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.crypto.CredentialCipher;
import com.doumiot.sshconsole.entity.SshAccount;
import com.doumiot.sshconsole.entity.SshHost;
import com.doumiot.sshconsole.ssh.SshClientFactory;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import com.doumiot.sshconsole.ssh.SshRemoteExec;
import com.doumiot.sshconsole.config.SshConsoleProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

@Slf4j
@SshConsoleEnabled
@Service
public class SshTerminalService {

    private static final int DEFAULT_COLS = 120;
    private static final int DEFAULT_ROWS = 40;

    @Resource
    private SshAccountService sshAccountService;
    @Resource
    private SshHostService sshHostService;
    @Resource
    private SshClientFactory sshClientFactory;
    @Resource
    private SshSessionRegistry sessionRegistry;
    @Resource
    private SshConsoleProperties properties;

    public TerminalSession openTerminal(long accountId, int cols, int rows, String terminalId) throws IOException {
        return openTerminal(accountId, cols, rows, terminalId, false);
    }

    public TerminalSession openTerminal(long accountId, int cols, int rows, String terminalId, boolean readOnly) throws IOException {
        SshAccount account = sshAccountService.requireAccount(accountId);
        if (account.getEnabled() != null && account.getEnabled() == 0) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "账户已禁用");
        }
        SshHost host = sshHostService.getById(account.getHostId());
        assertHostEnabled(host);
        CredentialCipher.SshSecretPayload secret = sshAccountService.loadSecret(account);
        SSHClient client = sshClientFactory.connectAndAuth(
                host.getHost(), portOf(host), account.getUsername(), account.getAuthType(), secret);

        String label = account.getUsername() + "@" + host.getHost();
        SshSessionRegistry.SessionEntry entry = sessionRegistry.register(
                client, accountId, label, host.getRemark(), account.getRemark(), account.getDefaultCwd());

        int c = cols > 0 ? cols : DEFAULT_COLS;
        int r = rows > 0 ? rows : DEFAULT_ROWS;
        boolean tmux = properties.isTmuxEnabled() && hasTmux(client, entry.getExecLock());
        try {
            attachShell(entry, c, r, account.getDefaultCwd(), accountId, terminalId, tmux, readOnly);
        } catch (IOException e) {
            sessionRegistry.close(entry.getSessionToken());
            throw e;
        }

        TerminalSession ts = new TerminalSession();
        ts.setEntry(entry);
        ts.setLabel(label);
        ts.setReadyRemark(pickReadyRemark(account.getRemark(), host.getRemark()));
        String mode;
        if (tmux) {
            mode = readOnly ? "SSH · tmux (只读共享)" : "SSH · tmux";
        } else {
            mode = "SSH";
        }
        ts.setConnectionMode(mode);
        return ts;
    }

    public void resize(String sessionToken, int cols, int rows) throws IOException {
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        Session.Shell shell = entry.getShell();
        if (shell == null || cols <= 0 || rows <= 0) {
            return;
        }
        if (cols == entry.getPtyCols() && rows == entry.getPtyRows()) {
            return;
        }
        try {
            shell.changeWindowDimensions(cols, rows, 0, 0);
            entry.setPtyCols(cols);
            entry.setPtyRows(rows);
        } catch (Exception e) {
            throw new IOException("调整终端尺寸失败: " + e.getMessage(), e);
        }
    }

    public void writeInput(String sessionToken, byte[] bytes) throws IOException {
        SshSessionRegistry.SessionEntry entry = sessionRegistry.get(sessionToken);
        OutputStream stdin = entry.getShellStdin();
        if (stdin == null) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "终端未就绪");
        }
        stdin.write(bytes);
        stdin.flush();
    }

    private void attachShell(SshSessionRegistry.SessionEntry entry, int cols, int rows, String defaultCwd,
                             long accountId, String terminalId, boolean tmux, boolean readOnly)
            throws IOException {
        SSHClient client = entry.getClient();
        Session sshSession = client.startSession();
        sshSession.allocatePTY("xterm-256color", cols, rows, 0, 0, Collections.emptyMap());
        Session.Shell shell = sshSession.startShell();
        entry.setSshSession(sshSession);
        entry.setShell(shell);
        entry.setShellStdin(shell.getOutputStream());
        entry.setShellStdout(shell.getInputStream());
        entry.setPtyCols(cols);
        entry.setPtyRows(rows);
        entry.setTerminalAttached(true);
        sessionRegistry.touchSession(entry.getSessionToken());

        // tmux keeps the interactive process alive after the browser/WebSocket disconnects.
        // -2 forces 256 colors, -u forces UTF-8 support.
        // The command is conditional so hosts without tmux retain the existing shell behavior.
        if (tmux) {
            String cdPrefix = StringUtils.hasText(defaultCwd) ? shellCd(defaultCwd.trim()) + " 2>/dev/null; " : "";
            String sessName = tmuxSessionName(accountId, terminalId);
            String attachArgs = readOnly ? "attach-session -r -t " : "attach-session -t ";
            String tmuxCommand = cdPrefix + "if command -v tmux >/dev/null 2>&1; then "
                    + "tmux setw -g aggressive-resize on >/dev/null 2>&1; "
                    + "if tmux has-session -t " + sessName + " >/dev/null 2>&1; then "
                    + "exec tmux -2 -u " + attachArgs + sessName + "; else "
                    + "exec tmux -2 -u new-session -s " + sessName + "; fi; fi\n";
            shell.getOutputStream().write(tmuxCommand.getBytes(StandardCharsets.UTF_8));
            shell.getOutputStream().flush();
        } else if (StringUtils.hasText(defaultCwd)) {
            String cmd = shellCd(defaultCwd.trim()) + "\n";
            shell.getOutputStream().write(cmd.getBytes(StandardCharsets.UTF_8));
            shell.getOutputStream().flush();
        }
    }

    private static boolean hasTmux(SSHClient client, Object lock) {
        try {
            SshRemoteExec.ExecResult result = SshRemoteExec.exec(client, lock, "command -v tmux 2>/dev/null", 5);
            return result.getExitStatus() == 0 && result.getStdout() != null
                    && !result.getStdout().trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static String shellCd(String cwd) {
        return "cd " + shellQuote(cwd);
    }

    private static String tmuxSessionName(long accountId, String terminalId) {
        String suffix = terminalId == null ? null : terminalId.replaceAll("[^A-Za-z0-9_-]", "");
        if (suffix == null || suffix.isEmpty() || "default".equalsIgnoreCase(suffix)) {
            suffix = "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        }
        if (suffix.length() > 48) suffix = suffix.substring(0, 48);
        return "'doumi-shell-" + accountId + "-" + suffix + "'";
    }

    private static void assertHostEnabled(SshHost host) {
        if (host.getEnabled() != null && host.getEnabled() == 0) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "主机已禁用，禁止连接");
        }
    }

    private static int portOf(SshHost host) {
        return host.getPort() != null ? host.getPort() : 22;
    }

    private static String pickReadyRemark(String accountRemark, String hostRemark) {
        if (StringUtils.hasText(accountRemark)) {
            return accountRemark.trim();
        }
        if (StringUtils.hasText(hostRemark)) {
            return hostRemark.trim();
        }
        return "";
    }

    @Data
    public static class TerminalSession {
        private SshSessionRegistry.SessionEntry entry;
        private String label;
        private String readyRemark;
        private String connectionMode;
    }
}
