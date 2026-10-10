package com.enterprise.subscription;

import com.enterprise.invoice.Invoice;
import com.enterprise.invoice.InvoiceService;
import com.enterprise.notification.NotificationService;
import com.enterprise.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SubscriptionBillingService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionBillingService.class);

    private final CustomerSubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionUsageRepository usageRepository;
    private final InvoiceService invoiceService;
    private final NotificationService notificationService;

    public SubscriptionBillingService(CustomerSubscriptionRepository subscriptionRepository,
                                      SubscriptionPlanRepository planRepository,
                                      SubscriptionUsageRepository usageRepository,
                                      InvoiceService invoiceService,
                                      NotificationService notificationService) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.usageRepository = usageRepository;
        this.invoiceService = invoiceService;
        this.notificationService = notificationService;
    }

    // ============================================================
    // PAUSE / RESUME
    // ============================================================
    @Transactional
    public CustomerSubscription pauseSubscription(Long subscriptionId, String reason) {
        CustomerSubscription sub = getSubscription(subscriptionId);
        if (sub.getStatus() == CustomerSubscription.SubscriptionStatus.CANCELLED) {
            throw new IllegalStateException("Cannot pause a cancelled subscription");
        }
        if (sub.getStatus() == CustomerSubscription.SubscriptionStatus.PAUSED) {
            throw new IllegalStateException("Subscription is already paused");
        }

        sub.setStatus(CustomerSubscription.SubscriptionStatus.PAUSED);
        sub.setPausedAt(LocalDateTime.now());
        sub.setPauseReason(reason);
        sub.setUpdatedAt(LocalDateTime.now());

        CustomerSubscription saved = subscriptionRepository.save(sub);

        notificationService.createNotification(
                sub.getCustomerId(),
                "SUBSCRIPTION_PAUSED",
                "Subscription Paused",
                "Your subscription to " + sub.getPlan().getName() + " has been paused.",
                "/portal/subscriptions"
        );

        log.info("⏸️ Subscription {} paused (reason: {})", subscriptionId, reason);
        return saved;
    }

    @Transactional
    public CustomerSubscription resumeSubscription(Long subscriptionId) {
        CustomerSubscription sub = getSubscription(subscriptionId);
        if (sub.getStatus() != CustomerSubscription.SubscriptionStatus.PAUSED) {
            throw new IllegalStateException("Only paused subscriptions can be resumed");
        }

        sub.setStatus(CustomerSubscription.SubscriptionStatus.ACTIVE);
        sub.setPausedAt(null);
        sub.setPauseReason(null);
        // Push next billing forward by the pause duration
        if (sub.getPausedAt() != null) {
            long pausedDays = ChronoUnit.DAYS.between(sub.getPausedAt(), LocalDateTime.now());
            sub.setNextBillingDate(sub.getNextBillingDate().plusDays(pausedDays));
        }
        sub.setUpdatedAt(LocalDateTime.now());

        CustomerSubscription saved = subscriptionRepository.save(sub);

        notificationService.createNotification(
                sub.getCustomerId(),
                "SUBSCRIPTION_RESUMED",
                "Subscription Resumed",
                "Your subscription to " + sub.getPlan().getName() + " is active again.",
                "/portal/subscriptions"
        );

        log.info("▶️ Subscription {} resumed", subscriptionId);
        return saved;
    }

    // ============================================================
    // PRORATION PREVIEW
    // ============================================================
    /**
     * Calculate the prorated charge for switching a subscription to a new plan.
     * Returns positive amount (charge) or negative amount (credit).
     */
    public Map<String, Object> previewProration(Long subscriptionId, Long newPlanId) {
        CustomerSubscription sub = getSubscription(subscriptionId);
        SubscriptionPlan oldPlan = sub.getPlan();
        SubscriptionPlan newPlan = planRepository.findById(newPlanId)
                .orElseThrow(() -> new RuntimeException("Plan not found"));

        Map<String, Object> result = new HashMap<>();

        if (oldPlan.getId().equals(newPlanId)) {
            result.put("isChange", false);
            result.put("message", "Same plan — no charge");
            result.put("charge", BigDecimal.ZERO);
            return result;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextBilling = sub.getNextBillingDate();

        long daysRemaining = Math.max(0, ChronoUnit.DAYS.between(now, nextBilling));
        long totalDays = oldPlan.getMonthsInPeriod() * 30L;

        BigDecimal dailyRate = oldPlan.getPrice()
                .divide(BigDecimal.valueOf(totalDays), 4, RoundingMode.HALF_UP);
        BigDecimal unusedValue = dailyRate.multiply(BigDecimal.valueOf(daysRemaining))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal priceDiff = newPlan.getPrice().subtract(oldPlan.getPrice());
        BigDecimal charge = priceDiff;

        boolean isUpgrade = newPlan.getPrice().compareTo(oldPlan.getPrice()) > 0;

        result.put("isChange", true);
        result.put("isUpgrade", isUpgrade);
        result.put("currentPlan", oldPlan.getName());
        result.put("newPlan", newPlan.getName());
        result.put("currentPrice", oldPlan.getPrice());
        result.put("newPrice", newPlan.getPrice());
        result.put("currency", newPlan.getCurrency());
        result.put("daysRemaining", daysRemaining);
        result.put("totalDays", totalDays);
        result.put("unusedValue", unusedValue);
        result.put("priceDifference", priceDiff);
        result.put("charge", charge);
        result.put("message", isUpgrade
                ? "Upgrade — you'll be charged the difference now."
                : "Downgrade — no immediate charge; new rate applies next period.");

        return result;
    }

    // ============================================================
    // INVOICE PREVIEW
    // ============================================================
    /**
     * Preview what the next invoice will contain — plan base + usage overages.
     */
    public Map<String, Object> previewNextInvoice(Long subscriptionId) {
        CustomerSubscription sub = getSubscription(subscriptionId);
        SubscriptionPlan plan = sub.getPlan();

        List<SubscriptionUsage> usage = usageRepository
                .findBySubscriptionIdOrderByMetricNameAsc(subscriptionId);

        BigDecimal base = plan.getPrice();
        BigDecimal totalOverage = BigDecimal.ZERO;

        for (SubscriptionUsage u : usage) {
            totalOverage = totalOverage.add(u.getOverageCharge());
        }

        BigDecimal total = base.add(totalOverage);

        Map<String, Object> result = new HashMap<>();
        result.put("planName", plan.getName());
        result.put("baseAmount", base);
        result.put("usageCharges", totalOverage);
        result.put("total", total);
        result.put("currency", plan.getCurrency());
        result.put("nextBillingDate", sub.getNextBillingDate());
        result.put("usageLines", usage);
        return result;
    }

    // ============================================================
    // UPGRADE WITH PRORATION
    // ============================================================
    @Transactional
    public CustomerSubscription upgradeWithProration(Long subscriptionId, Long newPlanId, Long adminId) {
        CustomerSubscription sub = getSubscription(subscriptionId);
        SubscriptionPlan newPlan = planRepository.findById(newPlanId)
                .orElseThrow(() -> new RuntimeException("New plan not found"));

        if (sub.getPlan().getId().equals(newPlanId)) {
            throw new IllegalStateException("Already on this plan");
        }

        Map<String, Object> preview = previewProration(subscriptionId, newPlanId);
        BigDecimal charge = (BigDecimal) preview.get("charge");

        // Create prorated invoice if there's a charge
        if (charge != null && charge.compareTo(BigDecimal.ZERO) > 0) {
            Invoice invoice = invoiceService.createInvoice(
                    charge,
                    "Prorated upgrade: " + sub.getPlan().getName() + " → " + newPlan.getName(),
                    sub.getCustomerEmail(),
                    sub.getMerchantId(),
                    false,
                    newPlan.getCurrency()
            );
            log.info("✅ Proration invoice #{} created for subscription {}",
                    invoice.getId(), subscriptionId);
        }

        sub.setPlan(newPlan);
        sub.setNextBillingDate(LocalDateTime.now().plusMonths(newPlan.getMonthsInPeriod()));
        sub.setUpdatedAt(LocalDateTime.now());

        CustomerSubscription saved = subscriptionRepository.save(sub);

        notificationService.createNotification(
                sub.getCustomerId(),
                "SUBSCRIPTION_UPGRADED",
                "Subscription Upgraded",
                "You've been upgraded to " + newPlan.getName() + ". Next billing: "
                        + saved.getNextBillingDate().toLocalDate(),
                "/portal/subscriptions"
        );

        log.info("⬆️ Subscription {} upgraded to {}", subscriptionId, newPlan.getName());
        return saved;
    }

    @Transactional
    public CustomerSubscription downgradeWithProration(Long subscriptionId, Long newPlanId, Long adminId) {
        CustomerSubscription sub = getSubscription(subscriptionId);
        SubscriptionPlan newPlan = planRepository.findById(newPlanId)
                .orElseThrow(() -> new RuntimeException("New plan not found"));

        if (sub.getPlan().getId().equals(newPlanId)) {
            throw new IllegalStateException("Already on this plan");
        }

        // No immediate charge on downgrade — new rate applies next period
        sub.setPlan(newPlan);
        sub.setNextBillingDate(LocalDateTime.now().plusMonths(newPlan.getMonthsInPeriod()));
        sub.setUpdatedAt(LocalDateTime.now());

        CustomerSubscription saved = subscriptionRepository.save(sub);

        notificationService.createNotification(
                sub.getCustomerId(),
                "SUBSCRIPTION_DOWNGRADED",
                "Subscription Downgraded",
                "Your plan has been changed to " + newPlan.getName()
                        + ". New rate applies next billing.",
                "/portal/subscriptions"
        );

        log.info("⬇️ Subscription {} downgraded to {}", subscriptionId, newPlan.getName());
        return saved;
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private CustomerSubscription getSubscription(Long id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));
    }
}