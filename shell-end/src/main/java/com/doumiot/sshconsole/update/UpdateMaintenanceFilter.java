package com.doumiot.sshconsole.update;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
@Component
public class UpdateMaintenanceFilter extends OncePerRequestFilter {
    private final UpdateMaintenance gate;
    public UpdateMaintenanceFilter(UpdateMaintenance gate){this.gate=gate;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(!path.startsWith("/api/")||path.startsWith("/api/ssh-console/update/")){chain.doFilter(request,response);return;}
        if(!gate.enter()) {
            response.setStatus(503);response.setContentType("application/json;charset=UTF-8");response.setHeader("Retry-After","10");
            response.getWriter().write("{\"success\":false,\"message\":\"正在更新，请等待服务恢复\"}");return;
        }
        AtomicBoolean released=new AtomicBoolean();Runnable leave=()->{if(released.compareAndSet(false,true))gate.leave();};
        try{chain.doFilter(request,response);}
        finally {
            // Long-lived script event streams need not hold up a restart; active executions are checked separately.
            if(request.isAsyncStarted()&&!path.contains("/executions/")) {
                try{request.getAsyncContext().addListener(new AsyncListener(){
                    public void onComplete(AsyncEvent e){leave.run();}public void onTimeout(AsyncEvent e){leave.run();}
                    public void onError(AsyncEvent e){leave.run();}public void onStartAsync(AsyncEvent e){e.getAsyncContext().addListener(this);}
                });}catch(IllegalStateException completed){leave.run();}
            }else leave.run();
        }
    }
}
