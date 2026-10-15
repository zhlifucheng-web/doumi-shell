package com.doumiot.config;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;

/** Revalidate the entry point and version on every launch; cache fingerprinted assets. */
@Component
public class WebResourceCacheFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("/".equals(path) || "/index.html".equals(path) || "/version.json".equals(path)) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        }
        chain.doFilter(request, response);
    }
}
