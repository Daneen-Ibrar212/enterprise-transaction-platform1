package com.enterprise.subscription;

import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

@Entity
@Table(name = "customer_subscription")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class CustomerSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate;

    @Column(name = "end_date")
    private LocalDateTime endDate;

    @Column(name = "next_billing_date", nullable = false)
    private LocalDateTime nextBillingDate;

    @Column(name = "last_billing_date")
    private LocalDateTime lastBillingDate;

    @Column(name = "trial_end_date")
    private LocalDateTime trialEndDate;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    public enum SubscriptionStatus {
        ACTIVE, PAUSED, CANCELLED, EXPIRED, TRIALING
    }

       @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @Column(name = "pause_reason", columnDefinition = "TEXT")
    private String pauseReason;

    @Column(name = "usage_based", nullable = false)
    private boolean usageBased = false;

    // Getters and setters
    public LocalDateTime getPausedAt() { return pausedAt; }
    public void setPausedAt(LocalDateTime pausedAt) { this.pausedAt = pausedAt; }
    public String getPauseReason() { return pauseReason; }
    public void setPauseReason(String pauseReason) { this.pauseReason = pauseReason; }
    public boolean isUsageBased() { return usageBased; }
    public void setUsageBased(boolean usageBased) { this.usageBased = usageBased; }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public SubscriptionPlan getPlan() { return plan; }
    public void setPlan(SubscriptionPlan plan) { this.plan = plan; }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public SubscriptionStatus getStatus() { return status; }
    public void setStatus(SubscriptionStatus status) { this.status = status; }

    public LocalDateTime getStartDate() { return startDate; }
    public void setStartDate(LocalDateTime startDate) { this.startDate = startDate; }

    public LocalDateTime getEndDate() { return endDate; }
    public void setEndDate(LocalDateTime endDate) { this.endDate = endDate; }

    public LocalDateTime getNextBillingDate() { return nextBillingDate; }
    public void setNextBillingDate(LocalDateTime nextBillingDate) { this.nextBillingDate = nextBillingDate; }

    public LocalDateTime getLastBillingDate() { return lastBillingDate; }
    public void setLastBillingDate(LocalDateTime lastBillingDate) { this.lastBillingDate = lastBillingDate; }

    public LocalDateTime getTrialEndDate() { return trialEndDate; }
    public void setTrialEndDate(LocalDateTime trialEndDate) { this.trialEndDate = trialEndDate; }

    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    // Business methods
    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIALING;
    }

    public boolean isOnTrial() {
        return status == SubscriptionStatus.TRIALING && trialEndDate != null && trialEndDate.isAfter(LocalDateTime.now());
    }

    public boolean isTrialExpired() {
        return status == SubscriptionStatus.TRIALING && trialEndDate != null && trialEndDate.isBefore(LocalDateTime.now());
    }
}