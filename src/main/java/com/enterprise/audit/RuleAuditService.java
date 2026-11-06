package com.enterprise.audit;

import com.enterprise.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RuleAuditService {

    private static final Logger log = LoggerFactory.getLogger(RuleAuditService.class);

    private final RuleAuditRepository ruleAuditRepository;
    private final ObjectMapper objectMapper;

    public RuleAuditService(RuleAuditRepository ruleAuditRepository, ObjectMapper objectMapper) {
        this.ruleAuditRepository = ruleAuditRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void logChange(String ruleType, Long ruleId, String action,
                          Object oldValues, Object newValues, Long changedBy) {
        try {
            RuleAudit audit = new RuleAudit();
            audit.setTenantId(TenantContext.getRequiredTenantId());
            audit.setRuleType(ruleType);
            audit.setRuleId(ruleId);
            audit.setAction(action);
            audit.setChangedBy(changedBy);
            if (oldValues != null) audit.setOldValues(objectMapper.writeValueAsString(oldValues));
            if (newValues != null) audit.setNewValues(objectMapper.writeValueAsString(newValues));
            ruleAuditRepository.save(audit);
        } catch (Exception e) {
            log.error("Failed to log rule audit: {}", e.getMessage(), e);
        }
    }

    public List<RuleAudit> getAllAudits() {
        return ruleAuditRepository.findAllByTenantIdOrderByCreatedAtDesc(
                TenantContext.getRequiredTenantId());
    }

    public List<RuleAudit> getAuditsByRuleType(String ruleType) {
        return ruleAuditRepository.findByTenantIdAndRuleTypeOrderByCreatedAtDesc(
                TenantContext.getRequiredTenantId(), ruleType);
    }

    public List<RuleAudit> getAuditsByRule(String ruleType, Long ruleId) {
        return ruleAuditRepository.findByRuleTypeAndRuleIdOrderByCreatedAtDesc(ruleType, ruleId);
    }

    public List<RuleAudit> getAuditsByUser(Long userId) {
        return ruleAuditRepository.findByTenantIdAndChangedByOrderByCreatedAtDesc(
                TenantContext.getRequiredTenantId(), userId);
    }
}