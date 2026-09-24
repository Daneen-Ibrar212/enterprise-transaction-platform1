package com.enterprise.scheduler;

import com.enterprise.security.TwoFactorOverrideService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TwoFactorOverrideCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorOverrideCleanupScheduler.class);

    private final TwoFactorOverrideService service;

    public TwoFactorOverrideCleanupScheduler(TwoFactorOverrideService service) {
        this.service = service;
    }

    // Run every hour
    @Scheduled(fixedDelay = 3600000)
    public void cleanupExpired2FAOverrides() {
        try {
            int cleaned = service.cleanupExpiredOverrides();
            if (cleaned > 0) {
                log.info("✅ Cleaned up {} expired 2FA overrides", cleaned);
            }
        } catch (Exception e) {
            log.error("Failed to clean up 2FA overrides", e);
        }
    }
}