package com.enterprise.tenant;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    private boolean active = true;

    @Column(name = "base_currency", nullable = false)
    private String baseCurrency = "GBP";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "reminder_first_days", nullable = false)
    private int reminderFirstDays = 7;

    @Column(name = "reminder_second_days", nullable = false)
    private int reminderSecondDays = 14;

    @Column(name = "reminder_final_days", nullable = false)
    private int reminderFinalDays = 30;

    @Column(name = "reminder_email_from")
    private String reminderEmailFrom;

    @Column(name = "plan_code", length = 50)
    private String planCode;

    @Column(name = "signup_at")
    private LocalDateTime signupAt;

    @Column(name = "current_plan_id")
    private Long currentPlanId;

    @Column(name = "billing_period", length = 20)
    private String billingPeriod = "MONTHLY";

    @Column(name = "current_period_started_at")
    private LocalDateTime currentPeriodStartedAt;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd = false;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    // ===== NEW FIELDS REQUIRED BY SubscriptionChargeService / SignupService =====
    @Column(name = "subscription_status", length = 20)
    private String subscriptionStatus;

    @Column(name = "subscription_renews_at")
    private LocalDateTime subscriptionRenewsAt;

    @Column(name = "trial_ends_at")
    private LocalDateTime trialEndsAt;
    // ============================================================================

    public String getPlanCode() { return planCode; }
    public void setPlanCode(String planCode) { this.planCode = planCode; }

    public LocalDateTime getSignupAt() { return signupAt; }
    public void setSignupAt(LocalDateTime signupAt) { this.signupAt = signupAt; }

    public int getReminderFirstDays() { return reminderFirstDays; }
    public void setReminderFirstDays(int reminderFirstDays) { this.reminderFirstDays = reminderFirstDays; }
    public int getReminderSecondDays() { return reminderSecondDays; }
    public void setReminderSecondDays(int reminderSecondDays) { this.reminderSecondDays = reminderSecondDays; }
    public int getReminderFinalDays() { return reminderFinalDays; }
    public void setReminderFinalDays(int reminderFinalDays) { this.reminderFinalDays = reminderFinalDays; }
    public String getReminderEmailFrom() { return reminderEmailFrom; }
    public void setReminderEmailFrom(String reminderEmailFrom) { this.reminderEmailFrom = reminderEmailFrom; }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getBaseCurrency() { return baseCurrency; }
    public void setBaseCurrency(String baseCurrency) { this.baseCurrency = baseCurrency; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getCurrentPlanId() { return currentPlanId; }
    public void setCurrentPlanId(Long currentPlanId) { this.currentPlanId = currentPlanId; }

    public String getBillingPeriod() { return billingPeriod; }
    public void setBillingPeriod(String billingPeriod) { this.billingPeriod = billingPeriod; }

    public LocalDateTime getCurrentPeriodStartedAt() { return currentPeriodStartedAt; }
    public void setCurrentPeriodStartedAt(LocalDateTime currentPeriodStartedAt) { this.currentPeriodStartedAt = currentPeriodStartedAt; }

    public boolean isCancelAtPeriodEnd() { return cancelAtPeriodEnd; }
    public void setCancelAtPeriodEnd(boolean cancelAtPeriodEnd) { this.cancelAtPeriodEnd = cancelAtPeriodEnd; }

    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }

    // ===== NEW GETTERS/SETTERS =====
    public String getSubscriptionStatus() { return subscriptionStatus; }
    public void setSubscriptionStatus(String subscriptionStatus) { this.subscriptionStatus = subscriptionStatus; }

    public LocalDateTime getSubscriptionRenewsAt() { return subscriptionRenewsAt; }
    public void setSubscriptionRenewsAt(LocalDateTime subscriptionRenewsAt) { this.subscriptionRenewsAt = subscriptionRenewsAt; }

    public LocalDateTime getTrialEndsAt() { return trialEndsAt; }
    public void setTrialEndsAt(LocalDateTime trialEndsAt) { this.trialEndsAt = trialEndsAt; }
}