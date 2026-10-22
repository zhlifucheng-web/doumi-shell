package com.doumiot.sshconsole.ssh;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the generated POSIX commands; only external tmux/ps are controlled fixtures. */
class SshTmuxMouseCommandTest {
    private static final byte[] WHEEL = "\033[M`!!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UP = "\033[A".getBytes(StandardCharsets.UTF_8);

    @Test void modernTmuxDistinguishesShellEditorNativeMouseAndCopyMode() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.manager.prepare("managed", null, 120, 40, false);
            assertEquals("modern:managed", fixture.config());
            Files.delete(fixture.directory.resolve("config"));
            fixture.manager.prepare("managed", null, 120, 40, true);
            assertEquals("modern:managed", fixture.config(), "reconnect/shared sessions must also configure scrolling");
            fixture.check("1|42|0|0|0", "42 42", WHEEL);
            fixture.check("1|42|1|0|0", "42 99", UP);
            fixture.check("1|42|1|1|0", "42 99", WHEEL);
            fixture.check("1|42|1|0|1", "42 99", WHEEL);
        }
    }

    @Test void tmux16FallsBackToScopedWindowOptionAndForegroundProcessGroups() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.manager.prepare("managed", null, 120, 40, false);
            assertEquals("legacy:managed", fixture.config());
            fixture.check("1|42|||", "42 42", WHEEL);
            fixture.check("0|40|||\n1|42|||", "42 99", UP);
            fixture.foreground = "tail";
            fixture.check("1|42|||", "42 99", WHEEL);
            fixture.foreground = "python";
            fixture.check("1|42|||", "42 99", WHEEL);
            fixture.check("1|42|||", "42 42", WHEEL);
            fixture.check("1|42|||", "42 -1", WHEEL);
        }
    }

    @Test void unsupportedOrMissingPaneStateCannotRecallAShellCommand() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.manager.prepare("managed", null, 120, 40, false);
            fixture.check("", "", WHEEL);
            fixture.check("1|not-a-pid|||", "42 99", WHEEL);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path directory = Files.createTempDirectory("terminal-mouse-");
        final boolean legacy;
        String pane = "", groups = "", foreground = "vim";
        final SshTmuxSession manager = new SshTmuxSession(this::run);

        Fixture(boolean legacy) throws Exception {
            this.legacy = legacy;
            executable("tmux", "#!/bin/sh\ncase \"$1\" in\n"
                    + "has-session) exit 0;;\n"
                    // Model tmux 1.6's stalled error flush when stderr is /dev/null instead of a pipe.
                    + "set-option) if [ \"$LEGACY\" = 1 ]; then "
                    + "[ -p /dev/fd/2 ] || sleep 4; printf 'ambiguous option: mouse\\n' >&2; exit 1; fi; "
                    + "[ \"$2 $3 $4 $5\" = '-t managed mouse on' ] || exit 9; printf modern:managed > \"$CONFIG\";;\n"
                    + "set-window-option) [ \"$2 $3 $4 $5\" = '-t managed mode-mouse on' ] || exit 9; printf legacy:managed > \"$CONFIG\";;\n"
                    + "list-panes) [ \"$2 $3\" = '-t managed' ] || exit 9; printf '%s\\n' \"$PANE\";;\n"
                    + "*) exit 9;;\nesac\n");
            executable("ps", "#!/bin/sh\ncase \"$1 $2 $3 $4\" in\n"
                    + "'-o pgid=,tpgid= -p 42') printf '%s\\n' \"$GROUPS_FIXTURE\";;\n"
                    + "'-o comm= -p 99') printf '%s\\n' \"$FOREGROUND\";;\n*) exit 9;;\nesac\n");
        }

        void executable(String name, String source) throws IOException {
            Path file = directory.resolve(name);
            Files.write(file, source.getBytes(StandardCharsets.UTF_8));
            assertTrue(file.toFile().setExecutable(true));
        }

        String config() throws IOException {
            return new String(Files.readAllBytes(directory.resolve("config")), StandardCharsets.UTF_8);
        }

        void check(String pane, String groups, byte[] expected) {
            this.pane = pane;
            this.groups = groups;
            manager.filterWheelInput(new byte[]{13}); // A command/editor transition invalidates the burst cache.
            assertArrayEquals(expected, manager.filterWheelInput(WHEEL), pane + " / " + groups);
        }

        SshRemoteExec.ExecResult run(String command, int seconds) throws IOException {
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", command);
            builder.environment().put("PATH", directory + File.pathSeparator + System.getenv("PATH"));
            builder.environment().put("LEGACY", legacy ? "1" : "0");
            builder.environment().put("CONFIG", directory.resolve("config").toString());
            builder.environment().put("PANE", pane);
            builder.environment().put("GROUPS_FIXTURE", groups);
            builder.environment().put("FOREGROUND", foreground);
            Process process = builder.start();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("fixture command timed out");
                SshRemoteExec.ExecResult result = new SshRemoteExec.ExecResult();
                result.setExitStatus(process.exitValue());
                result.setStdout(read(process.getInputStream()));
                result.setStderr(read(process.getErrorStream()));
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } finally { process.destroyForcibly(); }
        }

        String read(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override public void close() throws IOException {
            for (String name : new String[]{"tmux", "ps", "config"}) Files.deleteIfExists(directory.resolve(name));
            Files.deleteIfExists(directory);
        }
    }
}
