package com.doumiot.sshconsole.controller;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshReleaseService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import javax.annotation.Resource;

@SshConsoleEnabled
@RestController
@RequestMapping("/api/ssh-console/releases")
public class SshReleaseController {
    @Resource private SshReleaseService releases;

    @GetMapping("/latest")
    public ResponseEntity<SshReleaseService.Latest> latest(@RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(releases.latest(refresh));
    }
}
