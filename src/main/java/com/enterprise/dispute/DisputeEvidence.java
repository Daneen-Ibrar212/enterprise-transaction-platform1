package com.enterprise.dispute;

import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

@Entity
@Table(name = "dispute_evidence")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class DisputeEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dispute_id", nullable = false)
    private Long disputeId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "evidence_type", nullable = false)
    private String evidenceType;  // RECEIPT, SHIPPING, COMMUNICATION, REFUND_POLICY, OTHER

    @Column(columnDefinition = "TEXT")
    private String description;

    private String filename;

    @Column(name = "content_type")
    private String contentType;

    @Column(columnDefinition = "BYTEA")
    private byte[] content;

    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;

    @Column(name = "submitted_at", updatable = false)
    private LocalDateTime submittedAt = LocalDateTime.now();

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDisputeId() { return disputeId; }
    public void setDisputeId(Long disputeId) { this.disputeId = disputeId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getEvidenceType() { return evidenceType; }
    public void setEvidenceType(String evidenceType) { this.evidenceType = evidenceType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
    public Long getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(Long submittedBy) { this.submittedBy = submittedBy; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
}