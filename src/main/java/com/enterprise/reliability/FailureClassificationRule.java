package com.enterprise.reliability;

import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

@Entity
@Table(name = "failure_classification_rule")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class FailureClassificationRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "condition_expression", nullable = false, columnDefinition = "TEXT")
    private String conditionExpression;

    @Column(nullable = false)
    private String category;   // TRANSIENT, PERMANENT, VALIDATION, INFRASTRUCTURE

    @Column(nullable = false)
    private String severity;   // LOW, MEDIUM, HIGH, CRITICAL

    @Column(nullable = false)
    private String action;     // RETRY, ROLLBACK, DLQ, IGNORE

    @Column(nullable = false)
    private int priority = 0;

    private boolean active = true;

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
    public String getConditionExpression() { return conditionExpression; }
    public void setConditionExpression(String conditionExpression) { this.conditionExpression = conditionExpression; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    // ✅ TENANT ID GETTER + SETTER
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
}