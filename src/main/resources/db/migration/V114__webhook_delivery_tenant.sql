-- V114__webhook_delivery_tenant.sql
-- Adds tenant_id to webhook_delivery so deliveries are tenant-scoped.
-- Previously MERCHANT_ADMINs could list/replay any tenant's webhook deliveries.

ALTER TABLE webhook_delivery ADD COLUMN tenant_id BIGINT;

-- Backfill from the parent config row
UPDATE webhook_delivery d
SET tenant_id = (SELECT c.tenant_id FROM webhook_config c WHERE c.id = d.webhook_config_id)
WHERE tenant_id IS NULL;

-- Any orphaned deliveries (missing config) get the default tenant so NOT NULL succeeds.
-- Review these rows in staging — they indicate data corruption.
UPDATE webhook_delivery SET tenant_id = 1 WHERE tenant_id IS NULL;

ALTER TABLE webhook_delivery ALTER COLUMN tenant_id SET NOT NULL;
CREATE INDEX idx_wh_delivery_tenant ON webhook_delivery(tenant_id);
CREATE INDEX idx_wh_delivery_config ON webhook_delivery(webhook_config_id);