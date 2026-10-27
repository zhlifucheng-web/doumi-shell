package com.doumiot.sshconsole.config;

import com.doumiot.sshconsole.ws.SshTerminalWebSocketHandler;
import com.doumiot.sshconsole.ws.SshFileTailWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import javax.annotation.Resource;

@SshConsoleEnabled
@Configuration
@EnableWebSocket
public class SshConsoleWebSocketConfig implements WebSocketConfigurer {

    @Resource
    private SshTerminalWebSocketHandler terminalWebSocketHandler;

    @Resource private SshFileTailWebSocketHandler fileTailWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalWebSocketHandler, "/ws/ssh-console/terminal")
                .setAllowedOrigins("*");
        registry.addHandler(fileTailWebSocketHandler, "/ws/ssh-console/file-tail").setAllowedOrigins("*");
    }
}
