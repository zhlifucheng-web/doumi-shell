package com.doumiot.sshconsole.ssh;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import lombok.Data;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 在已有 SSH 连接上执行短命令（与交互 Shell 使用不同 Session 通道）。
 */
public final class SshRemoteExec {

    private static final int DEFAULT_TIMEOUT_SEC = 15;
    private static final int MAX_OUTPUT_BYTES = 512 * 1024;

    private SshRemoteExec() {
    }

    public static ExecResult exec(SSHClient client, Object lock, String command) throws IOException {
        return exec(client, lock, command, DEFAULT_TIMEOUT_SEC);
    }

    public static ExecResult exec(SSHClient client, Object lock, String command, int timeoutSec) throws IOException {
        if (client == null || !client.isConnected()) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "SSH 未连接");
        }
        synchronized (lock) {
            Session session = client.startSession();
            try {
                Session.Command cmd = session.exec(command);
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                Thread outThread = pump(cmd.getInputStream(), stdout);
                Thread errThread = pump(cmd.getErrorStream(), stderr);
                joinCommand(cmd, timeoutSec);
                joinQuietly(outThread, 500);
                joinQuietly(errThread, 500);
                ExecResult result = new ExecResult();
                result.setExitStatus(cmd.getExitStatus() != null ? cmd.getExitStatus() : -1);
                result.setStdout(truncate(stdout.toByteArray()));
                result.setStderr(truncate(stderr.toByteArray()));
                return result;
            } finally {
                session.close();
            }
        }
    }

    /** Bounded history snapshot; keep the newest bytes when the output exceeds the limit. */
    public static ExecResult execTail(SSHClient client, Object lock, String command, int timeoutSec,
            int maxBytes, Charset charset) throws IOException {
        if (maxBytes <= 0 || maxBytes > 8 * 1024 * 1024) throw new IllegalArgumentException("invalid output limit");
        if (client == null || !client.isConnected()) throw new IOException("SSH 未连接");
        synchronized (lock) {
            Session session = client.startSession();
            try {
                Session.Command cmd = session.exec(command);
                // One extra byte tells us whether the retained tail starts at a line boundary.
                TailOutput stdout = new TailOutput(maxBytes + 1);
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                InputStream input = cmd.getInputStream();
                Thread outThread = new Thread(() -> {
                    try {
                        byte[] bytes = new byte[8192]; int n;
                        while ((n = input.read(bytes)) >= 0) stdout.write(bytes, 0, n);
                    } catch (IOException e) { stdout.failure = e; }
                }, "ssh-history-pump");
                outThread.setDaemon(true); outThread.start();
                Thread errThread = pump(cmd.getErrorStream(), stderr);
                joinCommand(cmd, timeoutSec);
                joinQuietly(outThread, 2000); joinQuietly(errThread, 500);
                if (outThread.isAlive()) throw new IOException("历史输出读取超时");
                if (stdout.failure != null) throw stdout.failure;
                ExecResult result = new ExecResult();
                result.setExitStatus(cmd.getExitStatus() == null ? -1 : cmd.getExitStatus());
                byte[] tail = stdout.bytes();
                int start = 0;
                if (stdout.total > maxBytes) {
                    start = tail.length - maxBytes;
                    if (tail[start - 1] != '\n') {
                        // Drop a partial first line instead of decoding half a UTF-8/GBK character.
                        while (start < tail.length && tail[start++] != '\n') { /* skip fragment */ }
                    }
                }
                result.setStdout(new String(tail, start, tail.length - start, charset));
                result.setStderr(truncate(stderr.toByteArray()));
                result.setTruncated(stdout.total > maxBytes);
                return result;
            } finally { session.close(); }
        }
    }

    private static final class TailOutput {
        private final byte[] buffer;
        private int cursor;
        private long total;
        private IOException failure;
        TailOutput(int limit) { buffer = new byte[limit]; }
        void write(byte[] bytes, int offset, int length) {
            total += length;
            if (length >= buffer.length) {
                System.arraycopy(bytes, offset + length - buffer.length, buffer, 0, buffer.length);
                cursor = 0;
            } else {
                int first = Math.min(length, buffer.length - cursor);
                System.arraycopy(bytes, offset, buffer, cursor, first);
                System.arraycopy(bytes, offset + first, buffer, 0, length - first);
                cursor = (cursor + length) % buffer.length;
            }
        }
        byte[] bytes() {
            int size = (int)Math.min(total, buffer.length);
            byte[] result = new byte[size];
            int start = total >= buffer.length ? cursor : 0;
            int first = Math.min(size, buffer.length - start);
            System.arraycopy(buffer, start, result, 0, first);
            System.arraycopy(buffer, 0, result, first, size - first);
            return result;
        }
    }

    /**
     * 执行并从 stdout/stderr 流式回调（用于脚本实时输出）。
     */
    public static ExecResult execWithStdinStream(
            SSHClient client,
            Object lock,
            String command,
            byte[] stdin,
            int timeoutSec,
            BiConsumer<String, byte[]> onStdout,
            BiConsumer<String, byte[]> onStderr)
            throws IOException {
        if (client == null || !client.isConnected()) {
            throw new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, "SSH 未连接");
        }
        synchronized (lock) {
            Session session = client.startSession();
            try {
                Session.Command cmd = session.exec(command);
                if (stdin != null && stdin.length > 0) {
                    OutputStream os = cmd.getOutputStream();
                    os.write(stdin);
                    os.flush();
                    os.close();
                }
                Thread outThread = pumpStream(cmd.getInputStream(), onStdout, "stdout", MAX_OUTPUT_BYTES);
                Thread errThread = pumpStream(cmd.getErrorStream(), onStderr, "stderr", MAX_OUTPUT_BYTES);
                joinCommand(cmd, timeoutSec);
                joinQuietly(outThread, 2000);
                joinQuietly(errThread, 2000);
                ExecResult result = new ExecResult();
                result.setExitStatus(cmd.getExitStatus() != null ? cmd.getExitStatus() : -1);
                return result;
            } finally {
                session.close();
            }
        }
    }

    public static ExecResult execWithStdin(
            SSHClient client, Object lock, String command, byte[] stdin, int timeoutSec) throws IOException {
        synchronized (lock) {
            Session session = client.startSession();
            try {
                Session.Command cmd = session.exec(command);
                if (stdin != null && stdin.length > 0) {
                    OutputStream os = cmd.getOutputStream();
                    os.write(stdin);
                    os.flush();
                    os.close();
                }
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                Thread outThread = pump(cmd.getInputStream(), stdout);
                Thread errThread = pump(cmd.getErrorStream(), stderr);
                joinCommand(cmd, timeoutSec);
                joinQuietly(outThread, 500);
                joinQuietly(errThread, 500);
                ExecResult result = new ExecResult();
                result.setExitStatus(cmd.getExitStatus() != null ? cmd.getExitStatus() : -1);
                result.setStdout(truncate(stdout.toByteArray()));
                result.setStderr(truncate(stderr.toByteArray()));
                return result;
            } finally {
                session.close();
            }
        }
    }

    /** {@code timeoutSec <= 0} 表示一直等到远端命令结束 */
    private static void joinCommand(Session.Command cmd, int timeoutSec) throws IOException {
        if (timeoutSec > 0) {
            cmd.join(timeoutSec, TimeUnit.SECONDS);
            if (cmd.getExitStatus() == null) {
                try { cmd.signal(net.schmizz.sshj.connection.channel.direct.Signal.TERM); }
                catch (IOException ignored) { /* Close the channel even if the server rejects signals. */ }
                throw new java.net.SocketTimeoutException("SSH 命令执行超时 (" + timeoutSec + " 秒)");
            }
        } else {
            cmd.join();
        }
    }

    private static void joinQuietly(Thread t, long ms) {
        if (t == null) {
            return;
        }
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread pumpStream(
            InputStream in, BiConsumer<String, byte[]> consumer, String channel, int maxBytes) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[2048];
                int total = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n == 0) {
                        continue;
                    }
                    int take = n;
                    if (total + take > maxBytes) {
                        take = Math.max(0, maxBytes - total);
                    }
                    if (take > 0 && consumer != null) {
                        byte[] chunk = new byte[take];
                        System.arraycopy(buf, 0, chunk, 0, take);
                        consumer.accept(channel, chunk);
                        total += take;
                    }
                    if (total >= maxBytes) {
                        break;
                    }
                }
            } catch (IOException ignored) {
                /* ignore */
            }
        }, "ssh-exec-stream-" + channel);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (out.size() < MAX_OUTPUT_BYTES) {
                        out.write(buf, 0, Math.min(n, MAX_OUTPUT_BYTES - out.size()));
                    }
                }
            } catch (IOException ignored) {
                /* ignore */
            }
        }, "ssh-exec-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String truncate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Data
    public static class ExecResult {
        private int exitStatus;
        private String stdout;
        private String stderr;
        private boolean truncated;
    }
}
