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

    @PostMapping("/{id}/test")
    public SshAccountService.SshConnectionTestResult test(@PathVariable long id) {
        return sshAccountService.testConnection(id);
    }

    @PostMapping("/{id}/session")
    public SshAccountService.SshSessionResponse openSession(@PathVariable long id) {
        return sshAccountService.openSession(id);
    }
}
