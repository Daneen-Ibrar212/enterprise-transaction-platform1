package com.enterprise.reliability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final DlqEntryRepository dlqEntryRepository;
    private final CircuitBreakerStateRepository cbStateRepository;
    private final ReliabilityPolicyService policyService;
    private final ObjectMapper objectMapper;

    public RecoveryService(DlqEntryRepository dlqEntryRepository,
                           CircuitBreakerStateRepository cbStateRepository,
                           ReliabilityPolicyService policyService,
                           ObjectMapper objectMapper) {
        this.dlqEntryRepository = dlqEntryRepository;
        this.cbStateRepository = cbStateRepository;
        this.policyService = policyService;
        this.objectMapper = objectMapper;
    }

    public Object executeWithRetry(String operationType, Supplier<Object> action, Map<String, Object> context) {
        RetryPolicy retryPolicy = policyService.getRetryPolicy(operationType);
        int attempts = 0;
        Exception lastException = null;

        while (attempts < retryPolicy.getMaxAttempts()) {
            try {
                // ============================================================
                // CIRCUIT BREAKER CHECK (tenant-aware + safe)
                // ============================================================
                CircuitBreakerState cbState = policyService.getCircuitBreakerState(operationType);

                if ("OPEN".equals(cbState.getState())) {
                    CircuitBreakerPolicy cbPolicy = policyService.getCircuitBreakerPolicy(operationType);

                    // ✅ Guard against null openedAt
                    LocalDateTime openedAt = cbState.getOpenedAt() != null
                            ? cbState.getOpenedAt()
                            : cbState.getUpdatedAt() != null ? cbState.getUpdatedAt() : LocalDateTime.now();

                    long timeoutSeconds = cbPolicy.getTimeoutMs() / 1000;

                    if (openedAt.plusSeconds(timeoutSeconds).isBefore(LocalDateTime.now())) {
                        cbState.setState("HALF_OPEN");
                        cbStateRepository.save(cbState);
                        log.info("Circuit breaker moved to HALF_OPEN for {}", operationType);
                    } else {
                        throw new RuntimeException("Circuit breaker open for " + operationType);
                    }
                }

                Object result = action.get();

                // ============================================================
                // ON SUCCESS: reset circuit breaker
                // ============================================================
                CircuitBreakerState state = policyService.getCircuitBreakerState(operationType);
                if ("HALF_OPEN".equals(state.getState())) {
                    policyService.incrementSuccessCount(operationType);
                    CircuitBreakerPolicy cbPolicy = policyService.getCircuitBreakerPolicy(operationType);

                    // ✅ Reload to get updated count
                    state = policyService.getCircuitBreakerState(operationType);
                    if (state.getSuccessCount() >= cbPolicy.getSuccessThreshold()) {
                        state.setState("CLOSED");
                        state.setFailureCount(0);
                        state.setSuccessCount(0);
                        cbStateRepository.save(state);
                        log.info("Circuit breaker closed for {}", operationType);
                    }
                }
                return result;

            } catch (Exception e) {
                lastException = e;
                attempts++;
                log.warn("Attempt {} failed for {}: {}", attempts, operationType, e.getMessage());

                FailureClassificationRule rule;
                try {
                    rule = policyService.classifyFailure(operationType, e);
                } catch (Exception classifyEx) {
                    log.error("classifyFailure failed, defaulting to RETRY", classifyEx);
                    rule = new FailureClassificationRule();
                    rule.setAction("RETRY");
                }

                String ruleAction = rule.getAction();

                if ("IGNORE".equals(ruleAction)) {
                    log.info("Ignoring failure for {}: {}", operationType, e.getMessage());
                    return null;
                } else if ("ROLLBACK".equals(ruleAction)) {
                    throw new RuntimeException("Operation rolled back due to failure", e);
                } else if ("DLQ".equals(ruleAction)) {
                    sendToDlq(operationType, context, e);
                    throw new RuntimeException("Operation sent to DLQ", e);
                } else if ("RETRY".equals(ruleAction)) {
                    policyService.incrementFailureCount(operationType);
                    long delay = calculateBackoff(retryPolicy, attempts);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                }
            }
        }

        sendToDlq(operationType, context, lastException);
        throw new RuntimeException("All retry attempts exhausted for " + operationType, lastException);
    }

    private long calculateBackoff(RetryPolicy policy, int attempt) {
        long base = policy.getBaseDelayMs();
        long max = policy.getMaxDelayMs();
        String strategy = policy.getBackoffStrategy();
        long delay = base;
        if ("EXPONENTIAL".equals(strategy)) {
            delay = (long) (base * Math.pow(2, attempt - 1));
        } else if ("LINEAR".equals(strategy)) {
            delay = base * attempt;
        }
        if (policy.isJitterEnabled()) {
            double jitter = 0.8 + (Math.random() * 0.4);
            delay = (long) (delay * jitter);
        }
        return Math.min(delay, max);
    }

    private void sendToDlq(String operationType, Map<String, Object> context, Exception e) {
        try {
            String payload = objectMapper.writeValueAsString(context);
            DlqEntry entry = new DlqEntry();
            entry.setOperationType(operationType);
            entry.setPayload(payload);
            entry.setFailureReason(e != null ? e.getMessage() : "Unknown error");
            entry.setStatus("PENDING");

            // ✅ Safe tenant fallback
            Long tenantId = TenantContext.getTenantId();
            if (tenantId == null) {
                log.warn("No tenant context for DLQ entry, using default tenant 1");
                tenantId = 1L;
            }
            entry.setTenantId(tenantId);
            dlqEntryRepository.save(entry);
            log.info("Operation {} sent to DLQ for tenant {}", operationType, tenantId);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize payload for DLQ", ex);
        } catch (Exception ex) {
            log.error("Failed to send operation {} to DLQ", operationType, ex);
        }
    }
}