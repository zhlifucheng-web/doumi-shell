package com.doumiot.sshconsole.ssh;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.config.SshConsoleProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@SshConsoleEnabled
@Component
public class SshSessionIdleEvictionTask {

    @Resource
    private SshConsoleProperties properties;
    @Resource
    private SshSessionRegistry sessionRegistry;

    @Scheduled(fixedDelay = 60_000)
    public void evictIdleSessions() {
        int minutes = properties.getIdleTimeoutMinutes();
        if (minutes <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(minutes, ChronoUnit.MINUTES);
        List<String> idle = sessionRegistry.findIdleTokens(cutoff);
        for (String token : idle) {
            log.info("Closing idle SSH session token={}", token);
            sessionRegistry.close(token);
        }
    }
}
