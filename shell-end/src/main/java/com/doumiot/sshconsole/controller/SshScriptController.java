package com.doumiot.sshconsole.controller;

import com.doumiot.spring.result.NoWrap;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.service.SshScriptService;
import com.doumiot.sshconsole.service.SshScriptScheduleService;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;

@SshConsoleEnabled
@Validated
@RestController
@RequestMapping(name = "SSH 脚本", path = "/api/ssh-console/scripts")
public class SshScriptController {

    @Resource
    private SshScriptService sshScriptService;
    @Resource
    private SshScriptScheduleService schedules;

    @GetMapping("/schedule-accounts")
    public Object scheduleAccounts() { return schedules.accountOptions(); }

    @PostMapping("/cron-preview")
    public List<String> preview(@RequestBody CronRequest body) {
        return SshScriptScheduleService.preview(body.getCron(), body.getZone());
    }

    @Data
    public static class CronRequest {
        private String cron;
        private String zone = "Asia/Shanghai";
    }

    @GetMapping
    public List<SshScriptService.SshScriptVO> list(@RequestParam(required = false) Long hostId) {
        return sshScriptService.listForHost(hostId);
    }

    @GetMapping("/{id}")
    public SshScriptService.SshScriptVO get(@PathVariable long id) {
        return sshScriptService.getById(id);
    }

    @PostMapping
    public SshScriptService.SshScriptVO create(@RequestBody @Validated SshScriptService.SshScriptSaveRequest body) {
        return sshScriptService.create(body);
    }

    @PutMapping("/{id}")
    public SshScriptService.SshScriptVO update(
            @PathVariable long id, @RequestBody @Validated SshScriptService.SshScriptSaveRequest body) {
        return sshScriptService.update(id, body);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        sshScriptService.delete(id);
    }

    @PostMapping("/{id}/run")
    public SshScriptService.ScriptRunResult run(
            @PathVariable long id, @RequestBody @Validated RunRequest body) throws IOException {
        return sshScriptService.run(id, body.getSessionToken());
    }

    @NoWrap
    @PostMapping(value = "/{id}/run/stream", produces = "application/x-ndjson;charset=UTF-8")
    public ResponseEntity<StreamingResponseBody> runStream(
            @PathVariable long id, @RequestBody @Validated RunRequest body) {
        StreamingResponseBody stream = outputStream -> sshScriptService.runStreaming(
                id, body.getSessionToken(), outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"))
                .body(stream);
    }

    @Data
    public static class RunRequest {
        @NotBlank
        private String sessionToken;
    }
}
