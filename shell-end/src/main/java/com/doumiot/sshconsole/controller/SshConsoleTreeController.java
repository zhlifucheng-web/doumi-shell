package com.doumiot.sshconsole.controller;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshTreeService;
import com.doumiot.sshconsole.vo.TreeNodeVO;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

@SshConsoleEnabled
@Validated
@RestController
@RequestMapping(name = "SSH控制台树", path = "/api/ssh-console")
public class SshConsoleTreeController {

    @Resource
    private SshTreeService sshTreeService;

    @GetMapping("/tree/children")
    public List<TreeNodeVO> children(
            @RequestParam String parentType,
            @RequestParam(required = false) Long parentId) {
        return sshTreeService.listChildren(parentType, parentId);
    }
}
