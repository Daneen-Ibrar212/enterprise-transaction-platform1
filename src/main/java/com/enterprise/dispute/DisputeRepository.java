package com.enterprise.dispute;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    // ============================================================
    // Tenant-scoped (Merchant Admin)
    // ============================================================
    List<Dispute> findByTenantIdOrderByOpenedAtDesc(Long tenantId);

    List<Dispute> findByTenantIdAndStatusOrderByOpenedAtDesc(Long tenantId, DisputeStatus status);

    List<Dispute> findByTransactionId(Long transactionId);

    Optional<Dispute> findByExternalId(String externalId);

    @Query("SELECT d FROM Dispute d WHERE d.status IN ('OPENED','UNDER_REVIEW') AND d.respondBy BETWEEN :from AND :to")
    List<Dispute> findDueSoon(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT d FROM Dispute d WHERE d.status IN ('OPENED','UNDER_REVIEW') AND d.respondBy < :now")
    List<Dispute> findPastDeadline(@Param("now") LocalDateTime now);

    @Query("SELECT COUNT(d) FROM Dispute d WHERE d.tenantId = :tenantId AND d.status IN ('OPENED','UNDER_REVIEW','EVIDENCE_SUBMITTED')")
    long countOpenByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT d FROM Dispute d WHERE d.tenantId = :tenantId AND d.merchantId = :merchantId ORDER BY d.openedAt DESC")
    List<Dispute> findByTenantIdAndMerchantId(@Param("tenantId") Long tenantId,
                                              @Param("merchantId") Long merchantId);

    // ============================================================
    // Cross-tenant (Super Admin) — native SQL bypasses @Filter
    // ============================================================
    @Query(value = "SELECT * FROM dispute ORDER BY opened_at DESC", nativeQuery = true)
    List<Dispute> findAllAcrossTenantsOrderByOpenedAtDesc();

    @Query(value = "SELECT * FROM dispute WHERE status IN ('OPENED','UNDER_REVIEW','EVIDENCE_SUBMITTED')", nativeQuery = true)
    List<Dispute> findAllOpenAcrossTenants();

    @Query(value = "SELECT COUNT(*) FROM dispute WHERE status IN ('OPENED','UNDER_REVIEW','EVIDENCE_SUBMITTED')", nativeQuery = true)
    long countAllOpen();

    @Query(value = "SELECT * FROM dispute WHERE id = :id", nativeQuery = true)
    Optional<Dispute> findByIdAcrossTenants(@Param("id") Long id);
}