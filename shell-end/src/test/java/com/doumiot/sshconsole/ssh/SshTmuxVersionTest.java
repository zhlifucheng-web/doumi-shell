package com.doumiot.sshconsole.ssh;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SshTmuxVersionTest {
    private static SshRemoteExec.ExecResult result(int exit, String text) {
        SshRemoteExec.ExecResult result = new SshRemoteExec.ExecResult();
        result.setExitStatus(exit); result.setStdout(text); result.setStderr(""); return result;
    }

    @Test void prefersRunningServerVersionOverInstalledExecutableVersion() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SshTmuxSession tmux = new SshTmuxSession((command, timeout) -> {
            if (calls.incrementAndGet() <= 2) return result(0, "");
            assertTrue(command.contains("display-message -p -t"));
            assertTrue(command.contains("only-this-session:"));
            return result(0, "3.5a\n");
        });
        tmux.prepare("only-this-session", null, 120, 40, false);
        SshTmuxSession.VersionInfo info = tmux.detectVersion();
        assertEquals("3.5a", info.getVersion()); assertEquals("server", info.getSource());
        assertEquals(3, calls.get());
    }

    @Test void legacyUnknownFormatFallsBackToExecutableAndLabelsItsSource() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SshTmuxSession tmux = new SshTmuxSession((command, timeout) -> {
            int call = calls.incrementAndGet();
            return result(0, call <= 2 ? "" : call == 3 ? "#{version}\n" : "tmux 1.6\n");
        });
        tmux.prepare("persistent", null, 120, 40, false);
        SshTmuxSession.VersionInfo info = tmux.detectVersion();
        assertEquals("1.6", info.getVersion()); assertEquals("executable", info.getSource());
    }

    @Test void failedVersionProbeDoesNotFailAnOtherwiseReadySession() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SshTmuxSession tmux = new SshTmuxSession((command, timeout) -> {
            if (calls.incrementAndGet() <= 2) return result(0, "");
            throw new IOException("probe timed out");
        });
        tmux.prepare("persistent", null, 120, 40, false);
        SshTmuxSession.VersionInfo info = assertDoesNotThrow(tmux::detectVersion);
        assertEquals("", info.getVersion()); assertEquals("unknown", info.getSource());
        assertNotNull(tmux.historyCommand());
    }
}
