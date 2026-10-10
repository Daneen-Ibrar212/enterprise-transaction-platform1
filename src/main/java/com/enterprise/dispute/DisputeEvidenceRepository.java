package com.enterprise.dispute;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DisputeEvidenceRepository extends JpaRepository<DisputeEvidence, Long> {

    // Tenant-scoped (Merchant Admin)
    List<DisputeEvidence> findByDisputeIdOrderBySubmittedAtAsc(Long disputeId);

    // Cross-tenant (Super Admin) — native SQL bypasses @Filter
    @Query(value = "SELECT * FROM dispute_evidence WHERE dispute_id = :disputeId ORDER BY submitted_at ASC",
           nativeQuery = true)
    List<DisputeEvidence> findByDisputeIdAcrossTenantsOrderBySubmittedAtAsc(
            @Param("disputeId") Long disputeId);
}