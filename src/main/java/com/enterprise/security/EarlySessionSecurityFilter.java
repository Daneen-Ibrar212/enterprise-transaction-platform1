package com.enterprise.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Runs inside the Spring Security filter chain (and therefore inside Spring
 * Session's SessionRepositoryFilter). Touches the session up-front for GET
 * requests to form pages so Spring Security's deferred CSRF token has a
 * Spring Session to persist into before Thymeleaf starts writing the body.
 *
 * Not a @Component — it is registered explicitly in SecurityConfig.
 */
public class EarlySessionSecurityFilter extends OncePerRequestFilter {

    private static final Set<String> FORM_PAGES = Set.of(
            "/",
            "/pricing",
            "/login",
            "/forgot-password",
            "/reset-password",
            "/signup",
            "/signup/payment"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            String path = request.getRequestURI();
            if (FORM_PAGES.contains(path) || path.startsWith("/signup/")) {
                // This goes through Spring Session's request wrapper, so it creates
                // a real (Redis-backed) session rather than a raw Tomcat one.
                request.getSession(true);
            }
        }
        chain.doFilter(request, response);
    }
}