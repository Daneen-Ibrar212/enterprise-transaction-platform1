-- ============================================================
-- V116 — Add tenant_id to rule_audit (idempotent)
--
-- Context: rule_audit previously had no tenant_id column. The entity's
-- @Filter(tenantFilter) references tenant_id, so queries against rule_audit
-- either threw UnknownFilterException or returned all tenants' audits.
--
-- This migration is idempotent — safe to re-run if a previous attempt
-- partially succeeded (which happened when the column was added by
-- another migration or an earlier V116 attempt rolled back mid-execution).
-- ============================================================

-- 1. Add column only if missing
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'rule_audit'
           AND column_name = 'tenant_id'
    ) THEN
        ALTER TABLE rule_audit ADD COLUMN tenant_id BIGINT;
        RAISE NOTICE 'V116: added tenant_id column to rule_audit';
    ELSE
        RAISE NOTICE 'V116: tenant_id column already exists on rule_audit — skipping ADD';
    END IF;
END $$;

-- 2. Best-effort backfill from the referenced rule table
UPDATE rule_audit ra
   SET tenant_id = COALESCE(
         (SELECT ar.tenant_id FROM approval_rule  ar
           WHERE ra.rule_type = 'APPROVAL'  AND ar.id = ra.rule_id),
         (SELECT rr.tenant_id FROM refund_rule    rr
           WHERE ra.rule_type = 'REFUND'    AND rr.id = ra.rule_id),
         (SELECT sr.tenant_id FROM suspicion_rule sr
           WHERE ra.rule_type = 'SUSPICION' AND sr.id = ra.rule_id),
         -- Fallback for PERMISSION rows and deleted rules
         1
       )
 WHERE tenant_id IS NULL;

-- 3. Sanity: fail loudly if anything is still NULL
DO $$
DECLARE remaining BIGINT;
BEGIN
    SELECT COUNT(*) INTO remaining FROM rule_audit WHERE tenant_id IS NULL;
    IF remaining > 0 THEN
        RAISE EXCEPTION 'V116 aborted: % rule_audit rows still have NULL tenant_id after backfill', remaining;
    END IF;
END $$;

-- 4. Enforce NOT NULL (safe now that backfill is complete)
ALTER TABLE rule_audit ALTER COLUMN tenant_id SET NOT NULL;

-- 5. Foreign key to tenant (idempotent)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_rule_audit_tenant'
    ) THEN
        ALTER TABLE rule_audit
            ADD CONSTRAINT fk_rule_audit_tenant
            FOREIGN KEY (tenant_id) REFERENCES tenant(id);
    END IF;
END $$;

-- 6. Index for the tenant filter (idempotent)
CREATE INDEX IF NOT EXISTS idx_rule_audit_tenant ON rule_audit(tenant_id);

COMMENT ON COLUMN rule_audit.tenant_id IS
    'Tenant ID for multi-tenant isolation of rule-change audits';