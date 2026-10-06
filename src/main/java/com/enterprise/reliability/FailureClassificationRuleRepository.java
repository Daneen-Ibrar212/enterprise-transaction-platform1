package com.enterprise.reliability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FailureClassificationRuleRepository extends JpaRepository<FailureClassificationRule, Long> {

    // ============================================================
    // ✅ THE MISSING METHOD
    // ============================================================
    @Query("SELECT r FROM FailureClassificationRule r " +
           "WHERE r.tenantId = :tenantId AND r.active = true " +
           "ORDER BY r.priority ASC")
    List<FailureClassificationRule> findActiveByTenantIdOrderByPriority(@Param("tenantId") Long tenantId);

    // ============================================================
    // Global fallback
    // ============================================================
    List<FailureClassificationRule> findByActiveTrueOrderByPriorityAsc();

    // ============================================================
    // Tenant-aware extra queries
    // ============================================================
    @Query("SELECT r FROM FailureClassificationRule r " +
           "WHERE r.tenantId = :tenantId ORDER BY r.priority ASC")
    List<FailureClassificationRule> findAllByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(r) FROM FailureClassificationRule r WHERE r.tenantId = :tenantId")
    long countByTenantId(@Param("tenantId") Long tenantId);
}