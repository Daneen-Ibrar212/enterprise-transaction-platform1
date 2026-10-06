package com.enterprise.reliability;

import com.enterprise.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReliabilityPolicyService {

    private static final Logger log = LoggerFactory.getLogger(ReliabilityPolicyService.class);

    private final RetryPolicyRepository retryPolicyRepository;
    private final CircuitBreakerPolicyRepository cbPolicyRepository;
    private final CircuitBreakerStateRepository cbStateRepository;
    private final FailureClassificationRuleRepository ruleRepository;

    public ReliabilityPolicyService(RetryPolicyRepository retryPolicyRepository,
                                    CircuitBreakerPolicyRepository cbPolicyRepository,
                                    CircuitBreakerStateRepository cbStateRepository,
                                    FailureClassificationRuleRepository ruleRepository) {
        this.retryPolicyRepository = retryPolicyRepository;
        this.cbPolicyRepository = cbPolicyRepository;
        this.cbStateRepository = cbStateRepository;
        this.ruleRepository = ruleRepository;
    }

    // ============================================================
    // RETRY POLICY
    // ============================================================
    public RetryPolicy getRetryPolicy(String operationType) {
        Long tenantId = getTenantIdSafe();
        return retryPolicyRepository
                .findFirstByOperationTypeAndTenantIdAndActiveTrue(operationType, tenantId)
                .orElseGet(() -> {
                    RetryPolicy defaultPolicy = new RetryPolicy();
                    defaultPolicy.setOperationType(operationType);
                    defaultPolicy.setMaxAttempts(3);
                    defaultPolicy.setBackoffStrategy("EXPONENTIAL");
                    defaultPolicy.setBaseDelayMs(1000);
                    defaultPolicy.setMaxDelayMs(30000);
                    defaultPolicy.setJitterEnabled(true);
                    defaultPolicy.setActive(true);
                    defaultPolicy.setTenantId(tenantId);
                    return defaultPolicy;
                });
    }

    // ============================================================
    // CIRCUIT BREAKER STATE
    // ============================================================
    public CircuitBreakerState getCircuitBreakerState(String operationType) {
        Long tenantId = getTenantIdSafe();
        return cbStateRepository
                .findFirstByOperationTypeAndTenantId(operationType, tenantId)
                .orElseGet(() -> {
                    CircuitBreakerState defaultState = new CircuitBreakerState();
                    defaultState.setOperationType(operationType);
                    defaultState.setState("CLOSED");
                    defaultState.setFailureCount(0);
                    defaultState.setSuccessCount(0);
                    defaultState.setUpdatedAt(LocalDateTime.now());
                    defaultState.setTenantId(tenantId);
                    return cbStateRepository.save(defaultState);
                });
    }

    // ============================================================
    // CIRCUIT BREAKER POLICY
    // ============================================================
    public CircuitBreakerPolicy getCircuitBreakerPolicy(String operationType) {
        Long tenantId = getTenantIdSafe();
        return cbPolicyRepository
                .findFirstByOperationTypeAndTenantIdAndActiveTrue(operationType, tenantId)
                .orElseGet(() -> {
                    CircuitBreakerPolicy defaultPolicy = new CircuitBreakerPolicy();
                    defaultPolicy.setOperationType(operationType);
                    defaultPolicy.setFailureThreshold(5);
                    defaultPolicy.setSuccessThreshold(3);
                    defaultPolicy.setTimeoutMs(30000);
                    defaultPolicy.setEvaluationWindowSec(30);
                    defaultPolicy.setActive(true);
                    defaultPolicy.setTenantId(tenantId);
                    return cbPolicyRepository.save(defaultPolicy);
                });
    }

    // ============================================================
    // FAILURE CLASSIFICATION — FIXED to handle duplicates
    // ============================================================
    public FailureClassificationRule classifyFailure(String operationType, Exception ex) {
        Long tenantId = getTenantIdSafe();
        String message = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";

        // ✅ Try tenant-aware rules first
        List<FailureClassificationRule> rules =
                ruleRepository.findActiveByTenantIdOrderByPriority(tenantId);

        // ✅ Fallback to global rules if none found for tenant
        if (rules.isEmpty()) {
            rules = ruleRepository.findByActiveTrueOrderByPriorityAsc();
        }

        for (FailureClassificationRule rule : rules) {
            try {
                if (rule.getConditionExpression() != null
                        && !rule.getConditionExpression().isEmpty()
                        && message.contains(rule.getConditionExpression().toLowerCase())) {
                    return rule;
                }
            } catch (Exception e) {
                log.warn("Failed to evaluate rule {}: {}", rule.getId(), e.getMessage());
            }
        }

        // Default rule
        FailureClassificationRule defaultRule = new FailureClassificationRule();
        defaultRule.setAction("RETRY");
        defaultRule.setCategory("TRANSIENT");
        defaultRule.setSeverity("MEDIUM");
        return defaultRule;
    }

    // ============================================================
    // CIRCUIT BREAKER MUTATIONS — safe against duplicates
    // ============================================================
    public void updateCircuitBreakerState(String operationType, String state) {
        Long tenantId = getTenantIdSafe();
        cbStateRepository.updateState(operationType, tenantId, state);
    }

    public void incrementFailureCount(String operationType) {
        Long tenantId = getTenantIdSafe();
        cbStateRepository.incrementFailureCount(operationType, tenantId);
    }

    public void incrementSuccessCount(String operationType) {
        Long tenantId = getTenantIdSafe();
        cbStateRepository.incrementSuccessCount(operationType, tenantId);
    }

    public void resetCounts(String operationType) {
        Long tenantId = getTenantIdSafe();
        cbStateRepository.resetCounts(operationType, tenantId);
    }

    // ============================================================
    // SAFE TENANT ID
    // ============================================================
    private Long getTenantIdSafe() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            log.warn("No tenant context, defaulting to tenant 1");
            return 1L;
        }
        return tenantId;
    }
}