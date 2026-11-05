package com.enterprise.subscription;

import com.enterprise.audit.AuditService;
import com.enterprise.feature.FeatureFlagService;
import com.enterprise.idempotency.IdempotencyKey;
import com.enterprise.idempotency.IdempotencyKeyRepository;
import com.enterprise.notification.EmailNotificationService;
import com.enterprise.notification.NotificationService;
import com.enterprise.plan.PlatformPlan;
import com.enterprise.plan.PlatformPlanRepository;
import com.enterprise.reliability.Reliable;
import com.enterprise.signup.SignupRequest;
import com.enterprise.signup.SignupRequestRepository;
import com.enterprise.tenant.Tenant;
import com.enterprise.tenant.TenantRepository;
import com.enterprise.transaction.Transaction;
import com.enterprise.transaction.TransactionRepository;
import com.enterprise.transaction.TransactionStatus;
import com.enterprise.webhook.WebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class SubscriptionChargeService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionChargeService.class);

    private final SignupRequestRepository signupRepository;
    private final TenantRepository tenantRepository;
    private final PlatformPlanRepository planRepository;
    private final TransactionRepository transactionRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final SubscriptionEventRepository eventRepository;
    private final NotificationService notificationService;
    private final EmailNotificationService emailService;
    private final AuditService auditService;
    private final WebhookService webhookService;
    private final FeatureFlagService featureFlagService;
    private final ObjectMapper objectMapper;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    public SubscriptionChargeService(SignupRequestRepository signupRepository,
                                     TenantRepository tenantRepository,
                                     PlatformPlanRepository planRepository,
                                     TransactionRepository transactionRepository,
                                     IdempotencyKeyRepository idempotencyKeyRepository,
                                     SubscriptionEventRepository eventRepository,
                                     NotificationService notificationService,
                                     EmailNotificationService emailService,
                                     AuditService auditService,
                                     WebhookService webhookService,
                                     FeatureFlagService featureFlagService,
                                     ObjectMapper objectMapper) {
        this.signupRepository = signupRepository;
        this.tenantRepository = tenantRepository;
        this.planRepository = planRepository;
        this.transactionRepository = transactionRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.eventRepository = eventRepository;
        this.notificationService = notificationService;
        this.emailService = emailService;
        this.auditService = auditService;
        this.webhookService = webhookService;
        this.featureFlagService = featureFlagService;
        this.objectMapper = objectMapper;
    }

    // ============================================================
    // CHARGE FOR SIGNUP  (called from /signup/payment POST)
    // ============================================================
    @Reliable
    @Transactional
    public Transaction chargeSignup(Long signupId,
                                    String idempotencyKey,
                                    String cardNumber,
                                    String expiry,
                                    String cvv) {

        // ---------- 1. Idempotency ----------
        var existing = idempotencyKeyRepository.findById(idempotencyKey);
        if (existing.isPresent()) {
            try {
                Long txId = objectMapper.readTree(existing.get().getResponse())
                        .path("transactionId").asLong();
                if (txId > 0) {
                    log.info("Reusing existing subscription charge via idempotency key {}", idempotencyKey);
                    return transactionRepository.findById(txId).orElseThrow();
                }
            } catch (Exception e) {
                log.warn("Could not parse cached subscription charge response: {}", e.getMessage());
            }
        }

        // ---------- 2. Load signup ----------
        SignupRequest sr = signupRepository.findById(signupId)
                .orElseThrow(() -> new IllegalArgumentException("Signup not found"));

        if (sr.getStatus() != SignupRequest.Status.APPROVED_AWAITING_PAYMENT) {
            throw new IllegalStateException("Signup is not awaiting payment (status=" + sr.getStatus() + ")");
        }

        PlatformPlan plan = planRepository.findByCode(sr.getPlanCode())
                .orElseThrow(() -> new IllegalStateException("Plan not found: " + sr.getPlanCode()));

        Tenant tenant = tenantRepository.findById(sr.getTenantId())
                .orElseThrow(() -> new IllegalStateException("Tenant not found"));

        // ---------- 3. Simulator: accept even last digit ----------
        String digitsOnly = cardNumber == null ? "" : cardNumber.replaceAll("\\D", "");
        boolean approved = !digitsOnly.isEmpty()
                && Character.getNumericValue(digitsOnly.charAt(digitsOnly.length() - 1)) % 2 == 0;

        // ---------- 4. Create transaction record (platform subscription) ----------
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodEnd = now.plusMonths(1);

        BigDecimal amount = BigDecimal.valueOf(plan.getMonthlyPricePence())
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);

        Transaction tx = new Transaction();
        tx.setInvoiceId(0L);                       // no invoice for subscription charges
        tx.setCustomerId(sr.getUserId());          // the signup pays
        tx.setMerchantId(0L);                      // platform, not a merchant
        tx.setAmount(amount);
        tx.setCurrency(plan.getCurrency());
        tx.setStatus(approved ? TransactionStatus.SETTLED : TransactionStatus.FAILED);
        tx.setIdempotencyKey(idempotencyKey);
        tx.setTenantId(tenant.getId());
        tx.setTransactionType("PLATFORM_SUBSCRIPTION");
        tx.setSubscriptionPlanId(plan.getId());
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);
        tx = transactionRepository.save(tx);

        // ---------- 5. Failed path ----------
        if (!approved) {
            recordEvent(tenant.getId(), "PAYMENT_FAILED", null, plan.getId(), tx.getId(),
                    "Simulator declined card ending in odd digit", sr.getUserId());
            log.info("❌ Subscription charge declined: signup={}, tx={}", signupId, tx.getId());
            throw new IllegalStateException("Card declined by simulator. Use a card ending in an even digit (0,2,4,6,8).");
        }

        // ---------- 6. Success: update tenant ----------
        tenant.setSubscriptionStatus("ACTIVE");
        tenant.setCurrentPlanId(plan.getId());
        tenant.setPlanCode(plan.getCode());
        tenant.setBillingPeriod("MONTHLY");
        tenant.setCurrentPeriodStartedAt(now);
        tenant.setSubscriptionRenewsAt(periodEnd);
        tenant.setTrialEndsAt(null);
        tenant.setActive(true);
        tenant.setUpdatedAt(now);
        tenantRepository.save(tenant);

        // ---------- 7. Mark signup as PAID ----------
        sr.setStatus(SignupRequest.Status.PAID);
        sr.setPaidAt(now);
        sr.setUpdatedAt(now);
        signupRepository.save(sr);

        // ---------- 8. Lifecycle event ----------
        recordEvent(tenant.getId(), "SUBSCRIBED", null, plan.getId(), tx.getId(),
                "Activated via signup payment", sr.getUserId());

        // ---------- 9. In-app notification ----------
        try {
            notificationService.createNotification(
                    sr.getUserId(),
                    "SUBSCRIPTION_ACTIVE",
                    "Welcome — your subscription is active",
                    "You're on the " + plan.getName() + " plan. Renews " + periodEnd.toLocalDate() + ".",
                    "/account/plan"
            );
        } catch (Exception e) {
            log.warn("In-app notification failed: {}", e.getMessage());
        }

        // ---------- 10. Confirmation email ----------
        try {
            String subject = "Your PayFlow subscription is active";
            String html = buildSubscriptionActiveEmail(sr.getFullName(), plan.getName(),
                    plan.getFormattedMonthlyPrice(), periodEnd.toLocalDate().toString());
            emailService.sendHtmlEmailAsync(sr.getEmail(), subject, html);
        } catch (Exception e) {
            log.warn("Email notification failed: {}", e.getMessage());
        }

        // ---------- 11. Audit trail (hash-chained) ----------
        try {
            Map<String, Object> after = new HashMap<>();
            after.put("transactionId", tx.getId());
            after.put("tenantId", tenant.getId());
            after.put("planCode", plan.getCode());
            after.put("amount", amount.toPlainString());
            after.put("currency", plan.getCurrency());
            after.put("status", tx.getStatus().name());
            after.put("renewsAt", periodEnd.toString());

            auditService.recordEvent(
                    "SUBSCRIPTION_ACTIVATED",
                    sr.getUserId(),
                    after,
                    "Subscription",
                    tenant.getId(),
                    null,
                    after
            );
        } catch (Exception e) {
            log.warn("Audit recording failed: {}", e.getMessage());
        }

        // ---------- 12. Webhook fan-out (reuses existing infra) ----------
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", "SUBSCRIPTION_ACTIVATED");
            payload.put("tenantId", tenant.getId());
            payload.put("userId", sr.getUserId());
            payload.put("planCode", plan.getCode());
            payload.put("planName", plan.getName());
            payload.put("amount", amount.toPlainString());
            payload.put("currency", plan.getCurrency());
            payload.put("transactionId", tx.getId());
            payload.put("renewsAt", periodEnd.toString());
            payload.put("paidAt", now.toString());

            webhookService.sendWebhooks("SUBSCRIPTION_ACTIVATED", payload);
            log.info("✅ Subscription webhook dispatched (tenant={}, tx={})",
                    tenant.getId(), tx.getId());
        } catch (Exception e) {
            log.warn("Webhook dispatch failed: {}", e.getMessage());
        }

        // ---------- 13. Cache idempotent response ----------
        try {
            IdempotencyKey ik = new IdempotencyKey();
            ik.setKey(idempotencyKey);
            ik.setResponse(objectMapper.writeValueAsString(
                    Map.of("transactionId", tx.getId(), "status", "SETTLED")));
            ik.setExpiresAt(LocalDateTime.now().plusDays(30));
            ik.setRequestFingerprint(DigestUtils.sha256Hex(
                    "sub:" + signupId + ":" + plan.getId()));
            ik.setTenantId(tenant.getId());
            idempotencyKeyRepository.save(ik);
        } catch (Exception e) {
            log.warn("Idempotency cache write failed: {}", e.getMessage());
        }

        log.info("✅ Subscription charge settled: signup={}, tenant={}, plan={}, tx={}",
                signupId, tenant.getId(), plan.getCode(), tx.getId());
        return tx;
    }

    // ============================================================
    // SELF-SERVICE CHANGE (upgrade / downgrade)
    // ============================================================
    @Transactional
    public void changePlan(Long tenantId, String newPlanCode, Long actorUserId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found"));
        PlatformPlan newPlan = planRepository.findByCode(newPlanCode)
                .orElseThrow(() -> new IllegalStateException("Plan not found: " + newPlanCode));

        Long oldPlanId = tenant.getCurrentPlanId();
        PlatformPlan oldPlan = oldPlanId != null ? planRepository.findById(oldPlanId).orElse(null) : null;

        if (oldPlan != null && oldPlan.getCode().equals(newPlanCode)) {
            throw new IllegalStateException("Already on the " + newPlan.getName() + " plan");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodEnd = now.plusMonths(1);

        BigDecimal amount = BigDecimal.valueOf(newPlan.getMonthlyPricePence())
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);

        Transaction tx = new Transaction();
        tx.setInvoiceId(0L);
        tx.setCustomerId(actorUserId);
        tx.setMerchantId(0L);
        tx.setAmount(amount);
        tx.setCurrency(newPlan.getCurrency());
        tx.setStatus(TransactionStatus.SETTLED);
        tx.setIdempotencyKey("planchange-" + UUID.randomUUID());
        tx.setTenantId(tenantId);
        tx.setTransactionType("PLATFORM_SUBSCRIPTION");
        tx.setSubscriptionPlanId(newPlan.getId());
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);
        tx = transactionRepository.save(tx);

        tenant.setCurrentPlanId(newPlan.getId());
        tenant.setPlanCode(newPlan.getCode());
        tenant.setCurrentPeriodStartedAt(now);
        tenant.setSubscriptionRenewsAt(periodEnd);
        tenant.setSubscriptionStatus("ACTIVE");
        tenant.setCancelAtPeriodEnd(false);
        tenant.setCancelledAt(null);
        tenant.setUpdatedAt(now);
        tenantRepository.save(tenant);

        String eventType = oldPlan == null ? "SUBSCRIBED"
                : newPlan.getMonthlyPricePence() > oldPlan.getMonthlyPricePence() ? "UPGRADED"
                : "DOWNGRADED";

        recordEvent(tenantId, eventType,
                oldPlan != null ? oldPlan.getId() : null,
                newPlan.getId(), tx.getId(),
                "Self-service plan change", actorUserId);

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", "SUBSCRIPTION_" + eventType);
            payload.put("tenantId", tenantId);
            payload.put("fromPlanCode", oldPlan != null ? oldPlan.getCode() : null);
            payload.put("toPlanCode", newPlan.getCode());
            payload.put("transactionId", tx.getId());
            payload.put("actorUserId", actorUserId);
            payload.put("changedAt", now.toString());
            webhookService.sendWebhooks("SUBSCRIPTION_" + eventType, payload);
        } catch (Exception e) {
            log.warn("Plan-change webhook failed: {}", e.getMessage());
        }

        log.info("✅ Plan changed: tenant={}, {} → {}",
                tenantId, oldPlan != null ? oldPlan.getCode() : "none", newPlan.getCode());
    }

    // ============================================================
    // CANCEL / RESUME
    // ============================================================
    @Transactional
    public void cancelAtPeriodEnd(Long tenantId, Long actorUserId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found"));

        if ("CANCELLED".equals(tenant.getSubscriptionStatus())) {
            throw new IllegalStateException("Subscription is already cancelled");
        }

        tenant.setCancelAtPeriodEnd(true);
        tenant.setCancelledAt(LocalDateTime.now());
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantRepository.save(tenant);

        recordEvent(tenantId, "CANCELLED", tenant.getCurrentPlanId(), null, null,
                "Cancelled at period end", actorUserId);

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", "SUBSCRIPTION_CANCELLED");
            payload.put("tenantId", tenantId);
            payload.put("planId", tenant.getCurrentPlanId());
            payload.put("actorUserId", actorUserId);
            payload.put("cancelAt", tenant.getSubscriptionRenewsAt());
            webhookService.sendWebhooks("SUBSCRIPTION_CANCELLED", payload);
        } catch (Exception e) {
            log.warn("Cancel webhook failed: {}", e.getMessage());
        }

        log.info("✅ Subscription cancel-at-period-end: tenant={}", tenantId);
    }

    @Transactional
    public void resumeSubscription(Long tenantId, Long actorUserId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found"));

        if (!tenant.isCancelAtPeriodEnd()) {
            throw new IllegalStateException("Subscription is not scheduled for cancellation");
        }

        tenant.setCancelAtPeriodEnd(false);
        tenant.setCancelledAt(null);
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantRepository.save(tenant);

        recordEvent(tenantId, "RESUMED", null, tenant.getCurrentPlanId(), null,
                "Cancellation reverted", actorUserId);

        log.info("✅ Subscription resumed: tenant={}", tenantId);
    }

    // ============================================================
    // READS
    // ============================================================
    public PlatformPlan getCurrentPlan(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null || tenant.getCurrentPlanId() == null) return null;
        return planRepository.findById(tenant.getCurrentPlanId()).orElse(null);
    }

    public java.util.List<Transaction> getPaymentHistory(Long tenantId) {
        return transactionRepository.findAll().stream()
                .filter(t -> tenantId.equals(t.getTenantId()))
                .filter(t -> "PLATFORM_SUBSCRIPTION".equals(t.getTransactionType()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(20)
                .toList();
    }

    public java.util.List<SubscriptionEvent> getEventHistory(Long tenantId) {
        return eventRepository.findTop20ByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public java.util.List<PlatformPlan> allActivePlans() {
        return planRepository.findByActiveTrueOrderByDisplayOrderAsc();
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private void recordEvent(Long tenantId, String type,
                             Long fromPlanId, Long toPlanId, Long txId,
                             String notes, Long actorUserId) {
        SubscriptionEvent e = new SubscriptionEvent();
        e.setTenantId(tenantId);
        e.setEventType(type);
        e.setFromPlanId(fromPlanId);
        e.setToPlanId(toPlanId);
        e.setTransactionId(txId);
        e.setNotes(notes);
        e.setActorUserId(actorUserId);
        eventRepository.save(e);
    }

    private String buildSubscriptionActiveEmail(String name, String planName,
                                                 String price, String renewalDate) {
        String safeName = name == null || name.isBlank() ? "there" : name;
        return """
            <html>
            <body style="font-family:'Segoe UI',Arial,sans-serif;background:#f0f4f8;padding:32px;margin:0;">
              <div style="max-width:560px;margin:0 auto;background:white;border-radius:16px;
                          overflow:hidden;box-shadow:0 8px 24px rgba(11,26,51,0.08);">
                <div style="background:linear-gradient(135deg,#0b1a33,#1a3a6e);padding:28px;color:white;">
                  <h1 style="margin:0;font-size:1.375rem;font-weight:500;">Subscription active 🎉</h1>
                  <p style="margin:6px 0 0;opacity:0.85;font-size:0.875rem;">%s plan · %s / month</p>
                </div>
                <div style="padding:28px;">
                  <p style="font-size:0.9375rem;color:#334155;line-height:1.6;">Hi %s,</p>
                  <p style="font-size:0.9375rem;color:#334155;line-height:1.6;">
                    Your subscription is now active. Renews on <strong>%s</strong>.
                    You can manage your plan anytime from the dashboard.
                  </p>
                  <a href="%s/dashboard"
                     style="display:inline-block;background:linear-gradient(135deg,#0b1a33,#1a3a6e);
                            color:white;padding:12px 28px;border-radius:12px;text-decoration:none;
                            font-weight:400;margin-top:12px;">Open dashboard →</a>
                </div>
              </div>
            </body>
            </html>
            """.formatted(planName, price, safeName, renewalDate, baseUrl);
    }
}