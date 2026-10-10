package com.enterprise.dispute;

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
public class DisputeScheduler {

    private static final Logger log = LoggerFactory.getLogger(DisputeScheduler.class);

    private final DisputeService disputeService;
    private final TenantRepository tenantRepository;
    private final FeatureFlagService featureFlagService;

    public DisputeScheduler(DisputeService disputeService,
                            TenantRepository tenantRepository,
                            FeatureFlagService featureFlagService) {
        this.disputeService = disputeService;
        this.tenantRepository = tenantRepository;
        this.featureFlagService = featureFlagService;
    }

    // Warn about deadlines daily at 9 AM
    @Scheduled(cron = "0 0 9 * * *")
    public void warnDeadlines() {
        for (Tenant t : tenantRepository.findAll()) {
            try {
                TenantContext.setTenantId(t.getId());
                if (!featureFlagService.isEnabled("DISPUTES")) continue;
                int warned = disputeService.warnUpcomingDeadlines();
                if (warned > 0) log.info("Sent {} dispute deadline warnings for tenant {}", warned, t.getId());
            } catch (Exception e) {
                log.error("Deadline warn failed for tenant {}", t.getId(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    // Auto-lose expired disputes daily at 00:05
    @Scheduled(cron = "0 5 0 * * *")
    public void autoLoseExpired() {
        for (Tenant t : tenantRepository.findAll()) {
            try {
                TenantContext.setTenantId(t.getId());
                if (!featureFlagService.isEnabled("DISPUTES")) continue;
                int lost = disputeService.autoLoseExpired();
                if (lost > 0) log.info("Auto-lost {} expired disputes for tenant {}", lost, t.getId());
            } catch (Exception e) {
                log.error("Auto-lose failed for tenant {}", t.getId(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}