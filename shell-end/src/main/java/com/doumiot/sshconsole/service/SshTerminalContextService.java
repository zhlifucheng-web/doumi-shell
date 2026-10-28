package com.doumiot.sshconsole.service;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import com.doumiot.sshconsole.ssh.SshRemoteExec;
import com.doumiot.sshconsole.ssh.SshTerminalContextProbe;
import com.doumiot.sshconsole.ssh.SshTerminalContextProbe.Context;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;

@Service
@SshConsoleEnabled
public class SshTerminalContextService {
    @Resource private SshSessionRegistry sessions;
    private LongSupplier clock = System::nanoTime;
    public Context snapshot(String token) {
        SshSessionRegistry.SessionEntry entry = sessions.get(token);
        if (!entry.isTerminalAttached()) return SshTerminalContextProbe.unavailable("当前是独立文件会话，请连接终端后启用目录跟随");
        synchronized (entry.getTerminalContextLock()) {
            if (entry.getTerminalContext() != null && clock.getAsLong() - entry.getTerminalContextCheckedAt() < TimeUnit.MILLISECONDS.toNanos(500))
                return entry.getTerminalContext();
            Context result;
            try {
                String pane = entry.getTmuxSession() == null ? null : entry.getTmuxSession().contextPidCommand();
                SshRemoteExec.ExecResult response = SshRemoteExec.exec(entry.getClient(), entry.getTerminalContextLock(),
                        SshTerminalContextProbe.command(pane), 4);
                result = response.getExitStatus() == 0 ? SshTerminalContextProbe.parse(response.getStdout())
                        : SshTerminalContextProbe.unavailable("终端上下文探测暂不可用；SFTP 权限和目录保持不变");
            } catch (IOException | RuntimeException error) {
                result = SshTerminalContextProbe.unavailable("终端上下文探测暂不可用；SFTP 权限和目录保持不变");
            }
            entry.setTerminalContext(result); entry.setTerminalContextCheckedAt(clock.getAsLong());
            return result;
        }
    }
}
