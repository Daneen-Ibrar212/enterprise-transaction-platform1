package com.enterprise.subscription;

import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "subscription_usage",
       uniqueConstraints = @UniqueConstraint(columnNames = {"subscription_id", "metric_name", "period_start"}))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class SubscriptionUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "metric_name", nullable = false)
    private String metricName;

    @Column(name = "usage_count", nullable = false)
    private Long usageCount = 0L;

    @Column(name = "included_quantity", nullable = false)
    private Long includedQuantity = 0L;

    @Column(name = "overage_rate", nullable = false, precision = 19, scale = 4)
    private BigDecimal overageRate = BigDecimal.ZERO;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    // Computed: overage units × rate
    @Transient
    public BigDecimal getOverageCharge() {
        if (usageCount == null || includedQuantity == null) return BigDecimal.ZERO;
        long over = Math.max(0, usageCount - includedQuantity);
        if (over == 0 || overageRate == null) return BigDecimal.ZERO;
        return overageRate.multiply(BigDecimal.valueOf(over));
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(Long subscriptionId) { this.subscriptionId = subscriptionId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }
    public Long getUsageCount() { return usageCount; }
    public void setUsageCount(Long usageCount) { this.usageCount = usageCount; }
    public Long getIncludedQuantity() { return includedQuantity; }
    public void setIncludedQuantity(Long includedQuantity) { this.includedQuantity = includedQuantity; }
    public BigDecimal getOverageRate() { return overageRate; }
    public void setOverageRate(BigDecimal overageRate) { this.overageRate = overageRate; }
    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}