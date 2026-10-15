package com.doumiot.sshconsole.controller;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshHostMonitorService;
import com.doumiot.sshconsole.service.SshProcessService;
import com.doumiot.sshconsole.service.SshDiskUsageService;
import org.springframework.validation.annotation.Validated;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;

@SshConsoleEnabled
@Validated
@RestController
@RequestMapping(name = "SSH 主机运维", path = "/api/ssh-console/host")
public class SshHostOpsController {

    @Resource
    private SshHostMonitorService sshHostMonitorService;
    @Resource
    private SshProcessService sshProcessService;
    @Resource private SshDiskUsageService diskUsage;

    @GetMapping("/disk-usage")
    public SshDiskUsageService.ScanResult diskUsage(@RequestParam @NotBlank String sessionToken,
            @RequestParam(required=false) String path,@RequestParam(defaultValue="100") int minimumMb) throws IOException {
        return diskUsage.scan(sessionToken,path,minimumMb);
    }

    @GetMapping("/metrics")
    public SshHostMonitorService.HostMetricsSnapshot metrics(@RequestParam @NotBlank String sessionToken)
            throws IOException {
        return sshHostMonitorService.sample(sessionToken);
    }

    @GetMapping("/processes")
    public List<SshProcessService.ProcessRow> processes(@RequestParam @NotBlank String sessionToken)
            throws IOException {
        return sshProcessService.listProcesses(sessionToken);
    }

    @PostMapping("/processes/kill")
    public SshProcessService.KillResult killProcess(@RequestBody @Validated KillRequest body) throws IOException {
        return sshProcessService.killProcess(body.getSessionToken(), body.getPid(), body.getSignal());
    }

    @Data
    public static class KillRequest {
        @NotBlank
        private String sessionToken;
        private long pid;
        /** TERM 或 KILL，默认 TERM */
        private String signal;
    }
}
