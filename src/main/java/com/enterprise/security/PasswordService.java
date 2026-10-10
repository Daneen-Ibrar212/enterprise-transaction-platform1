package com.enterprise.security;

import com.enterprise.audit.UserActivityLogService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.notification.EmailNotificationService;
import com.enterprise.notification.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserActivityLogService activityLogService;
    private final NotificationService notificationService;
    private final EmailNotificationService emailService;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    public PasswordService(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           UserActivityLogService activityLogService,
                           NotificationService notificationService,
                           EmailNotificationService emailService,
                           FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.activityLogService = activityLogService;
        this.notificationService = notificationService;
        this.emailService = emailService;
        this.sessionRepository = sessionRepository;
    }

    /**
     * Change password for an authenticated user.
     * Verifies current password, updates to new password, invalidates all sessions.
     */
    @Transactional
    public void changePassword(AppUser user, String currentPassword, String newPassword,
                               HttpServletRequest request) {
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("New password must be at least 8 characters");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("New password must be different from current password");
        }

        String oldHash = user.getPasswordHash();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("Password changed for user {}", user.getEmail());

        // Invalidate all sessions for this user (forces re-login everywhere)
        invalidateAllSessions(user.getEmail());

        // Audit
        activityLogService.logActivity(
                user.getId(),
                "PASSWORD_CHANGED",
                "User changed own password",
                request
        );

        // In-app + email notification
        notificationService.createNotification(
                user.getId(),
                "PASSWORD_CHANGED",
                "Password Changed",
                "Your password was changed successfully. If this wasn't you, contact support immediately.",
                "/account/password"
        );

        String subject = "Your PayFlow password was changed";
        String html = buildPasswordChangedEmail(user.getEmail());
        emailService.sendHtmlEmailAsync(user.getEmail(), subject, html);
    }

    /**
     * Reset password using a valid reset token. Called by PasswordResetService
     * after token verification.
     */
    @Transactional
    public void resetPassword(AppUser user, String newPassword, HttpServletRequest request) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("New password must be at least 8 characters");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("Password reset for user {}", user.getEmail());

        // Force logout everywhere
        invalidateAllSessions(user.getEmail());

        activityLogService.logActivity(
                user.getId(),
                "PASSWORD_RESET",
                "Password reset via forgot-password flow",
                request
        );

        notificationService.createNotification(
                user.getId(),
                "PASSWORD_RESET",
                "Password Reset",
                "Your password was reset via the forgot-password flow. If this wasn't you, contact support immediately.",
                "/account/password"
        );

        String subject = "Your PayFlow password was reset";
        String html = buildPasswordChangedEmail(user.getEmail());
        emailService.sendHtmlEmailAsync(user.getEmail(), subject, html);
    }

    /**
     * Delete ALL sessions for this user, forcing logout on every device.
     */
    private void invalidateAllSessions(String email) {
        try {
            Map<String, ? extends Session> sessions =
                    sessionRepository.findByPrincipalName(email);
            sessions.values().forEach(s -> sessionRepository.deleteById(s.getId()));
            log.info("Invalidated {} session(s) for {}", sessions.size(), email);
        } catch (Exception e) {
            log.warn("Failed to invalidate sessions for {}: {}", email, e.getMessage());
        }
    }

    private String buildPasswordChangedEmail(String email) {
        return """
            <html>
            <body style="font-family:'Segoe UI',Arial,sans-serif;background:#f0f4f8;padding:32px;">
              <div style="max-width:560px;margin:0 auto;background:white;border-radius:16px;
                          overflow:hidden;box-shadow:0 8px 24px rgba(11,26,51,0.08);">
                <div style="background:linear-gradient(135deg,#0b1a33,#1a3a6e);padding:24px 28px;color:white;">
                  <h1 style="margin:0;font-size:1.25rem;font-weight:500;">Password Changed</h1>
                </div>
                <div style="padding:28px;">
                  <p style="font-size:0.9375rem;color:#334155;line-height:1.6;">Hi,</p>
                  <p style="font-size:0.9375rem;color:#334155;line-height:1.6;">
                    The password for your PayFlow account <strong>%s</strong> was just changed.
                    All active sessions have been signed out.
                  </p>
                  <p style="font-size:0.9375rem;color:#334155;line-height:1.6;">
                    If this wasn't you, reset your password immediately and contact support.
                  </p>
                  <p style="font-size:0.75rem;color:#94a3b8;margin-top:24px;">
                    Sent by PayFlow Enterprise Transaction Platform.
                  </p>
                </div>
              </div>
            </body>
            </html>
            """.formatted(email);
    }
}