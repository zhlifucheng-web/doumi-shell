package com.doumiot.sshconsole.controller;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshTerminalContextService;
import com.doumiot.sshconsole.ssh.SshTerminalContextProbe.Context;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;

@SshConsoleEnabled
@RestController
@RequestMapping("/api/ssh-console/terminal-context")
public class SshTerminalContextController {
    @Resource private SshTerminalContextService context;
    @GetMapping public Context get(@RequestParam String sessionToken) { return context.snapshot(sessionToken); }
}
