-- ============================================================
-- V89: Add tenant_id to reliability entities missing it
-- ============================================================

-- ============================================================
-- 1. failure_classification_rule
-- ============================================================
ALTER TABLE failure_classification_rule 
    ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_failure_rule_tenant_active 
    ON failure_classification_rule(tenant_id, active, priority);

COMMENT ON COLUMN failure_classification_rule.tenant_id 
    IS 'Tenant ID for multi-tenant isolation';

-- ============================================================
-- 2. backoff_policy
-- ============================================================
ALTER TABLE backoff_policy 
    ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_backoff_policy_tenant 
    ON backoff_policy(tenant_id);

CREATE INDEX IF NOT EXISTS idx_backoff_policy_tenant_retry 
    ON backoff_policy(tenant_id, retry_policy_id);

COMMENT ON COLUMN backoff_policy.tenant_id 
    IS 'Tenant ID for multi-tenant isolation';

-- ============================================================
-- 3. Data backfill — inherit tenant from parent retry_policy
-- ============================================================
-- Existing backoff_policy rows: copy tenant_id from their parent retry_policy
UPDATE backoff_policy bp
SET tenant_id = rp.tenant_id
FROM retry_policy rp
WHERE bp.retry_policy_id = rp.id
  AND bp.tenant_id = 1
  AND rp.tenant_id IS NOT NULL;

-- ============================================================
-- 4. Optional: unique constraint for multi-tenant safety
-- ============================================================
-- If backoff_policy should be unique per (retry_policy, tenant):
-- ALTER TABLE backoff_policy ADD CONSTRAINT uk_backoff_policy_retry_tenant 
--     UNIQUE (retry_policy_id, tenant_id);