package com.doumiot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * WebSocket / SockJS 需要可用的 TaskScheduler（避免 defaultSockJsTaskScheduler 为 NullBean）。
 */
@Configuration
public class DoumiShellSchedulingConfig {

    @Bean(name = {"taskScheduler", "defaultSockJsTaskScheduler"})
    public TaskScheduler doumiShellTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("doumi-scheduler-");
        scheduler.initialize();
        return scheduler;
    }
}
