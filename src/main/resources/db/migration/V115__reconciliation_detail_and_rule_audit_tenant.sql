-- ============================================================
-- V115 — Add tenant_id to reconciliation_detail and rule_audit
--
-- Context (P2 findings):
--   • ReconciliationDetail previously had no tenant_id column.
--     Its @Filter(tenantFilter) required this column, so any tenant
--     isolation on this table was silently failing.
--   • RuleAudit had the same problem — no tenant_id, so rules audited
--     by tenant A could be seen by tenant B.
--
-- Order of operations matters:
--   1. Add nullable column
--   2. Backfill from the parent row (reconciliation_detail) or default
--   3. Promote to NOT NULL
--   4. Add index for the filter
-- ============================================================

-- ---------- reconciliation_detail ----------
ALTER TABLE reconciliation_detail
    ADD COLUMN tenant_id BIGINT;

-- Backfill from the parent reconciliation_record.
UPDATE reconciliation_detail d
   SET tenant_id = (
         SELECT r.tenant_id
           FROM reconciliation_record r
          WHERE r.id = d.reconciliation_record_id
       )
 WHERE tenant_id IS NULL;

-- Safety net: if any orphaned details exist (should not happen due to FK),
-- fail loudly rather than silently mis-assigning them.
DO $$
DECLARE
    orphan_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_count
      FROM reconciliation_detail
     WHERE tenant_id IS NULL;

    IF orphan_count > 0 THEN
        RAISE EXCEPTION
            'Migration V14 aborted: % reconciliation_detail rows could not be backfilled with tenant_id (orphaned reconciliation_record_id). Resolve manually before re-running.', orphan_count;
    END IF;
END $$;

ALTER TABLE reconciliation_detail
    ALTER COLUMN tenant_id SET NOT NULL;

CREATE INDEX idx_recon_detail_tenant
    ON reconciliation_detail (tenant_id);

-- ---------- rule_audit ----------
ALTER TABLE rule_audit
    ADD COLUMN tenant_id BIGINT;

-- Backfill: rule_audit has no direct parent that carries tenant_id
-- (approval_rule / refund_rule / suspicion_rule are the referenced tables,
-- but rule_audit is polymorphic via rule_type + rule_id).
--
-- Best-effort backfill: try each rule table in turn. Anything we cannot
-- resolve defaults to tenant 1 (the default tenant), matching the
-- application's behaviour before this migration.
UPDATE rule_audit ra
   SET tenant_id = COALESCE(
         -- APPROVAL rules
         (SELECT ar.tenant_id FROM approval_rule  ar WHERE ra.rule_type = 'APPROVAL'  AND ar.id = ra.rule_id),
         -- REFUND rules
         (SELECT rr.tenant_id FROM refund_rule    rr WHERE ra.rule_type = 'REFUND'    AND rr.id = ra.rule_id),
         -- SUSPICION rules
         (SELECT sr.tenant_id FROM suspicion_rule sr WHERE ra.rule_type = 'SUSPICION' AND sr.id = ra.rule_id),
         -- Fallback
         1
       )
 WHERE tenant_id IS NULL;

ALTER TABLE rule_audit
    ALTER COLUMN tenant_id SET NOT NULL;

CREATE INDEX idx_rule_audit_tenant
    ON rule_audit (tenant_id);