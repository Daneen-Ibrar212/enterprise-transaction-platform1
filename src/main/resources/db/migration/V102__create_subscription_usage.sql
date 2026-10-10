-- ============================================================
-- V102: Usage-based billing tracking
-- ============================================================

CREATE TABLE IF NOT EXISTS subscription_usage (
    id                  BIGSERIAL PRIMARY KEY,
    subscription_id     BIGINT NOT NULL REFERENCES customer_subscription(id) ON DELETE CASCADE,
    tenant_id           BIGINT NOT NULL REFERENCES tenant(id),

    metric_name         VARCHAR(50) NOT NULL,   -- e.g. API_CALLS, INVOICES, SEATS
    usage_count         BIGINT NOT NULL DEFAULT 0,
    included_quantity   BIGINT NOT NULL DEFAULT 0,
    overage_rate        NUMERIC(19,4) NOT NULL DEFAULT 0,
    period_start        DATE NOT NULL,
    period_end          DATE NOT NULL,

    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP,
    version             BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_subscription_usage_period UNIQUE (subscription_id, metric_name, period_start)
);

CREATE INDEX IF NOT EXISTS idx_usage_subscription ON subscription_usage(subscription_id);
CREATE INDEX IF NOT EXISTS idx_usage_tenant       ON subscription_usage(tenant_id);
CREATE INDEX IF NOT EXISTS idx_usage_period       ON subscription_usage(period_start, period_end);