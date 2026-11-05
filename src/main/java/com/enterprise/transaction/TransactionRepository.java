package com.enterprise.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    // ===== USE SPRING DATA's findFirst (preferred) =====
    Optional<Transaction> findFirstByIdempotencyKeyOrderByIdDesc(String idempotencyKey);

    // ===== FALLBACK: Return Optional with LIMIT 1 =====
    @Query(value = "SELECT * FROM transaction WHERE idempotency_key = :key ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<Transaction> findByIdempotencyKey(@Param("key") String key);

    List<Transaction> findByStatus(TransactionStatus status);

    // ===== USER-SPECIFIC METHODS =====
    List<Transaction> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<Transaction> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);

    // ===== TENANT-AWARE findAll and count =====
    @Query("SELECT t FROM Transaction t WHERE t.tenantId = :tenantId")
    List<Transaction> findAllByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.tenantId = :tenantId")
    long countByTenantId(@Param("tenantId") Long tenantId);

    /**
     * Loads only transactions in a window — used to build the 7-day dashboard
     * chart without pulling the tenant's entire history.
     */
    @Query("""
        SELECT t FROM Transaction t
         WHERE t.tenantId = :tenantId
           AND t.createdAt >= :from
         ORDER BY t.createdAt
    """)
    List<Transaction> findByTenantIdSince(@Param("tenantId") Long tenantId,
                                          @Param("from") java.time.LocalDateTime from);
}