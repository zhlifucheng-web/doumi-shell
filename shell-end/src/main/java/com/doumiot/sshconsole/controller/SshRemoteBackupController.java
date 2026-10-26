package com.doumiot.sshconsole.controller;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshRemoteBackupService;
import com.doumiot.sshconsole.service.SshRemoteBackupService.RemoteBackupConfigDTO;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController
@SshConsoleEnabled
@RequestMapping("/api/ssh-console/backup/remote")
public class SshRemoteBackupController {

    @Resource
    private SshRemoteBackupService service;

    @GetMapping("/config")
    public RemoteBackupConfigDTO getConfig(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.getConfig();
    }

    @PostMapping("/config")
    public RemoteBackupConfigDTO saveConfig(@RequestBody RemoteBackupConfigDTO request) {
        service.saveConfig(request);
        return service.getConfig();
    }

    @PostMapping("/test")
    public Map<String, Object> testConnection(@RequestBody RemoteBackupConfigDTO request) {
        return service.testConnection(request);
    }

    @PostMapping("/execute")
    public Map<String, Object> executeBackupNow() {
        return service.executeBackupNow();
    }
}
