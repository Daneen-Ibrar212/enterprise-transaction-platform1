package com.enterprise.reminder;

import com.enterprise.feature.FeatureFlagService;
import com.enterprise.tenant.Tenant;
import com.enterprise.tenant.TenantContext;
import com.enterprise.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderService reminderService;
    private final TenantRepository tenantRepository;
    private final FeatureFlagService featureFlagService;

    public ReminderScheduler(ReminderService reminderService,
                             TenantRepository tenantRepository,
                             FeatureFlagService featureFlagService) {
        this.reminderService = reminderService;
        this.tenantRepository = tenantRepository;
        this.featureFlagService = featureFlagService;
    }

    // Schedule new reminders every day at 8:00 AM
    @Scheduled(cron = "0 0 8 * * *")
    public void scheduleDailyReminders() {
        log.info("⏰ Running reminder scheduling...");

        List<Tenant> tenants = tenantRepository.findAll();
        int totalCreated = 0;

        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());

                if (!featureFlagService.isEnabled("INVOICE_REMINDERS")) {
                    log.debug("Reminders disabled for tenant {}", tenant.getId());
                    continue;
                }

                int created = reminderService.scheduleReminders(
                        tenant.getReminderFirstDays(),
                        tenant.getReminderSecondDays(),
                        tenant.getReminderFinalDays()
                );
                totalCreated += created;

            } catch (Exception e) {
                log.error("Error scheduling reminders for tenant {}", tenant.getId(), e);
            } finally {
                TenantContext.clear();
            }
        }

        log.info("✅ Reminder scheduling complete: {} new reminders", totalCreated);
    }

    // Send due reminders every hour
    @Scheduled(cron = "0 15 * * * *")
    public void sendDueReminders() {
        log.debug("📧 Checking for due reminders...");

        List<Tenant> tenants = tenantRepository.findAll();
        int totalSent = 0;

        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());

                if (!featureFlagService.isEnabled("INVOICE_REMINDERS")) {
                    continue;
                }

                int sent = reminderService.sendDueReminders();
                totalSent += sent;

            } catch (Exception e) {
                log.error("Error sending reminders for tenant {}", tenant.getId(), e);
            } finally {
                TenantContext.clear();
            }
        }

        if (totalSent > 0) {
            log.info("✅ Sent {} reminders", totalSent);
        }
    }
}