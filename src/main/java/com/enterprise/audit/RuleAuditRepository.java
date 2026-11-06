package com.enterprise.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RuleAuditRepository extends JpaRepository<RuleAudit, Long> {

    List<RuleAudit> findByRuleTypeAndRuleIdOrderByCreatedAtDesc(String ruleType, Long ruleId);

    @Query("SELECT a FROM RuleAudit a WHERE a.tenantId = :tenantId ORDER BY a.createdAt DESC")
    List<RuleAudit> findAllByTenantIdOrderByCreatedAtDesc(@Param("tenantId") Long tenantId);

    @Query("SELECT a FROM RuleAudit a WHERE a.tenantId = :tenantId AND a.ruleType = :ruleType ORDER BY a.createdAt DESC")
    List<RuleAudit> findByTenantIdAndRuleTypeOrderByCreatedAtDesc(@Param("tenantId") Long tenantId,
                                                                   @Param("ruleType") String ruleType);

    @Query("SELECT a FROM RuleAudit a WHERE a.tenantId = :tenantId AND a.changedBy = :userId ORDER BY a.createdAt DESC")
    List<RuleAudit> findByTenantIdAndChangedByOrderByCreatedAtDesc(@Param("tenantId") Long tenantId,
                                                                   @Param("userId") Long userId);
}