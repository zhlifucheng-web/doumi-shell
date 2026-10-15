package com.doumiot.sshconsole.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SshConsoleProperties.class)
public class SshConsoleConfiguration {
}
