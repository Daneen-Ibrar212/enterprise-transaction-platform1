package com.enterprise.reliability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RetryPolicyRepository extends JpaRepository<RetryPolicy, Long> {

    // ============================================================
    // ✅ REQUIRED by ReliabilityPolicyService line 37
    // ============================================================
    Optional<RetryPolicy> findFirstByOperationTypeAndTenantIdAndActiveTrue(
            String operationType, Long tenantId);

    // ============================================================
    // Existing methods
    // ============================================================
    Optional<RetryPolicy> findByOperationTypeAndTenantIdAndActiveTrue(
            String operationType, Long tenantId);

    List<RetryPolicy> findByOperationTypeAndActiveTrue(String operationType);

    Optional<RetryPolicy> findFirstByOperationTypeAndActiveTrueOrderByIdAsc(String operationType);

    List<RetryPolicy> findByTenantId(Long tenantId);

    List<RetryPolicy> findByTenantIdAndActiveTrue(Long tenantId);

    List<RetryPolicy> findByOperationType(String operationType);
}