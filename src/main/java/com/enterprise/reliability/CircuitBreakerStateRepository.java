package com.enterprise.reliability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface CircuitBreakerStateRepository extends JpaRepository<CircuitBreakerState, Long> {

    // ============================================================
    // ✅ REQUIRED by ReliabilityPolicyService line 58
    // ============================================================
    Optional<CircuitBreakerState> findFirstByOperationTypeAndTenantId(
            String operationType, Long tenantId);

    // ============================================================
    // Existing methods
    // ============================================================
    Optional<CircuitBreakerState> findByOperationTypeAndTenantId(
            String operationType, Long tenantId);

    List<CircuitBreakerState> findByTenantId(Long tenantId);

    // Update state for a tenant
    @Modifying
    @Transactional
    @Query("UPDATE CircuitBreakerState cs SET cs.state = :state, cs.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE cs.operationType = :operationType AND cs.tenantId = :tenantId")
    void updateState(@Param("operationType") String operationType,
                     @Param("tenantId") Long tenantId,
                     @Param("state") String state);

    // Increment failure count
    @Modifying
    @Transactional
    @Query("UPDATE CircuitBreakerState cs SET cs.failureCount = cs.failureCount + 1, " +
           "cs.lastFailureTime = CURRENT_TIMESTAMP, cs.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE cs.operationType = :operationType AND cs.tenantId = :tenantId")
    void incrementFailureCount(@Param("operationType") String operationType,
                               @Param("tenantId") Long tenantId);

    // Increment success count
    @Modifying
    @Transactional
    @Query("UPDATE CircuitBreakerState cs SET cs.successCount = cs.successCount + 1, " +
           "cs.lastSuccessTime = CURRENT_TIMESTAMP, cs.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE cs.operationType = :operationType AND cs.tenantId = :tenantId")
    void incrementSuccessCount(@Param("operationType") String operationType,
                               @Param("tenantId") Long tenantId);

    // Reset counts
    @Modifying
    @Transactional
    @Query("UPDATE CircuitBreakerState cs SET cs.failureCount = 0, cs.successCount = 0, " +
           "cs.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE cs.operationType = :operationType AND cs.tenantId = :tenantId")
    void resetCounts(@Param("operationType") String operationType,
                     @Param("tenantId") Long tenantId);
}