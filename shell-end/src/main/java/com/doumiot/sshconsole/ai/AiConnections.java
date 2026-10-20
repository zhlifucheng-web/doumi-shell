package com.doumiot.sshconsole.ai;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshAccountService;
import com.doumiot.sshconsole.ssh.SshSessionRegistry;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

@Service @SshConsoleEnabled
public class AiConnections {
    @Resource private SshAccountService accounts;
    @Resource private SshSessionRegistry registry;
    private final Map<Long,String> tokens = new HashMap<>();
    synchronized String token(long accountId) {
        String token = tokens.get(accountId);
        if (token != null) {
            try {
                SshSessionRegistry.SessionEntry entry = registry.get(token);
                if (entry.getClient().isConnected()) return token;
            } catch (RuntimeException ignored) { /* An expired AI connection can be reopened. */ }
            registry.close(token); tokens.remove(accountId);
        }
        token = accounts.openSession(accountId).getSessionToken(); tokens.put(accountId, token); return token;
    }
    SshSessionRegistry.SessionEntry session(long accountId) { return registry.get(token(accountId)); }
    synchronized void close(long accountId) {
        String token = tokens.remove(accountId); if (token != null) registry.close(token);
    }
    @PreDestroy synchronized void closeAll() { tokens.values().forEach(registry::close); tokens.clear(); }
}
