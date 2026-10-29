package com.doumiot.sshconsole.controller;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshCopyService;
import com.doumiot.sshconsole.service.SshSftpService;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.io.IOException;

@RestController
@SshConsoleEnabled
@RequestMapping("/api/ssh-console/sftp/copy")
public class SshCopyController {
    @Resource private SshCopyService copies;
    @GetMapping("/target-users") public SshSftpService.SystemUsersAndGroups targetUsers(
            @RequestParam String sessionToken,@RequestParam long targetAccountId) throws IOException {
        return copies.targetUsers(sessionToken,targetAccountId);
    }
    @GetMapping("/target-directories") public SshSftpService.ListResult targetDirectories(
            @RequestParam String sessionToken,@RequestParam long targetAccountId,@RequestParam(defaultValue="") String path) throws IOException {
        return copies.targetDirectories(sessionToken,targetAccountId,path);
    }
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
