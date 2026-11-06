-- ============================================================
-- V119 — Backfill any remaining NULL webhook_config.tenant_id
--
-- V31 set these to 1 originally, V87 re-set to 1 for existing NULLs.
-- If any webhook rows were inserted between V31 and V87 without a tenant,
-- they'd be orphaned. This catches them.
-- ============================================================

UPDATE webhook_config SET tenant_id = 1 WHERE tenant_id IS NULL;
ALTER TABLE webhook_config ALTER COLUMN tenant_id SET NOT NULL;

-- Ensure the constraint from V87 is present (idempotent)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_webhook_config_tenant_event'
    ) THEN
        ALTER TABLE webhook_config ADD CONSTRAINT uk_webhook_config_tenant_event
            UNIQUE (tenant_id, event_type);
    END IF;
END $$;

ALTER TABLE webhook_config DROP CONSTRAINT IF EXISTS uk_webhook_config_tenant_event;