package com.enterprise.invoice;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long>, JpaSpecificationExecutor<Invoice> {

    List<Invoice> findByMerchantId(Long merchantId);
    List<Invoice> findByCustomerEmail(String customerEmail);
    List<Invoice> findByStatusAndRequiresApproval(String status, boolean requiresApproval);
    List<Invoice> findByStatusIn(List<String> statuses);
    List<Invoice> findByStatus(String status);
    List<Invoice> findByStatusAndCustomerEmailContainingIgnoreCase(String status, String customerEmail);
    List<Invoice> findAllByIdIn(List<Long> ids);

    // ===== Ordered methods (tenant-less; legacy — prefer the *AndTenantId* variants) =====
    List<Invoice> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    List<Invoice> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);

    List<Invoice> findByMerchantIdAndStatus(Long merchantId, String status);

    // ===== TENANT-AWARE VARIANTS =====
    // Preferred over the tenant-less versions whenever tenantId is available.
    // Prevents cross-tenant leakage when merchant IDs or customer emails collide.
    List<Invoice> findByMerchantIdAndTenantIdOrderByCreatedAtDesc(Long merchantId, Long tenantId);
    List<Invoice> findByCustomerEmailAndTenantIdOrderByCreatedAtDesc(String customerEmail, Long tenantId);
    long countByMerchantIdAndTenantIdAndStatus(Long merchantId, Long tenantId, String status);

    /**
     * Count of non-GREEN invoices for a tenant — the tenant-scoped replacement
     * for the "Suspicious Invoices" admin dashboard card. Replaces the old
     * invoiceService.findAll().stream().filter(...).count() pattern.
     */
    @Query("""
        SELECT COUNT(i) FROM Invoice i
         WHERE i.tenantId = :tenantId
           AND i.riskLevel IS NOT NULL
           AND i.riskLevel <> 'GREEN'
    """)
    long countSuspiciousByTenantId(@Param("tenantId") Long tenantId);

    // ===== OPTIMISTIC LOCKING WITH RETRY - find with lock =====
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.id = :id")
    Optional<Invoice> findByIdWithLock(@Param("id") Long id);

    // ===== SUSPICION RE-EVALUATION QUERIES =====
    // Native query to bypass tenant filter - gets ALL invoices across ALL tenants
    @Query(value = "SELECT * FROM invoice WHERE status NOT IN ('PAID', 'REJECTED')", nativeQuery = true)
    List<Invoice> findAllInvoicesForReevaluation();

    // Tenant-aware re-evaluation query
    @Query(value = "SELECT * FROM invoice WHERE tenant_id = :tenantId AND status NOT IN ('PAID', 'REJECTED')", nativeQuery = true)
    List<Invoice> findAllByTenantIdForReevaluation(@Param("tenantId") Long tenantId);

    // ===== NATIVE UPDATE TO BYPASS TENANT FILTER =====
    @Modifying
    @Query(value = "UPDATE invoice SET risk_level = :riskLevel, suspicion_reason = :reason, updated_at = CURRENT_TIMESTAMP WHERE id = :id", nativeQuery = true)
    void updateRiskLevel(@Param("id") Long id, @Param("riskLevel") String riskLevel, @Param("reason") String reason);
}