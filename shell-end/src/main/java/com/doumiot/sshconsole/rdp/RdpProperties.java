package com.doumiot.sshconsole.rdp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "doumi.rdp")
public class RdpProperties {
    private boolean enabled = true;
    private String guacdHost = "127.0.0.1";
    private int guacdPort = 4822;
    private int maxSessions = 20;
    private int idleTimeoutMinutes = 30;
}
