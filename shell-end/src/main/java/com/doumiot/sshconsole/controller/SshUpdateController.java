package com.doumiot.sshconsole.controller;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.sshconsole.update.*;
import com.doumiot.spring.result.NoWrap;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;

@RestController @SshConsoleEnabled @RequestMapping("/api/ssh-console/update")
public class SshUpdateController {
    private final UpdateSettings settings;private final UpdateService updates;private final Environment env;
    private volatile boolean ready;
    public SshUpdateController(UpdateSettings settings,UpdateService updates,Environment env){this.settings=settings;this.updates=updates;this.env=env;}
    @EventListener public void ready(ApplicationReadyEvent event){ready=true;}
    @GetMapping("/status") public ResponseEntity<Map<String,Object>> status()throws Exception {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(updates.status());
    }
    @PutMapping("/settings") public Map<String,Object> settings(@RequestHeader(value="X-Doumi-Update-Token",required=false) String token,
            @RequestBody Map<String,String> body,HttpServletRequest request)throws Exception {
        authorize(token,request);
        synchronized(updates){
            if(Boolean.TRUE.equals(updates.status().get("busy")))throw new IllegalStateException("更新进行中，不能修改加速代理");
            if(!body.containsKey("proxy"))throw new IllegalArgumentException("缺少加速代理配置");
            settings.saveProxy(body.get("proxy"));return updates.status();
        }
    }
    @PostMapping("/install") public Map<String,Object> install(@RequestHeader(value="X-Doumi-Update-Token",required=false) String token,
            @RequestBody Map<String,String> body,HttpServletRequest request)throws Exception {
        authorize(token,request);return updates.start(body.get("version"));
    }
    @NoWrap @GetMapping("/health") public ResponseEntity<Map<String,String>> health(@RequestParam String id) {
        if(!ready||id.isEmpty()||!id.equals(env.getProperty("doumi.update.health-id","")))return ResponseEntity.status(503).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("id",id,"version",updates.version()));
    }
    private void authorize(String token,HttpServletRequest request) {
        settings.authorize(token);
        if("cross-site".equals(request.getHeader("Sec-Fetch-Site")))throw new SecurityException("拒绝跨站更新请求");
        String origin=request.getHeader("Origin");
        if(origin!=null) {
            try {
                URI uri=URI.create(origin);int port=uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort();
                if(!Objects.equals(uri.getScheme(),request.getScheme())||!Objects.equals(uri.getHost(),request.getServerName())||port!=request.getServerPort())throw new SecurityException("拒绝跨站更新请求");
            }catch(IllegalArgumentException e){throw new SecurityException("请求来源无效");}
        }
    }
    @ExceptionHandler(SecurityException.class) public ResponseEntity<Map<String,Object>> forbidden(SecurityException e){return error(403,e.getMessage());}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,Object>> invalid(IllegalArgumentException e){return error(400,e.getMessage());}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,Object>> conflict(IllegalStateException e){return error(409,e.getMessage());}
    private ResponseEntity<Map<String,Object>> error(int code,String message){return ResponseEntity.status(code).body(Map.of("success",false,"message",message));}
}
