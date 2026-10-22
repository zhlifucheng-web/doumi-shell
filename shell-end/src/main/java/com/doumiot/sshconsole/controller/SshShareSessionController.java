package com.doumiot.sshconsole.controller;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshShareSessionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

@SshConsoleEnabled
@Validated
@RestController
@RequestMapping(name = "SSH终端共享", path = "/api/ssh-console/share")
public class SshShareSessionController {

    @Resource
    private SshShareSessionService shareSessionService;

    @PostMapping("/create")
    public SshShareSessionService.ShareInfoVO create(@RequestBody SshShareSessionService.ShareCreateRequest req) {
        return shareSessionService.createShare(req);
    }

    @GetMapping("/info")
    public SshShareSessionService.ShareInfoVO getInfo(@RequestParam("token") String token) {
        SshShareSessionService.ShareInfoVO info = shareSessionService.getShareInfo(token);
        if (info == null) {
            SshShareSessionService.ShareInfoVO empty = new SshShareSessionService.ShareInfoVO();
            empty.setToken(token);
            empty.setExpired(true);
            return empty;
        }
        return info;
    }

    @GetMapping("/list")
    public List<SshShareSessionService.ShareInfoVO> listActive(
            @RequestParam("accountId") Long accountId,
            @RequestParam(value = "terminalId", required = false) String terminalId) {
        return shareSessionService.listActiveShares(accountId, terminalId);
    }

    @PostMapping("/revoke")
    public boolean revoke(@RequestParam("token") String token) {
        return shareSessionService.revokeShare(token);
    }
}
