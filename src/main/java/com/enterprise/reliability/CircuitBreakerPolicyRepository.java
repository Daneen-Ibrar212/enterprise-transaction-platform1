package com.enterprise.reliability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CircuitBreakerPolicyRepository extends JpaRepository<CircuitBreakerPolicy, Long> {

    // ============================================================
    // ✅ REQUIRED by ReliabilityPolicyService line 77
    // ============================================================
    Optional<CircuitBreakerPolicy> findFirstByOperationTypeAndTenantIdAndActiveTrue(
            String operationType, Long tenantId);

    // ============================================================
    // Existing methods
    // ============================================================
    Optional<CircuitBreakerPolicy> findByOperationTypeAndTenantIdAndActiveTrue(
            String operationType, Long tenantId);

    List<CircuitBreakerPolicy> findByOperationTypeAndActiveTrue(String operationType);

    Optional<CircuitBreakerPolicy> findFirstByOperationTypeAndActiveTrueOrderByIdAsc(String operationType);

    List<CircuitBreakerPolicy> findByTenantId(Long tenantId);

    List<CircuitBreakerPolicy> findByTenantIdAndActiveTrue(Long tenantId);

    List<CircuitBreakerPolicy> findByOperationType(String operationType);
}