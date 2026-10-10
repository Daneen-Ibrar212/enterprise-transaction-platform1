package com.enterprise.reconciliation;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.tenant.Tenant;
import com.enterprise.tenant.TenantContext;
import com.enterprise.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ReconciliationSummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationSummaryScheduler.class);

    private final ReconciliationRecordRepository recordRepository;
    private final ReconciliationEmailService emailService;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;

    public ReconciliationSummaryScheduler(ReconciliationRecordRepository recordRepository,
                                          ReconciliationEmailService emailService,
                                          TenantRepository tenantRepository,
                                          UserRepository userRepository) {
        this.recordRepository = recordRepository;
        this.emailService = emailService;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
    }

    // 00:30 daily — 30 min after nightly reconciliation
    @Scheduled(cron = "0 30 0 * * *")
    public void sendDailySummaries() {
        log.info("📧 Sending daily reconciliation summaries...");

        for (Tenant tenant : tenantRepository.findAll()) {
            try {
                TenantContext.setTenantId(tenant.getId());

                Optional<ReconciliationRecord> latest = recordRepository.findTopByOrderByCreatedAtDesc();
                if (latest.isEmpty()) continue;

                List<AppUser> admins = userRepository.findByTenantIdAndRoleName(tenant.getId(), "ADMIN");
                for (AppUser admin : admins) {
                    emailService.sendSummary(latest.get(), admin.getEmail());
                }
            } catch (Exception e) {
                log.error("Failed to send reconciliation summary for tenant {}", tenant.getId(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}