package com.enterprise.reliability;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "backoff_policy")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class BackoffPolicy {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "retry_policy_id", nullable = false)
    private RetryPolicy retryPolicy;

    private BigDecimal multiplier;      // for exponential
    private Long linearIncrementMs;     // for linear
    private Long fixedDelayMs;          // for fixed

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ============================================================
    // ✅ TENANT ID
    // ============================================================
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public RetryPolicy getRetryPolicy() { return retryPolicy; }
    public void setRetryPolicy(RetryPolicy retryPolicy) { this.retryPolicy = retryPolicy; }
    public BigDecimal getMultiplier() { return multiplier; }
    public void setMultiplier(BigDecimal multiplier) { this.multiplier = multiplier; }
    public Long getLinearIncrementMs() { return linearIncrementMs; }
    public void setLinearIncrementMs(Long linearIncrementMs) { this.linearIncrementMs = linearIncrementMs; }
    public Long getFixedDelayMs() { return fixedDelayMs; }
    public void setFixedDelayMs(Long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    // ✅ TENANT ID GETTER + SETTER
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
}