-- ============================================================
-- V88: Dedupe reliability policies
-- ============================================================

-- Step 1: Ensure failure_classification_rule has tenant_id
ALTER TABLE failure_classification_rule 
    ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 1;

-- Step 2: Dedupe retry policies (keep lowest id per tenant+operation)
DELETE FROM retry_policy
WHERE id NOT IN (
    SELECT MIN(id) FROM retry_policy
    GROUP BY tenant_id, operation_type, active
);

-- Step 3: Dedupe circuit breaker policies
DELETE FROM circuit_breaker_policy
WHERE id NOT IN (
    SELECT MIN(id) FROM circuit_breaker_policy
    GROUP BY tenant_id, operation_type, active
);

-- Step 4: Dedupe circuit breaker states
DELETE FROM circuit_breaker_state
WHERE id NOT IN (
    SELECT MIN(id) FROM circuit_breaker_state
    GROUP BY tenant_id, operation_type
);

-- Step 5: Dedupe failure classification rules
DELETE FROM failure_classification_rule
WHERE id NOT IN (
    SELECT MIN(id) FROM failure_classification_rule
    GROUP BY tenant_id, priority, active
);

-- Step 6: Create unique constraints to prevent future dupes
ALTER TABLE retry_policy 
    DROP CONSTRAINT IF EXISTS uq_retry_policy_tenant_op_active;
ALTER TABLE retry_policy 
    ADD CONSTRAINT uq_retry_policy_tenant_op_active 
    UNIQUE (tenant_id, operation_type, active);

ALTER TABLE circuit_breaker_policy 
    DROP CONSTRAINT IF EXISTS uq_cb_policy_tenant_op_active;
ALTER TABLE circuit_breaker_policy 
    ADD CONSTRAINT uq_cb_policy_tenant_op_active 
    UNIQUE (tenant_id, operation_type, active);

ALTER TABLE circuit_breaker_state 
    DROP CONSTRAINT IF EXISTS uq_cb_state_tenant_op;
ALTER TABLE circuit_breaker_state 
    ADD CONSTRAINT uq_cb_state_tenant_op 
    UNIQUE (tenant_id, operation_type);

-- Step 7: Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_retry_policy_tenant_active 
    ON retry_policy(tenant_id, active, operation_type);

CREATE INDEX IF NOT EXISTS idx_cb_policy_tenant_active 
    ON circuit_breaker_policy(tenant_id, active, operation_type);

CREATE INDEX IF NOT EXISTS idx_cb_state_tenant_op 
    ON circuit_breaker_state(tenant_id, operation_type);

CREATE INDEX IF NOT EXISTS idx_failure_rule_tenant_active 
    ON failure_classification_rule(tenant_id, active, priority);

-- Step 8: Clean up Flyway history for the failed attempt
-- (This is handled automatically by Flyway when the migration fails)