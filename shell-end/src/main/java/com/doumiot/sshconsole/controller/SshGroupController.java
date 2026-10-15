package com.doumiot.sshconsole.controller;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.entity.SshGroup;
import com.doumiot.sshconsole.service.SshGroupService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
@SshConsoleEnabled
@Validated
@RestController
@RequestMapping(name = "SSH分组", path = "/api/ssh-console/groups")
public class SshGroupController {

    @Resource
    private SshGroupService sshGroupService;

    @PostMapping
    public SshGroup create(@RequestBody SshGroupService.SshGroupSaveRequest req) {
        return sshGroupService.create(req);
    }

    @PutMapping("/{id}")
    public SshGroup update(@PathVariable long id, @RequestBody SshGroupService.SshGroupSaveRequest req) {
        return sshGroupService.update(id, req);
    }

    @GetMapping("/{id}")
    public SshGroup getById(@PathVariable long id) {
        return sshGroupService.getById(id);
    }

    @DeleteMapping("/{id}")
    public int delete(@PathVariable long id) {
        return sshGroupService.delete(id);
    }

    @PatchMapping("/{id}/move")
    public SshGroup move(@PathVariable long id, @RequestBody MoveRequest body) {
        return sshGroupService.move(id, body.getParentId());
    }

    @lombok.Data
    public static class MoveRequest {
        private Long parentId;
    }
}
