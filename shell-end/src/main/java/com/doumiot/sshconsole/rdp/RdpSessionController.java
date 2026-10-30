package com.doumiot.sshconsole.rdp;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import lombok.Data;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@SshConsoleEnabled
@RequestMapping("/api/ssh-console/rdp")
public class RdpSessionController {
    private final RdpSessionService sessions;
    public RdpSessionController(RdpSessionService sessions) { this.sessions=sessions; }
    @PostMapping("/sessions")
    public Map<String,String> open(@RequestBody OpenRequest request) {
        return Collections.singletonMap("token", sessions.issue(request.getConnectionId(), request.getWidth(), request.getHeight(), request.getDpi()));
    }
    @DeleteMapping("/sessions/{token}")
    public void close(@PathVariable String token) { sessions.cancel(token); }
    @GetMapping("/gateway")
    public Map<String,Object> gateway() { return sessions.gateway(); }
    @Data public static class OpenRequest {
        private long connectionId;
        private int width=1280, height=720, dpi=96;
    }
}
