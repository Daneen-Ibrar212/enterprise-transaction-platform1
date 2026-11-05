-- V110: add subscription lifecycle columns to tenant (idempotent)
ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS subscription_status VARCHAR(20),
    ADD COLUMN IF NOT EXISTS subscription_renews_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS trial_ends_at TIMESTAMP;

-- Index for scheduled billing scans (also idempotent)
CREATE INDEX IF NOT EXISTS idx_tenant_subscription_status
    ON tenant (subscription_status);

CREATE INDEX IF NOT EXISTS idx_tenant_subscription_renews_at
    ON tenant (subscription_renews_at);