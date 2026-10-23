package com.doumiot.sshconsole.ssh;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SshTmuxHistoryTest {
    @Test void historyTargetsOnlyTheAttachedSessionAndHasAFixedLineLimit() throws Exception {
        AtomicReference<String> command = new AtomicReference<>();
        SshTmuxSession tmux = new SshTmuxSession((cmd, timeout) -> {
            command.set(cmd);
            SshRemoteExec.ExecResult result = new SshRemoteExec.ExecResult();
            result.setExitStatus(0); result.setStdout("old 中文\nnew\n");
            return result;
        });
        assertThrows(IOException.class, tmux::historyCommand);
        tmux.prepare("only-this-session", null, 120, 40, false);
        String capture = tmux.historyCommand();
        assertTrue(capture.contains("capture-pane -p -S -50000"));
        assertTrue(capture.contains("'only-this-session:'"));
        assertFalse(capture.contains("send-keys"));
        assertFalse(capture.contains("set-buffer"));
    }
}
