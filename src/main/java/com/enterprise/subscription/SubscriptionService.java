package com.enterprise.subscription;

import com.enterprise.invoice.Invoice;
import com.enterprise.invoice.InvoiceService;
import com.enterprise.notification.NotificationService;
import com.enterprise.tenant.Tenant;
import com.enterprise.tenant.TenantContext;
import com.enterprise.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final CustomerSubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionRequestRepository requestRepository;
    private final PaymentRetryRepository paymentRetryRepository;
    private final InvoiceService invoiceService;
    private final NotificationService notificationService;
    private final TenantRepository tenantRepository;

    public SubscriptionService(CustomerSubscriptionRepository subscriptionRepository,
                               SubscriptionPlanRepository planRepository,
                               SubscriptionRequestRepository requestRepository,
                               PaymentRetryRepository paymentRetryRepository,
                               InvoiceService invoiceService,
                               NotificationService notificationService,
                               TenantRepository tenantRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.requestRepository = requestRepository;
        this.paymentRetryRepository = paymentRetryRepository;
        this.invoiceService = invoiceService;
        this.notificationService = notificationService;
        this.tenantRepository = tenantRepository;
    }

    // ============ REQUEST MANAGEMENT ============

    @Transactional
    public SubscriptionRequest createRequest(Long customerId, String customerEmail, Long merchantId, Long planId) {
        Long tenantId = TenantContext.getRequiredTenantId();
        SubscriptionPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new RuntimeException("Plan not found"));

        List<SubscriptionRequest> pending = requestRepository.findByCustomerId(customerId).stream()
                .filter(r -> r.getPlanId().equals(planId)
                        && r.getStatus() == SubscriptionRequest.RequestStatus.PENDING)
                .toList();
        if (!pending.isEmpty()) {
            throw new RuntimeException("You already have a pending request for this plan");
        }

        // ✅ Check for existing active subscription
        List<CustomerSubscription> active = subscriptionRepository
                .findByCustomerIdAndPlanIdAndStatus(customerId, planId,
                        CustomerSubscription.SubscriptionStatus.ACTIVE)
                .stream().toList();
        if (!active.isEmpty()) {
            throw new RuntimeException("You are already subscribed to this plan");
        }

        SubscriptionRequest req = new SubscriptionRequest();
        req.setCustomerId(customerId);
        req.setCustomerEmail(customerEmail);
        req.setPlanId(planId);
        req.setPlanName(plan.getName());
        req.setMerchantId(merchantId);
        req.setTenantId(tenantId);
        req.setStatus(SubscriptionRequest.RequestStatus.PENDING);
        SubscriptionRequest saved = requestRepository.save(req);

        notificationService.createNotification(merchantId, "SUBSCRIPTION_REQUEST",
                "New Subscription Request",
                "Customer " + customerEmail + " wants to subscribe to " + plan.getName(),
                "/merchant/subscriptions/requests");
        return saved;
    }

    @Transactional
    public CustomerSubscription approveRequest(Long requestId, Long adminId) {
        SubscriptionRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Request not found"));
        if (req.getStatus() != SubscriptionRequest.RequestStatus.PENDING) {
            throw new RuntimeException("Request already processed");
        }
        CustomerSubscription sub = createSubscriptionInternal(
                req.getCustomerId(), req.getCustomerEmail(),
                req.getMerchantId(), req.getPlanId());
        req.setStatus(SubscriptionRequest.RequestStatus.APPROVED);
        req.setAssignedBy(adminId);
        req.setAssignedAt(LocalDateTime.now());
        req.setUpdatedAt(LocalDateTime.now());
        requestRepository.save(req);

        notificationService.createNotification(req.getCustomerId(), "SUBSCRIPTION_APPROVED",
                "Subscription Approved",
                "Your subscription to " + req.getPlanName() + " has been approved!",
                "/subscriptions/my");
        return sub;
    }

    @Transactional
    public void rejectRequest(Long requestId, Long adminId, String reason) {
        SubscriptionRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Request not found"));
        if (req.getStatus() != SubscriptionRequest.RequestStatus.PENDING) {
            throw new RuntimeException("Request already processed");
        }
        req.setStatus(SubscriptionRequest.RequestStatus.REJECTED);
        req.setAssignedBy(adminId);
        req.setAssignedAt(LocalDateTime.now());
        req.setUpdatedAt(LocalDateTime.now());
        req.setNotes(reason);
        requestRepository.save(req);

        notificationService.createNotification(req.getCustomerId(), "SUBSCRIPTION_REJECTED",
                "Subscription Rejected",
                "Your subscription request was rejected. Reason: " + (reason != null ? reason : "Not specified"),
                "/subscriptions/plans");
    }

    public List<SubscriptionRequest> getPendingRequests(Long merchantId) {
        Long tenantId = TenantContext.getRequiredTenantId();
        return requestRepository.findPendingByMerchantId(tenantId, merchantId);
    }

    public List<SubscriptionRequest> getRequestsForCustomer(Long customerId) {
        return requestRepository.findByCustomerId(customerId);
    }

    public List<SubscriptionRequest> getAllRequestsForMerchant(Long merchantId) {
        return requestRepository.findByMerchantIdAndStatus(merchantId,
                SubscriptionRequest.RequestStatus.PENDING);
    }

    public long getPendingRequestCount(Long merchantId) {
        return requestRepository.countByMerchantIdAndStatus(merchantId,
                SubscriptionRequest.RequestStatus.PENDING);
    }

    // ============ PLANS ============

    public List<SubscriptionPlan> getPlansForTenant(Long tenantId) {
        return planRepository.findActiveByTenantId(tenantId);
    }

    public SubscriptionPlan getPlanById(Long id) {
        return planRepository.findById(id).orElseThrow(() -> new RuntimeException("Plan not found: " + id));
    }

    @Transactional
    public SubscriptionPlan createPlan(SubscriptionPlan plan) {
        plan.setTenantId(TenantContext.getRequiredTenantId());
        return planRepository.save(plan);
    }

    @Transactional
    public SubscriptionPlan updatePlan(Long id, SubscriptionPlan updated) {
        SubscriptionPlan plan = planRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Plan not found"));
        plan.setName(updated.getName());
        plan.setDescription(updated.getDescription());
        plan.setBillingPeriod(updated.getBillingPeriod());
        plan.setPrice(updated.getPrice());
        plan.setCurrency(updated.getCurrency());
        plan.setActive(updated.isActive());
        plan.setTrialDays(updated.getTrialDays());
        plan.setUpdatedAt(LocalDateTime.now());
        return planRepository.save(plan);
    }

    @Transactional
    public void deletePlan(Long id) {
        SubscriptionPlan plan = planRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Plan not found"));
        plan.setActive(false);
        plan.setUpdatedAt(LocalDateTime.now());
        planRepository.save(plan);
    }

    // ============ SUBSCRIPTIONS ============

    @Transactional
    public CustomerSubscription createSubscription(Long customerId, String customerEmail,
                                                   Long merchantId, Long planId) {
        List<CustomerSubscription> existing = subscriptionRepository
                .findByCustomerIdAndPlanIdAndStatus(customerId, planId,
                        CustomerSubscription.SubscriptionStatus.ACTIVE)
                .stream().toList();
        if (!existing.isEmpty()) {
            throw new RuntimeException("Customer already has an active subscription to this plan");
        }
        return createSubscriptionInternal(customerId, customerEmail, merchantId, planId);
    }

    private CustomerSubscription createSubscriptionInternal(Long customerId, String customerEmail,
                                                             Long merchantId, Long planId) {
        Long tenantId = TenantContext.getRequiredTenantId();
        SubscriptionPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new RuntimeException("Plan not found"));

        CustomerSubscription sub = new CustomerSubscription();
        sub.setPlan(plan);
        sub.setCustomerId(customerId);
        sub.setCustomerEmail(customerEmail);
        sub.setMerchantId(merchantId);
        sub.setTenantId(tenantId);

        LocalDateTime now = LocalDateTime.now();
        sub.setStartDate(now);
        if (plan.getTrialDays() != null && plan.getTrialDays() > 0) {
            sub.setStatus(CustomerSubscription.SubscriptionStatus.TRIALING);
            sub.setTrialEndDate(now.plusDays(plan.getTrialDays()));
            sub.setNextBillingDate(now.plusDays(plan.getTrialDays()));
        } else {
            sub.setStatus(CustomerSubscription.SubscriptionStatus.ACTIVE);
            sub.setNextBillingDate(now.plusMonths(plan.getMonthsInPeriod()));
        }
        CustomerSubscription saved = subscriptionRepository.save(sub);

        notificationService.createNotification(customerId, "SUBSCRIPTION_CREATED",
                "Subscription Created",
                "You have subscribed to " + plan.getName() + " (" + plan.getBillingPeriod() + ")",
                "/subscriptions/my");
        notificationService.createNotification(merchantId, "NEW_SUBSCRIPTION",
                "New Subscription",
                "Customer " + customerEmail + " subscribed to " + plan.getName(),
                "/merchant/subscriptions");
        return saved;
    }

    @Transactional
    public CustomerSubscription upgradeSubscription(Long subscriptionId, Long newPlanId) {
        CustomerSubscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));
        SubscriptionPlan oldPlan = sub.getPlan();
        SubscriptionPlan newPlan = planRepository.findById(newPlanId)
                .orElseThrow(() -> new RuntimeException("New plan not found"));
        if (oldPlan.getId().equals(newPlanId)) {
            throw new RuntimeException("Already subscribed to this plan");
        }
        LocalDateTime now = LocalDateTime.now();
        BigDecimal prorated = calculateProratedCharge(sub, newPlan);
        if (prorated.compareTo(BigDecimal.ZERO) > 0) {
            Invoice invoice = invoiceService.createInvoice(prorated,
                    "Subscription Upgrade: " + oldPlan.getName() + " → " + newPlan.getName(),
                    sub.getCustomerEmail(), sub.getMerchantId(), false, newPlan.getCurrency());
            log.info("Prorated invoice #{} created for upgrade", invoice.getId());
        }
        sub.setPlan(newPlan);
        sub.setNextBillingDate(now.plusMonths(newPlan.getMonthsInPeriod()));
        sub.setUpdatedAt(now);
        CustomerSubscription updated = subscriptionRepository.save(sub);

        notificationService.createNotification(sub.getCustomerId(), "SUBSCRIPTION_UPGRADED",
                "Subscription Upgraded",
                "Your subscription has been upgraded to " + newPlan.getName(),
                "/subscriptions/my");
        notificationService.createNotification(sub.getMerchantId(), "SUBSCRIPTION_UPGRADED_MERCHANT",
                "Subscription Upgraded",
                "Customer " + sub.getCustomerEmail() + " upgraded to " + newPlan.getName(),
                "/merchant/subscriptions");
        return updated;
    }

    @Transactional
    public CustomerSubscription downgradeSubscription(Long subscriptionId, Long newPlanId) {
        CustomerSubscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));
        SubscriptionPlan newPlan = planRepository.findById(newPlanId)
                .orElseThrow(() -> new RuntimeException("New plan not found"));
        sub.setPlan(newPlan);
        sub.setNextBillingDate(LocalDateTime.now().plusMonths(newPlan.getMonthsInPeriod()));
        sub.setUpdatedAt(LocalDateTime.now());
        CustomerSubscription updated = subscriptionRepository.save(sub);

        notificationService.createNotification(sub.getCustomerId(), "SUBSCRIPTION_DOWNGRADED",
                "Subscription Downgraded",
                "Your subscription has been downgraded to " + newPlan.getName(),
                "/subscriptions/my");
        return updated;
    }

    private BigDecimal calculateProratedCharge(CustomerSubscription sub, SubscriptionPlan newPlan) {
        SubscriptionPlan oldPlan = sub.getPlan();
        if (newPlan.getPrice().compareTo(oldPlan.getPrice()) <= 0) return BigDecimal.ZERO;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextBilling = sub.getNextBillingDate();
        long daysRemaining = java.time.Duration.between(now, nextBilling).toDays();
        if (daysRemaining <= 0) return BigDecimal.ZERO;
        long totalDays = oldPlan.getMonthsInPeriod() * 30L;
        BigDecimal daily = oldPlan.getPrice().divide(BigDecimal.valueOf(totalDays), 4, RoundingMode.HALF_UP);
        BigDecimal unused = daily.multiply(BigDecimal.valueOf(daysRemaining));
        BigDecimal diff = newPlan.getPrice().subtract(oldPlan.getPrice());
        return unused.add(diff).setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional
    public void cancelSubscription(Long subscriptionId) {
        CustomerSubscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));
        sub.setStatus(CustomerSubscription.SubscriptionStatus.CANCELLED);
        sub.setCancelledAt(LocalDateTime.now());
        sub.setUpdatedAt(LocalDateTime.now());
        subscriptionRepository.save(sub);

        notificationService.createNotification(sub.getCustomerId(), "SUBSCRIPTION_CANCELLED",
                "Subscription Cancelled",
                "Your subscription has been cancelled",
                "/subscriptions/my");
    }

    // ============ BILLING ============

    @Transactional
    public void processBilling() {
        LocalDateTime now = LocalDateTime.now();
        List<CustomerSubscription> due = subscriptionRepository.findActiveSubscriptionsDueForBilling(now);
        if (due.isEmpty()) return;
        int success = 0, fail = 0;
        for (CustomerSubscription sub : due) {
            try {
                processSingleBilling(sub);
                success++;
            } catch (Exception e) {
                fail++;
                log.error("Billing failed for subscription {}: {}", sub.getId(), e.getMessage());
                createRetry(sub, e.getMessage());
            }
        }
        log.info("Billing completed: {} successful, {} failed", success, fail);
    }

    @Transactional
    public void processSingleBilling(CustomerSubscription sub) {
        SubscriptionPlan plan = sub.getPlan();
        Invoice invoice = invoiceService.createInvoice(plan.getPrice(),
                "Subscription: " + plan.getName() + " (" + plan.getBillingPeriod() + ")",
                sub.getCustomerEmail(), sub.getMerchantId(), false, plan.getCurrency());

        sub.setLastBillingDate(LocalDateTime.now());
        sub.setNextBillingDate(LocalDateTime.now().plusMonths(plan.getMonthsInPeriod()));
        sub.setUpdatedAt(LocalDateTime.now());
        subscriptionRepository.save(sub);

        notificationService.createNotification(sub.getCustomerId(), "BILLING_CONFIRMATION",
                "Billing Confirmation",
                "Your subscription " + plan.getName() + " has been billed: "
                        + plan.getPrice() + " " + plan.getCurrency(),
                "/invoices/" + invoice.getId());
    }

    @Transactional
    public void createRetry(CustomerSubscription sub, String errorMessage) {
        PaymentRetry retry = new PaymentRetry();
        retry.setSubscriptionId(sub.getId());
        retry.setAttemptNumber(1);
        retry.setMaxAttempts(3);
        retry.setStatus(PaymentRetry.RetryStatus.PENDING);
        retry.setErrorMessage(errorMessage);
        retry.setNextAttemptAt(LocalDateTime.now().plusHours(24));
        paymentRetryRepository.save(retry);
    }

    /**
     * ✅ Iterates ALL tenants. Previous version defaulted to tenant 1 only —
     *    subscription billing silently no-op'd for every other tenant.
     */
    @Scheduled(cron = "0 0 6 * * *")
    public void scheduledBilling() {
        List<Tenant> tenants = tenantRepository.findAll();
        int totalSuccessful = 0;
        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                processBilling();
                totalSuccessful++;
            } catch (Exception e) {
                log.error("Scheduled billing failed for tenant {}", tenant.getId(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Scheduled billing loop completed for {}/{} tenants", totalSuccessful, tenants.size());
    }

    @Scheduled(cron = "0 30 6 * * *")
    public void processRetries() {
        List<Tenant> tenants = tenantRepository.findAll();
        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                List<PaymentRetry> retries = paymentRetryRepository
                        .findByStatusAndNextAttemptAtBefore(PaymentRetry.RetryStatus.PENDING,
                                LocalDateTime.now());
                for (PaymentRetry retry : retries) {
                    try {
                        CustomerSubscription sub = subscriptionRepository
                                .findById(retry.getSubscriptionId()).orElse(null);
                        if (sub == null
                                || sub.getStatus() != CustomerSubscription.SubscriptionStatus.ACTIVE) {
                            retry.setStatus(PaymentRetry.RetryStatus.CANCELLED);
                            paymentRetryRepository.save(retry);
                            continue;
                        }
                        processSingleBilling(sub);
                        retry.setStatus(PaymentRetry.RetryStatus.SUCCESS);
                        paymentRetryRepository.save(retry);
                    } catch (Exception e) {
                        retry.incrementAttempt();
                        if (retry.canRetry()) {
                            retry.setNextAttemptAt(LocalDateTime.now()
                                    .plusHours(24L * retry.getAttemptNumber()));
                        } else {
                            retry.setStatus(PaymentRetry.RetryStatus.FAILED);
                            notificationService.createNotification(sub_merchant(retry),
                                    "PAYMENT_RETRY_FAILED",
                                    "Payment Retry Failed",
                                    "All retry attempts failed for subscription #" + retry.getSubscriptionId(),
                                    "/merchant/subscriptions");
                        }
                        retry.setErrorMessage(e.getMessage());
                        paymentRetryRepository.save(retry);
                    }
                }
            } catch (Exception e) {
                log.error("Retry processing failed for tenant {}", tenant.getId(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    private Long sub_merchant(PaymentRetry retry) {
        return subscriptionRepository.findById(retry.getSubscriptionId())
                .map(CustomerSubscription::getMerchantId).orElse(1L);
    }

    // ============ QUERIES ============

    public CustomerSubscription getSubscriptionById(Long id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));
    }

    public List<CustomerSubscription> getCustomerSubscriptions(Long customerId) {
        return subscriptionRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    public List<CustomerSubscription> getMerchantSubscriptions(Long merchantId) {
        return subscriptionRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    public Page<CustomerSubscription> getMerchantSubscriptionsPaginated(Long merchantId, Pageable pageable) {
        return subscriptionRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
    }

    public Map<String, Object> getMerchantStats(Long merchantId) {
        Map<String, Object> stats = new HashMap<>();
        try {
            long active = subscriptionRepository.countByMerchantIdAndStatus(merchantId,
                    CustomerSubscription.SubscriptionStatus.ACTIVE);
            long trialing = subscriptionRepository.countByMerchantIdAndStatus(merchantId,
                    CustomerSubscription.SubscriptionStatus.TRIALING);
            long pending = requestRepository.countByMerchantIdAndStatus(merchantId,
                    SubscriptionRequest.RequestStatus.PENDING);
            List<CustomerSubscription> subs = subscriptionRepository
                    .findByMerchantIdOrderByCreatedAtDesc(merchantId);
            BigDecimal revenue = subs.stream()
                    .filter(s -> s.getStatus() == CustomerSubscription.SubscriptionStatus.ACTIVE)
                    .map(s -> s.getPlan().getPrice())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            stats.put("activeSubscriptions", active);
            stats.put("trialingSubscriptions", trialing);
            stats.put("totalSubscriptions", (long) subs.size());
            stats.put("monthlyRevenue", revenue);
            stats.put("annualRevenue", revenue.multiply(BigDecimal.valueOf(12)));
            stats.put("pendingRequests", pending);
        } catch (Exception e) {
            log.error("Error getting merchant stats", e);
            stats.put("activeSubscriptions", 0L);
            stats.put("trialingSubscriptions", 0L);
            stats.put("totalSubscriptions", 0L);
            stats.put("monthlyRevenue", BigDecimal.ZERO);
            stats.put("annualRevenue", BigDecimal.ZERO);
            stats.put("pendingRequests", 0L);
        }
        return stats;
    }
}