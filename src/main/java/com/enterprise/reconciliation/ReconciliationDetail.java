package com.enterprise.reconciliation;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "reconciliation_detail", indexes = {
    @Index(name = "idx_recon_detail_tenant", columnList = "tenant_id"),
    @Index(name = "idx_recon_detail_record", columnList = "reconciliation_record_id")
})
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class ReconciliationDetail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "reconciliation_record_id", nullable = false)
    private ReconciliationRecord reconciliationRecord;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "discrepancy_type", nullable = false)
    private String discrepancyType;

    @Column(name = "expected_amount", precision = 19, scale = 2)
    private BigDecimal expectedAmount;

    @Column(name = "actual_amount", precision = 19, scale = 2)
    private BigDecimal actualAmount;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "repair_status", nullable = false)
    private String repairStatus = "PENDING";

    @Column(name = "repaired_at")
    private LocalDateTime repairedAt;

    @Column(name = "repaired_by")
    private Long repairedBy;

    @Column(name = "repair_notes", columnDefinition = "TEXT")
    private String repairNotes;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ReconciliationRecord getReconciliationRecord() { return reconciliationRecord; }
    public void setReconciliationRecord(ReconciliationRecord r) { this.reconciliationRecord = r; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }
    public String getDiscrepancyType() { return discrepancyType; }
    public void setDiscrepancyType(String discrepancyType) { this.discrepancyType = discrepancyType; }
    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public void setExpectedAmount(BigDecimal expectedAmount) { this.expectedAmount = expectedAmount; }
    public BigDecimal getActualAmount() { return actualAmount; }
    public void setActualAmount(BigDecimal actualAmount) { this.actualAmount = actualAmount; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRepairStatus() { return repairStatus; }
    public void setRepairStatus(String repairStatus) { this.repairStatus = repairStatus; }
    public LocalDateTime getRepairedAt() { return repairedAt; }
    public void setRepairedAt(LocalDateTime repairedAt) { this.repairedAt = repairedAt; }
    public Long getRepairedBy() { return repairedBy; }
    public void setRepairedBy(Long repairedBy) { this.repairedBy = repairedBy; }
    public String getRepairNotes() { return repairNotes; }
    public void setRepairNotes(String repairNotes) { this.repairNotes = repairNotes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}