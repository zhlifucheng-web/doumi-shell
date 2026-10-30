package com.doumiot.sshconsole.rdp;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@SshConsoleEnabled
@RestController
@RequestMapping("/api/ssh-console/rdp/connections")
public class RdpConnectionController {
    private final RdpConnectionService service;
    private final RdpConnectionTestService tests;

    public RdpConnectionController(RdpConnectionService service, RdpConnectionTestService tests) { this.service = service; this.tests=tests; }

    @GetMapping
    public List<RdpConnectionService.ConnectionVO> list(@RequestParam long hostId) { return service.list(hostId); }

    @PostMapping("/test")
    public RdpConnectionTestService.TestResult test(@RequestBody RdpConnectionService.TestRequest request) { return tests.test(request); }

    @GetMapping("/{id}")
    public RdpConnectionService.ConnectionVO get(@PathVariable long id) { return service.get(id); }

    @PostMapping
    public RdpConnectionService.ConnectionVO create(@RequestBody RdpConnectionService.SaveRequest request) { return service.create(request); }

    @PutMapping("/{id}")
    public RdpConnectionService.ConnectionVO update(@PathVariable long id, @RequestBody RdpConnectionService.SaveRequest request) { return service.update(id,request); }

    @DeleteMapping("/{id}")
    public int delete(@PathVariable long id) { return service.delete(id); }
}
