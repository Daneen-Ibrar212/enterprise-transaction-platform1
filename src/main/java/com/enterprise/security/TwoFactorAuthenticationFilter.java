package com.enterprise.security;

import com.enterprise.feature.FeatureFlagService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class TwoFactorAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorAuthenticationFilter.class);

    private final FeatureFlagService featureFlagService;
    private final UserRepository userRepository;

    public TwoFactorAuthenticationFilter(FeatureFlagService featureFlagService,
                                         UserRepository userRepository) {
        this.featureFlagService = featureFlagService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!featureFlagService.isEnabled("TWO_FACTOR_AUTH")) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        if (uri.startsWith("/2fa/") || uri.startsWith("/logout")
                || uri.startsWith("/css/") || uri.startsWith("/js/")
                || uri.startsWith("/images/") || uri.startsWith("/fonts/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Admin override check
        boolean hasActiveOverride = false;
        try {
            AppUser user = userRepository.findByEmail(auth.getName()).orElse(null);
            if (user != null && user.isTwoFactorEffectivelyDisabled() && user.isTwoFactorEnabled()) {
                hasActiveOverride = true;
            }
        } catch (Exception e) {
            log.warn("Failed to check 2FA override for {}: {}", auth.getName(), e.getMessage());
        }

        Boolean required = (Boolean) session.getAttribute("2FA_REQUIRED");
        if (Boolean.TRUE.equals(required) && !hasActiveOverride) {
            response.sendRedirect("/2fa/setup?required=true");
            return;
        }

        Boolean pending = (Boolean) session.getAttribute("2FA_PENDING");
        Boolean authenticated = (Boolean) session.getAttribute("2FA_AUTHENTICATED");
        if (Boolean.TRUE.equals(pending)
                && !Boolean.TRUE.equals(authenticated)
                && !hasActiveOverride) {
            response.sendRedirect("/2fa/verify");
            return;
        }

        filterChain.doFilter(request, response);
    }
}