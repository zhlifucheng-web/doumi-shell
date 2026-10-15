package com.doumiot.sshconsole.controller;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.entity.SshHost;
import com.doumiot.sshconsole.service.SshHostService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@SshConsoleEnabled
@Validated
@RestController
@RequestMapping(name = "SSH主机", path = "/api/ssh-console/hosts")
public class SshHostController {

    @Resource
    private SshHostService sshHostService;

    @PostMapping
    public SshHost create(@RequestBody SshHostService.SshHostSaveRequest req) {
        return sshHostService.create(req);
    }

    @PutMapping("/{id}")
    public SshHost update(@PathVariable long id, @RequestBody SshHostService.SshHostSaveRequest req) {
        return sshHostService.update(id, req);
    }

    @GetMapping("/{id}")
    public SshHost getById(@PathVariable long id) {
        return sshHostService.getById(id);
    }

    @DeleteMapping("/{id}")
    public int delete(@PathVariable long id) {
        return sshHostService.delete(id);
    }

    @PatchMapping("/{id}/move")
    public SshHost move(@PathVariable long id, @RequestBody MoveRequest body) {
        return sshHostService.move(id, body.getGroupId());
    }

    @lombok.Data
    public static class MoveRequest {
        private Long groupId;
    }
}
