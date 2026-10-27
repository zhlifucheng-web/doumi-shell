package com.doumiot.sshconsole.controller;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshAccountService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@SshConsoleEnabled
@Validated
@RestController
@RequestMapping(name = "SSH账户", path = "/api/ssh-console/accounts")
public class SshAccountController {

    @Resource
    private SshAccountService sshAccountService;

    @Resource private com.doumiot.sshconsole.ssh.SshSessionRegistry sessions;

    @PostMapping
    public SshAccountService.SshAccountVO create(@RequestBody SshAccountService.SshAccountSaveRequest req) {
        return sshAccountService.create(req);
    }

    @PutMapping("/{id}")
    public SshAccountService.SshAccountVO update(
            @PathVariable long id, @RequestBody SshAccountService.SshAccountSaveRequest req) {
        return sshAccountService.update(id, req);
    }

    @GetMapping("/{id}")
    public SshAccountService.SshAccountVO getById(@PathVariable long id) {
        return sshAccountService.getById(id);
    }

    @DeleteMapping("/{id}")
    public int delete(@PathVariable long id) {
        return sshAccountService.delete(id);
    }

    @PostMapping("/test")
    public SshAccountService.SshConnectionTestResult testDirect(
            @RequestBody SshAccountService.SshAccountTestRequest req) {
        return sshAccountService.testConnection(req);
    }

    @PostMapping("/{id}/test")
    public SshAccountService.SshConnectionTestResult test(@PathVariable long id) {
        return sshAccountService.testConnection(id);
    }

    @GetMapping("/sessions/{token}")
    public java.util.Map<String, Long> sessionContext(@PathVariable String token) {
        return java.util.Collections.singletonMap("accountId", sessions.get(token).getAccountId());
    }

    @PostMapping("/{id}/viewer-session")
    public SshAccountService.SshSessionResponse openViewerSession(@PathVariable long id) {
        SshAccountService.SshSessionResponse response = sshAccountService.openSession(id);
        sessions.get(response.getSessionToken()).setViewerOwned(true);
        return response;
    }

    @DeleteMapping("/viewer-sessions/{token}")
    public void closeViewerSession(@PathVariable String token) {
        // Never close the terminal session that an existing viewer was opened from.
        sessions.closeViewerSession(token);
    }

    @PostMapping("/{id}/session")
    public SshAccountService.SshSessionResponse openSession(@PathVariable long id) {
        return sshAccountService.openSession(id);
    }
}
