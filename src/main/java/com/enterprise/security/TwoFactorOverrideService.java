package com.enterprise.security;

import com.enterprise.audit.UserActivityLogService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.notification.NotificationService;
import com.enterprise.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TwoFactorOverrideService {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorOverrideService.class);

    private final UserRepository userRepository;
    private final UserActivityLogService activityLogService;
    private final NotificationService notificationService;

    public TwoFactorOverrideService(UserRepository userRepository,
                                    UserActivityLogService activityLogService,
                                    NotificationService notificationService) {
        this.userRepository = userRepository;
        this.activityLogService = activityLogService;
        this.notificationService = notificationService;
    }

    /**
     * Super Admin disables 2FA for a user temporarily.
     *
     * @param targetUserId ID of the user whose 2FA is being disabled
     * @param adminId      ID of the Super Admin performing the action
     * @param reason       Reason (e.g., "Lost device")
     * @param hoursValid   How long the override lasts (null = permanent until manually re-enabled)
     */
    @Transactional
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public void disableTwoFactorForUser(Long targetUserId, Long adminId, String reason, Integer hoursValid) {
        AppUser target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new RuntimeException("User not found: " + targetUserId));

        AppUser admin = userRepository.findById(adminId)
                .orElseThrow(() -> new RuntimeException("Admin not found: " + adminId));

        if (target.getRoles().stream().noneMatch(r -> r.getName().equals("ADMIN") ||
                r.getName().equals("SUPER_ADMIN") ||
                r.getName().equals("MERCHANT_ADMIN") ||
                r.getName().equals("CUSTOMER") ||
                r.getName().equals("MERCHANT") ||
                r.getName().equals("AUDITOR"))) {
            log.warn("Unusual role for 2FA override target: {}", target.getEmail());
        }

        target.setTwoFactorDisabledByAdmin(true);
        target.setTwoFactorDisabledAt(LocalDateTime.now());
        target.setTwoFactorDisabledBy(adminId);
        target.setTwoFactorDisableReason(reason != null ? reason : "Disabled by Super Admin");

        if (hoursValid != null && hoursValid > 0) {
            target.setTwoFactorDisableExpiresAt(LocalDateTime.now().plusHours(hoursValid));
        } else {
            target.setTwoFactorDisableExpiresAt(null); // permanent until re-enabled
        }

        userRepository.save(target);

        // Log the action
        activityLogService.logActivity(
                adminId,
                "2FA_DISABLED_BY_ADMIN",
                String.format("Admin disabled 2FA for user %s (ID: %d). Reason: %s. Duration: %s",
                        target.getEmail(), targetUserId, reason,
                        hoursValid != null ? hoursValid + " hours" : "indefinite"),
                null
        );

        // Notify the user
        try {
            notificationService.createNotification(
                    targetUserId,
                    "2FA_DISABLED",
                    "2FA Temporarily Disabled",
                    "An administrator has temporarily disabled 2FA for your account. " +
                    "Reason: " + reason + ". " +
                    (hoursValid != null ? "Override expires in " + hoursValid + " hours." : "Contact admin for details."),
                    "/2fa/setup"
            );
        } catch (Exception e) {
            log.warn("Failed to send 2FA disable notification: {}", e.getMessage());
        }

        log.info("🔓 Admin {} disabled 2FA for user {} (expires: {})",
                admin.getEmail(), target.getEmail(), target.getTwoFactorDisableExpiresAt());
    }

    /**
     * Super Admin re-enables 2FA for a user.
     */
    @Transactional
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public void enableTwoFactorForUser(Long targetUserId, Long adminId) {
        AppUser target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        target.setTwoFactorDisabledByAdmin(false);
        target.setTwoFactorDisableExpiresAt(null);
        target.setTwoFactorDisabledAt(null);
        target.setTwoFactorDisabledBy(null);
        target.setTwoFactorDisableReason(null);

        userRepository.save(target);

        activityLogService.logActivity(
                adminId,
                "2FA_RE_ENABLED_BY_ADMIN",
                "Admin re-enabled 2FA for user " + target.getEmail(),
                null
        );

        notificationService.createNotification(
                targetUserId,
                "2FA_RE_ENABLED",
                "2FA Re-Enabled",
                "An administrator has re-enabled 2FA for your account. Please set up 2FA again.",
                "/2fa/setup"
        );

        log.info("🔒 Admin {} re-enabled 2FA for user {}", adminId, target.getEmail());
    }

    /**
     * Get all users with 2FA overrides (active or expired).
     */
    public List<AppUser> getUsersWith2FAOverride() {
        return userRepository.findAllWithoutTenantFilter().stream()
                .filter(AppUser::isTwoFactorDisabledByAdmin)
                .toList();
    }

    /**
     * Cleanup expired overrides. Called by scheduler.
     */
    @Transactional
    public int cleanupExpiredOverrides() {
        List<AppUser> expired = userRepository.findAllWithoutTenantFilter().stream()
                .filter(u -> u.isTwoFactorDisabledByAdmin())
                .filter(u -> u.getTwoFactorDisableExpiresAt() != null)
                .filter(u -> u.getTwoFactorDisableExpiresAt().isBefore(LocalDateTime.now()))
                .toList();

        for (AppUser user : expired) {
            user.setTwoFactorDisabledByAdmin(false);
            user.setTwoFactorDisableExpiresAt(null);
            userRepository.save(user);

            activityLogService.logActivity(
                    user.getId(),
                    "2FA_OVERRIDE_EXPIRED",
                    "2FA admin override expired automatically",
                    null
            );
        }
        if (!expired.isEmpty()) {
            log.info("🧹 Cleaned up {} expired 2FA overrides", expired.size());
        }
        return expired.size();
    }
}