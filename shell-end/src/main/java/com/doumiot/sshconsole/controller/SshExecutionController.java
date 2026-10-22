package com.doumiot.sshconsole.controller;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.spring.result.NoWrap;
import com.doumiot.sshconsole.service.SshScriptExecutionService;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.Map;

@RestController @SshConsoleEnabled
@RequestMapping("/api/ssh-console/executions")
public class SshExecutionController {
    @Resource private SshScriptExecutionService service;
    @PostMapping public Object start(@RequestBody Map<String,Long> body) {
        if(body.get("scriptId")==null || body.get("accountId")==null) throw new IllegalArgumentException("请选择脚本和执行账户");
        return service.start(body.get("scriptId"),body.get("accountId"),"MANUAL");
    }
    @GetMapping public Object list(@RequestParam(required=false) Long scriptId,@RequestParam(required=false) String status,
            @RequestParam(required=false) String source,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return service.list(scriptId,status,source,page,size);
    }
    @GetMapping("/{id}") public Object detail(@PathVariable String id) {return service.detail(id);}
    @NoWrap
    @GetMapping(value="/{id}/events",produces="text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter detailEvents(
            @PathVariable String id, javax.servlet.http.HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        return service.streamDetail(id);
    }
    @PostMapping("/{id}/stop") public Object stop(@PathVariable String id) {return service.stop(id);}
}
