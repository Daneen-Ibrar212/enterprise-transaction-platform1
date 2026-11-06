package com.enterprise.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;

/**
 * ✅ Dev-only — previously logged every header (including JSESSIONID,
 *    Authorization, X-API-Key) for /admin/invoices requests to INFO,
 *    leaking session tokens into production logs.
 */
@Component
@Profile("dev")
public class DebugFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DebugFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/admin/invoices")) {
            chain.doFilter(request, response);
            return;
        }
        log.debug("📨 {} {} from {}", request.getMethod(), uri, request.getRemoteAddr());
        Enumeration<String> params = request.getParameterNames();
        while (params.hasMoreElements()) {
            String name = params.nextElement();
            log.debug("   param: {} = {}", name, request.getParameter(name));
        }
        chain.doFilter(request, response);
    }
}
