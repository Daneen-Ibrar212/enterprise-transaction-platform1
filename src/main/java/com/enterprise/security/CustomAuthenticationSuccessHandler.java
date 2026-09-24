package com.enterprise.security;

import com.enterprise.audit.UserActivityLogService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.tenant.TenantContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAuthenticationSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomAuthenticationSuccessHandler.class);

    private final UserRepository userRepository;
    private final UserActivityLogService activityLogService;
    private final LoginAttemptService loginAttemptService;

    public CustomAuthenticationSuccessHandler(UserRepository userRepository,
                                              UserActivityLogService activityLogService,
                                              LoginAttemptService loginAttemptService) {
        this.userRepository = userRepository;
        this.activityLogService = activityLogService;
        this.loginAttemptService = loginAttemptService;
        setDefaultTargetUrl("/dashboard");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        String email = authentication.getName();
        log.info("✅ Authentication SUCCESS for user: {}", email);

        try {
            AppUser user = userRepository.findByEmail(email).orElse(null);

            if (user != null) {
                log.info("   User: {}, locked: {}, attempts: {}",
                        user.getEmail(), user.isAccountLocked(), user.getFailedLoginAttempts());

                TenantContext.setTenantId(user.getTenantId());

                loginAttemptService.loginSucceeded(user.getEmail());

                activityLogService.logActivity(
                        user.getId(),
                        "LOGIN_SUCCESS",
                        "User logged in successfully",
                        request
                );

                // ===== 2FA ENFORCEMENT =====
                boolean isAdmin = user.getRoles().stream()
                        .anyMatch(r -> r.getName().equals("ADMIN") || r.getName().equals("SUPER_ADMIN"));
                boolean hasAdminOverride = user.isTwoFactorDisabledByAdmin() 
                        && (user.getTwoFactorDisableExpiresAt() == null 
                            || user.getTwoFactorDisableExpiresAt().isAfter(java.time.LocalDateTime.now()));

                // Admin without 2FA setup
                if (isAdmin && !user.isTwoFactorEnabled()) {
                    if (hasAdminOverride) {
                        log.info("✅ 2FA override active for admin {}, skipping 2FA setup", email);
                    } else {
                        log.info("🔐 Admin user {} must set up 2FA – redirecting to setup", email);
                        HttpSession session = request.getSession();
                        session.setAttribute("2FA_REQUIRED", true);
                        getRedirectStrategy().sendRedirect(request, response, "/2fa/setup?required=true");
                        return;
                    }
                }

                // 2FA enabled but admin override is active
                if (user.isTwoFactorEnabled() && hasAdminOverride) {
                    log.info("✅ 2FA bypassed for {} due to admin override", email);
                    // Allow direct login without 2FA verification
                }
                // 2FA enabled - require verification
                else if (user.isTwoFactorEnabled()) {
                    HttpSession session = request.getSession();
                    session.setAttribute("2FA_PENDING", true);
                    session.removeAttribute("2FA_AUTHENTICATED");
                    getRedirectStrategy().sendRedirect(request, response, "/2fa/verify");
                    return;
                }
            }
        } catch (Exception e) {
            log.error("Error in CustomAuthenticationSuccessHandler", e);
        }

        super.onAuthenticationSuccess(request, response, authentication);
    }
}