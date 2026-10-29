package com.doumiot.sshconsole.controller;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshCopyService;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.io.IOException;

@RestController
@SshConsoleEnabled
@RequestMapping("/api/ssh-console/sftp/copy")
public class SshCopyController {
    @Resource private SshCopyService copies;
    @PostMapping("/start") public SshCopyService.State start(@RequestBody SshCopyService.Request request) throws IOException {
        return copies.start(request);
    }
    @GetMapping("/status") public SshCopyService.State status(@RequestParam String sessionToken,@RequestParam String id) throws IOException {
        return copies.status(sessionToken,id);
    }
    @PostMapping("/cancel") public SshCopyService.State cancel(@RequestBody Control request) throws IOException {
        return copies.cancel(request.getSessionToken(),request.getId());
    }
    @lombok.Data public static class Control {private String sessionToken,id;}
}
