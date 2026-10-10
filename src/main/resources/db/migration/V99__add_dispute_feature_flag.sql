-- ============================================================
-- V99: Feature flag for disputes
-- ============================================================

INSERT INTO feature_flag (name, enabled, description, tenant_id, updated_at)
SELECT 'DISPUTES', true, 'Enable dispute / chargeback management', t.id, NOW()
FROM tenant t
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flag f
    WHERE f.name = 'DISPUTES' AND f.tenant_id = t.id
);