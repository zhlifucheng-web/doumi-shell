package com.doumiot.sshconsole.controller;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshDataCleanupService;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
@RestController @SshConsoleEnabled @RequestMapping("/api/ssh-console/data-cleanup")
public class SshDataCleanupController {
    @Resource private SshDataCleanupService service;
    @GetMapping("/tables") public Object tables(){return service.tables();}
    @PostMapping("/preview") public Object preview(@RequestBody SshDataCleanupService.Request body){return service.preview(body);}
    @PostMapping("/clear") public Object clear(@RequestBody SshDataCleanupService.ClearRequest body){return service.clear(body.getToken());}
}
