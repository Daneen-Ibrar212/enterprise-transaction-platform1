-- ============================================================
-- V101: Feature flag for reconciliation auto-fix
-- ============================================================

INSERT INTO feature_flag (name, enabled, description, tenant_id, updated_at)
SELECT 'RECONCILIATION_AUTOFIX', true,
       'Allow auto-repair of MISSING_LEDGER discrepancies', t.id, NOW()
FROM tenant t
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flag f
    WHERE f.name = 'RECONCILIATION_AUTOFIX' AND f.tenant_id = t.id
);