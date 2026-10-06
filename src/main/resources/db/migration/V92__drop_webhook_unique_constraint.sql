-- Drop the old hard uniqueness on (tenant_id, event_type)
ALTER TABLE webhook_config DROP CONSTRAINT IF EXISTS uk_webhook_config_tenant_event;

-- Drop the partial index from the earlier attempt (if it exists)
DROP INDEX IF EXISTS idx_webhook_config_tenant_event;
DROP INDEX IF EXISTS uk_webhook_config_tenant_event_active;

-- New rule: prevent only the *exact* duplicate (same tenant + event + url)
CREATE UNIQUE INDEX IF NOT EXISTS uk_webhook_config_tenant_event_url
    ON webhook_config(tenant_id, event_type, url);