package com.enterprise.subscription;

import com.enterprise.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class SubscriptionUsageService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionUsageService.class);

    private final SubscriptionUsageRepository usageRepository;
    private final CustomerSubscriptionRepository subscriptionRepository;

    public SubscriptionUsageService(SubscriptionUsageRepository usageRepository,
                                    CustomerSubscriptionRepository subscriptionRepository) {
        this.usageRepository = usageRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    /**
     * Increment usage for a subscription for the current billing period.
     * Creates the usage row if it doesn't exist yet.
     */
    @Transactional
    public SubscriptionUsage recordUsage(Long subscriptionId, String metricName, long delta,
                                         long includedQuantity, BigDecimal overageRate) {
        CustomerSubscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));

        LocalDate today = LocalDate.now();
        LocalDate periodStart = calculatePeriodStart(sub, today);
        LocalDate periodEnd = calculatePeriodEnd(sub, periodStart);

        SubscriptionUsage usage = usageRepository
                .findBySubscriptionIdAndMetricNameAndPeriodStart(subscriptionId, metricName, periodStart)
                .orElseGet(() -> {
                    SubscriptionUsage u = new SubscriptionUsage();
                    u.setSubscriptionId(subscriptionId);
                    u.setTenantId(TenantContext.getRequiredTenantId());
                    u.setMetricName(metricName);
                    u.setPeriodStart(periodStart);
                    u.setPeriodEnd(periodEnd);
                    u.setIncludedQuantity(includedQuantity);
                    u.setOverageRate(overageRate);
                    return u;
                });

        usage.setUsageCount(usage.getUsageCount() + delta);
        usage.setUpdatedAt(LocalDateTime.now());
        return usageRepository.save(usage);
    }

    public List<SubscriptionUsage> listUsage(Long subscriptionId) {
        return usageRepository.findBySubscriptionIdOrderByMetricNameAsc(subscriptionId);
    }

    private LocalDate calculatePeriodStart(CustomerSubscription sub, LocalDate today) {
        LocalDate start = sub.getStartDate().toLocalDate();
        int monthsInPeriod = sub.getPlan().getMonthsInPeriod();
        long monthsBetween = java.time.temporal.ChronoUnit.MONTHS.between(start, today);
        long completedPeriods = monthsBetween / monthsInPeriod;
        return start.plusMonths(completedPeriods * monthsInPeriod);
    }

    private LocalDate calculatePeriodEnd(CustomerSubscription sub, LocalDate periodStart) {
        return periodStart.plusMonths(sub.getPlan().getMonthsInPeriod()).minusDays(1);
    }
}