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
import java.util.Arrays;
import java.util.List;

public class TwoFactorAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorAuthenticationFilter.class);

    private static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/2fa/**", "/logout", "/css/", "/js/"
    );

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
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser")) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();

        // ============================================================
        // ✅ CHECK ADMIN OVERRIDE - allows user to skip 2FA
        // ============================================================
        boolean hasActiveOverride = false;
        try {
            AppUser user = userRepository.findByEmail(auth.getName()).orElse(null);
            if (user != null && user.isTwoFactorEffectivelyDisabled() && user.isTwoFactorEnabled()) {
                hasActiveOverride = true;
                log.debug("✅ 2FA bypassed for {} due to admin override", auth.getName());
            }
        } catch (Exception e) {
            log.warn("Failed to check 2FA override for {}: {}", auth.getName(), e.getMessage());
        }

        // ----- CHECK 2FA REQUIRED FOR ADMIN -----
        Boolean required = (Boolean) session.getAttribute("2FA_REQUIRED");
        if (required != null && required) {
            // Allow 2FA pages, logout, static resources
            if (uri.startsWith("/2fa/") || uri.startsWith("/logout") || 
                uri.startsWith("/css/") || uri.startsWith("/js/")) {
                filterChain.doFilter(request, response);
                return;
            }
            // ✅ If admin override is active, allow through
            if (hasActiveOverride) {
                filterChain.doFilter(request, response);
                return;
            }
            // Otherwise redirect to 2FA setup
            response.sendRedirect("/2fa/setup?required=true");
            return;
        }

        // ----- NORMAL 2FA VERIFICATION -----
        Boolean pending = (Boolean) session.getAttribute("2FA_PENDING");
        Boolean authenticated = (Boolean) session.getAttribute("2FA_AUTHENTICATED");

        if (pending == null || !pending || (authenticated != null && authenticated)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Allow 2FA pages, logout, static resources
        if (uri.startsWith("/2fa/") || uri.startsWith("/logout") || 
            uri.startsWith("/css/") || uri.startsWith("/js/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // ✅ If admin override is active, allow through
        if (hasActiveOverride) {
            filterChain.doFilter(request, response);
            return;
        }

        response.sendRedirect("/2fa/verify");
    }
}