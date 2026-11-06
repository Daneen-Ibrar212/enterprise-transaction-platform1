package com.enterprise.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findFirstByIdempotencyKeyOrderByIdDesc(String idempotencyKey);

    @Query(value = "SELECT * FROM transaction WHERE idempotency_key = :key ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<Transaction> findByIdempotencyKey(@Param("key") String key);

    List<Transaction> findByStatus(TransactionStatus status);
    List<Transaction> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<Transaction> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);

    @Query("SELECT t FROM Transaction t WHERE t.tenantId = :tenantId")
    List<Transaction> findAllByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.tenantId = :tenantId")
    long countByTenantId(@Param("tenantId") Long tenantId);

    @Query("""
        SELECT t FROM Transaction t
         WHERE t.tenantId = :tenantId
           AND t.createdAt >= :from
         ORDER BY t.createdAt
    """)
    List<Transaction> findByTenantIdSince(@Param("tenantId") Long tenantId,
                                          @Param("from") LocalDateTime from);

    // ===== BOUNDED DATE-RANGE QUERIES (used by ReportingService) =====

    @Query("""
        SELECT t FROM Transaction t
         WHERE t.tenantId = :tenantId
           AND t.createdAt >= :start
           AND t.createdAt <= :end
         ORDER BY t.createdAt
    """)
    List<Transaction> findByTenantIdBetween(@Param("tenantId") Long tenantId,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    @Query("""
        SELECT t FROM Transaction t
         WHERE t.createdAt >= :start
           AND t.createdAt <= :end
         ORDER BY t.createdAt
    """)
    List<Transaction> findAllBetween(@Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end);

    // ===== AGGREGATE COUNTS (avoid loading rows just to count) =====

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.tenantId = :tenantId AND t.createdAt >= :start AND t.createdAt <= :end")
    long countByTenantIdBetween(@Param("tenantId") Long tenantId,
                                @Param("start") LocalDateTime start,
                                @Param("end") LocalDateTime end);

    // ===== Tenants that ACTUALLY have transactions in a window — used by schedulers =====
    @Query("SELECT DISTINCT t.tenantId FROM Transaction t WHERE t.createdAt >= :from")
    List<Long> findDistinctTenantIdsSince(@Param("from") LocalDateTime from);
}