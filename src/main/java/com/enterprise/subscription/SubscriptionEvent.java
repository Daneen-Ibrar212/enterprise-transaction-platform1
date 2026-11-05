package com.enterprise.subscription;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "subscription_event")
public class SubscriptionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "from_plan_id")
    private Long fromPlanId;

    @Column(name = "to_plan_id")
    private Long toPlanId;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Getters and setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public Long getFromPlanId() { return fromPlanId; }
    public void setFromPlanId(Long fromPlanId) { this.fromPlanId = fromPlanId; }

    public Long getToPlanId() { return toPlanId; }
    public void setToPlanId(Long toPlanId) { this.toPlanId = toPlanId; }

    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}