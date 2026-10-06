-- ============================================================
-- Backfill missing feature flags for all tenants
-- ============================================================

INSERT INTO feature_flag (name, enabled, description, tenant_id, updated_at)
SELECT 'TWO_FACTOR_AUTH', true, 'Require 2FA for admin users', t.id, NOW()
FROM tenant t
WHERE NOT EXISTS (SELECT 1 FROM feature_flag f WHERE f.name = 'TWO_FACTOR_AUTH' AND f.tenant_id = t.id);

INSERT INTO feature_flag (name, enabled, description, tenant_id, updated_at)
SELECT 'WEBHOOKS', true, 'Enable webhook delivery', t.id, NOW()
FROM tenant t
WHERE NOT EXISTS (SELECT 1 FROM feature_flag f WHERE f.name = 'WEBHOOKS' AND f.tenant_id = t.id);

INSERT INTO feature_flag (name, enabled, description, tenant_id, updated_at)
SELECT 'REFUNDS', true, 'Enable refund processing', t.id, NOW()
FROM tenant t
WHERE NOT EXISTS (SELECT 1 FROM feature_flag f WHERE f.name = 'REFUNDS' AND f.tenant_id = t.id);

INSERT INTO feature_flag (name, enabled, description, tenant_id, updated_at)
SELECT 'SUSPICION_DETECTION', true, 'Enable suspicion rules', t.id, NOW()
FROM tenant t
WHERE NOT EXISTS (SELECT 1 FROM feature_flag f WHERE f.name = 'SUSPICION_DETECTION' AND f.tenant_id = t.id);

INSERT INTO feature_flag (name, enabled, description, tenant_id, updated_at)
SELECT 'XERO_AUTO_SYNC', false, 'Auto-sync invoices to Xero', t.id, NOW()
FROM tenant t
WHERE NOT EXISTS (SELECT 1 FROM feature_flag f WHERE f.name = 'XERO_AUTO_SYNC' AND f.tenant_id = t.id);

INSERT INTO feature_flag (name, enabled, description, tenant_id, updated_at)
SELECT 'RECURRING_BILLING', true, 'Enable recurring invoices', t.id, NOW()
FROM tenant t
WHERE NOT EXISTS (SELECT 1 FROM feature_flag f WHERE f.name = 'RECURRING_BILLING' AND f.tenant_id = t.id);

INSERT INTO feature_flag (name, enabled, description, tenant_id, updated_at)
SELECT 'SUBSCRIPTIONS', true, 'Enable subscription management', t.id, NOW()
FROM tenant t
WHERE NOT EXISTS (SELECT 1 FROM feature_flag f WHERE f.name = 'SUBSCRIPTIONS' AND f.tenant_id = t.id);