package com.doumiot.sshconsole.ssh;

import net.schmizz.sshj.connection.channel.Channel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Short-lived tmux preparation and exact-client attachment confirmation (tmux 1.6+). */
public final class SshTmuxSession {
    @FunctionalInterface public interface CommandRunner {
        SshRemoteExec.ExecResult run(String command, int timeoutSeconds) throws IOException;
    }
    private final CommandRunner runner;
    private String sessionName;
    private long mouseStateUntil;
    private boolean wheelAsKeys;
    private static final Pattern X10_WHEEL = Pattern.compile("\033\\[M([\\s\\S])([\\s\\S]{2})");
    private static final Pattern SGR_WHEEL = Pattern.compile("\033\\[<(\\d{1,3});\\d+;\\d+M");
    public SshTmuxSession(CommandRunner runner) { this.runner = runner; }

    public static final class VersionInfo {
        private final String version;
        private final String source;
        VersionInfo(String version, String source) { this.version = version; this.source = source; }
        public String getVersion() { return version; }
        public String getSource() { return source; }
    }

    /** Version probing is advisory: an old server or failed probe must not end a live terminal. */
    public VersionInfo detectVersion() {
        if (sessionName != null) {
            try {
                SshRemoteExec.ExecResult server = run("exec tmux display-message -p -t "
                        + quote(sessionName + ":") + " " + quote("#{version}"), 2);
                String version = server.getExitStatus() == 0 ? parseVersion(server.getStdout()) : "";
                if (!version.isEmpty()) return new VersionInfo(version, "server");
            } catch (IOException | RuntimeException ignored) { /* Try the installed executable next. */ }
        }
        try {
            SshRemoteExec.ExecResult executable = run("exec tmux -V", 2);
            String version = executable.getExitStatus() == 0 ? parseVersion(executable.getStdout()) : "";
            if (!version.isEmpty()) return new VersionInfo(version, "executable");
        } catch (IOException | RuntimeException ignored) { /* Keep the established session usable. */ }
        return new VersionInfo("", "unknown");
    }

    private static String parseVersion(String output) {
        if (output == null || output.length() > 128) return "";
        Matcher match = Pattern.compile("^(?:tmux\\s+)?((?:next-)?[0-9]+\\.[0-9]+(?:[a-z]|-rc[0-9]+)?)(?:[ \\t].*)?$")
                .matcher(output.trim());
        return match.matches() ? match.group(1) : "";
    }

    /** Read the active pane in this session, without changing copy mode or paste buffers. */
    public String historyCommand() throws IOException {
        if (sessionName == null) throw new IOException("tmux 会话未就绪");
        // -p requires tmux 1.8+. Older versions keep their existing connection support.
        return boundedCommand("exec tmux capture-pane -p -S -50000 -t " + quote(sessionName + ":"), 8);
    }

    /** Resolve only this session's active window/pane, including tmux 1.6. */
    public String contextPidCommand() throws IOException {
        if (sessionName == null) throw new IOException("tmux 会话未就绪");
        return "tmux list-panes -t " + quote(sessionName + ":")
                + " -F '#{pane_active}|#{pane_pid}' | while IFS='|' read -r active pid; do "
                + "[ \"$active\" = 1 ] || continue; printf '%s' \"$pid\"; break; done";
    }

    public void prepare(String name, String cwd, int cols, int rows, boolean shared) throws IOException {
        SshRemoteExec.ExecResult exists = run("exec tmux has-session -t " + quote(name), 3);
        if (exists.getExitStatus() != 0) {
            if (exists.getExitStatus() != 1) throw failure("检查会话", exists);
            if (shared) throw new IOException("共享的 tmux 会话不存在，请发起者先连接原终端");
            String cd = cwd == null || cwd.trim().isEmpty() ? "" : "cd " + quote(cwd.trim()) + " && ";
            SshRemoteExec.ExecResult created = run(cd + "exec tmux -2 -u new-session -d -s " + quote(name)
                    + " -x " + cols + " -y " + rows, 3);
            // Another browser may have created this same persistent session concurrently.
            if (created.getExitStatus() != 0 && run("exec tmux has-session -t " + quote(name), 3).getExitStatus() != 0)
                throw failure("创建会话", created);
        }
        // 2.1+ uses a session option; 1.6 uses a window option. Never change global defaults.
        // Keep stderr on the SSH pipe: tmux 1.6 can stall flushing errors to /dev/null.
        SshRemoteExec.ExecResult mouse = run("tmux set-option -t " + quote(name)
                + " mouse on || tmux set-window-option -t " + quote(name) + " mode-mouse on", 3);
        if (mouse.getExitStatus() != 0) throw failure("配置鼠标滚动", mouse);
        sessionName = name;
    }

    /** Keep shell wheels in tmux history, but retain cursor movement in editors without mouse support. */
    public synchronized byte[] filterWheelInput(byte[] input) {
        String text = new String(input, StandardCharsets.UTF_8);
        Matcher x10 = X10_WHEEL.matcher(text), sgr = SGR_WHEEL.matcher(text);
        int button;
        if (input.length == 6 && input[0] == 27 && input[1] == '[' && input[2] == 'M') button = (input[3] & 255) - 32;
        else if (x10.matches()) button = x10.group(1).charAt(0) - 32;
        else if (sgr.matches()) button = Integer.parseInt(sgr.group(1));
        else { mouseStateUntil = 0; return input; }
        // Accept only wheel up/down, with optional Shift/Alt/Ctrl modifier bits.
        if ((button & ~28) != 64 && (button & ~28) != 65) { mouseStateUntil = 0; return input; }
        if (sessionName == null) return input;
        long now = System.nanoTime();
        if (now >= mouseStateUntil) {
            wheelAsKeys = false;
            try {
                SshRemoteExec.ExecResult state = run(mouseStateCommand(), 1);
                wheelAsKeys = state.getExitStatus() == 0 && "keys".equals(state.getStdout().trim());
            } catch (IOException ignored) {
                // A failed probe must never turn a shell wheel into command-history cursor keys.
            }
            mouseStateUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
        }
        return wheelAsKeys ? (button % 2 == 0 ? "\033[A" : "\033[B").getBytes(StandardCharsets.UTF_8) : input;
    }

    private String mouseStateCommand() {
        // 1.6 has no alternate_on/mouse_any_flag formats. Its Linux pane process still exposes
        // the foreground process group. Recognize common full-screen tools, leaving commands
        // such as tail and Python REPLs in history scrolling rather than recalling their input.
        return "tmux list-panes -t " + quote(sessionName)
                + " -F '#{pane_active}|#{pane_pid}|#{alternate_on}|#{mouse_any_flag}|#{pane_in_mode}'"
                + " | while IFS='|' read -r active pid alternate mouse in_mode; do "
                + "[ \"$active\" = 1 ] || continue; "
                + "if [ -n \"$alternate\" ]; then "
                + "if [ \"$alternate\" = 1 ] && [ \"$mouse\" = 0 ] && [ \"$in_mode\" != 1 ]; then printf keys; else printf mouse; fi; "
                + "else case \"$pid\" in ''|*[!0-9]*) exit 1;; esac; "
                + "groups=$(ps -o pgid=,tpgid= -p \"$pid\") || exit 1; set -- $groups; "
                + "if [ \"$#\" = 2 ] && [ \"$1\" != \"$2\" ] && [ \"$2\" -gt 0 ]; then "
                + "foreground=$(ps -o comm= -p \"$2\") || exit 1; "
                + "case \"${foreground##*/}\" in vi|view|vim|vim.*|nvim|nano|pico|emacs*|less|more|man|top|htop|btop|mcedit|mc|ranger|joe|jed|micro) printf keys;; *) printf mouse;; esac; "
                + "else printf mouse; fi; fi; break; done";
    }

    public static String attachCommand(String name, boolean readOnly) {
        return attachCommand(name, readOnly, "UTF-8");
    }

    public static String attachCommand(String name, boolean readOnly, String encoding) {
        String normalized = encoding == null ? "" : encoding.trim().toUpperCase(java.util.Locale.ROOT);
        boolean legacy = java.util.Arrays.asList("GBK", "GB18030", "GB2312", "BIG5", "ISO-8859-1", "WINDOWS-1252")
                .contains(normalized);
        // The client locale controls tmux's own borders/filler, not the existing pane's locale or bytes.
        // A legacy-codepage viewer must not receive UTF-8 decorations mixed into its raw pane output.
        // Unknown labels follow the browser TextDecoder fallback to UTF-8.
        String client = legacy ? "env LC_ALL=C LANG=C tmux -2" : "tmux -2 -u";
        return "printf '\\036DOUMI_TTY:%s\\037' \"$(tty)\"; exec " + client + " attach-session "
                + (readOnly ? "-r " : "") + "-t " + quote(name);
    }

    /** The marker identifies the PTY only; ready requires tmux to list that exact PTY as a client. */
    public InputStream awaitAttached(Channel channel, String name, long timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        InputStream stdout = channel.getInputStream();
        ByteArrayOutputStream initial = new ByteArrayOutputStream();
        String tty = null;
        while (System.nanoTime() < deadline) {
            while (stdout.available() > 0) {
                byte[] buf = new byte[Math.min(stdout.available(), 8192)];
                int n = stdout.read(buf);
                if (n < 0) break;
                initial.write(buf, 0, n);
                if (initial.size() > 128 * 1024) throw new IOException("tmux 启动输出过多，未确认附着");
            }
            byte[] buffered = initial.toByteArray();
            String ascii = new String(buffered, StandardCharsets.ISO_8859_1);
            int start = ascii.indexOf("\036DOUMI_TTY:");
            int end = start < 0 ? -1 : ascii.indexOf('\037', start);
            if (tty == null && end >= 0) {
                tty = ascii.substring(start + 11, end).trim();
                if (!tty.startsWith("/dev/")) throw new IOException("无法获取 tmux 客户端终端标识");
                initial.reset();
                initial.write(buffered, 0, start);
                initial.write(buffered, end + 1, buffered.length - end - 1);
            }
            if (!channel.isOpen() || channel.isEOF())
                throw new IOException("tmux 附着失败: " + new String(initial.toByteArray(), StandardCharsets.UTF_8).trim());
            if (tty != null) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs < 1000) break;
                SshRemoteExec.ExecResult clients = run("exec tmux list-clients -t " + quote(name)
                        + " -F '#{client_tty}'", (int)Math.min(2, remainingMs / 1000));
                if (clients.getExitStatus() != 0) throw failure("确认客户端附着", clients);
                for (String line : clients.getStdout().split("\\r?\\n")) {
                    if (tty.equals(line.trim()))
                        return new SequenceInputStream(new ByteArrayInputStream(initial.toByteArray()), stdout);
                }
            }
            try { Thread.sleep(100); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("tmux 连接已取消", e); }
        }
        throw new java.net.SocketTimeoutException("tmux 附着超时，未检测到本次客户端；请检查远端 tmux 或改用普通 SSH");
    }

    private SshRemoteExec.ExecResult run(String command, int seconds) throws IOException {
        SshRemoteExec.ExecResult result = runner.run(boundedCommand(command, seconds), seconds + 2);
        if (result.getExitStatus() == 124 || result.getExitStatus() == 137 || result.getExitStatus() == 143)
            throw new java.net.SocketTimeoutException("tmux 准备命令超时，已终止本次命令");
        return result;
    }

    /** Only short commands are bounded; the interactive attached client has no lifetime limit. */
    static String boundedCommand(String command, int seconds) {
        // A POSIX watchdog avoids requiring GNU timeout on the target host. exec preserves the child PID.
        String script = "sh -c " + quote(command) + " & child=$!; "
                + "(sleep " + seconds + "; kill -TERM \"$child\" 2>/dev/null; sleep 1; kill -KILL \"$child\" 2>/dev/null) >/dev/null 2>&1 & guard=$!; "
                + "trap 'kill -TERM \"$child\" 2>/dev/null; kill \"$guard\" 2>/dev/null' HUP INT TERM; "
                + "wait \"$child\"; result=$?; kill \"$guard\" 2>/dev/null; wait \"$guard\" 2>/dev/null; exit \"$result\"";
        return "sh -c " + quote(script);
    }

    private static IOException failure(String stage, SshRemoteExec.ExecResult result) {
        String detail = result.getStderr() == null ? "" : result.getStderr().trim();
        if (detail.length() > 500) detail = detail.substring(0, 500);
        return new IOException("tmux " + stage + "失败 (exit=" + result.getExitStatus() + "): " + detail);
    }
    public static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
}
