-- ============================================================
-- V88: Fix webhook_config rows with null tenant_id + remove dupes
-- ============================================================

-- 1. Set any null tenant_id to default tenant 1
UPDATE webhook_config SET tenant_id = 1 WHERE tenant_id IS NULL;

-- 2. Ensure tenant_id is NOT NULL
ALTER TABLE webhook_config ALTER COLUMN tenant_id SET NOT NULL;

-- 3. Delete duplicate configs (keep oldest per tenant+event)
DELETE FROM webhook_config 
WHERE id NOT IN (
    SELECT MIN(id) 
    FROM webhook_config 
    GROUP BY tenant_id, event_type
);

-- 4. Add unique constraint to prevent future duplicates
ALTER TABLE webhook_config 
DROP CONSTRAINT IF EXISTS uk_webhook_config_tenant_event;

ALTER TABLE webhook_config 
ADD CONSTRAINT uk_webhook_config_tenant_event 
UNIQUE (tenant_id, event_type);

-- 5. Add performance index
CREATE INDEX IF NOT EXISTS idx_webhook_config_tenant_event_active 
ON webhook_config(tenant_id, event_type, active);

COMMENT ON CONSTRAINT uk_webhook_config_tenant_event ON webhook_config 
IS 'Only one webhook per event per tenant';