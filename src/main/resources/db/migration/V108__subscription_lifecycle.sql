-- ============================================================
-- V108: Subscription lifecycle (reuses existing payment infra)
-- ============================================================
-- Design notes:
--   • Subscription charges reuse transaction + idempotency_key tables
--   • A new transaction_type column distinguishes PLATFORM_SUBSCRIPTION
--     charges from regular customer→merchant INVOICE payments
--   • Webhooks fire through the existing WebhookService
--   • Audit goes through AuditService (hash-chained)
-- ============================================================

-- ---------- platform_plan: Stripe metadata (future) ----------
ALTER TABLE platform_plan
    ADD COLUMN IF NOT EXISTS stripe_price_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS annual_price_pence INTEGER;

-- ---------- tenant: current plan + billing window ----------
ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS current_plan_id BIGINT REFERENCES platform_plan(id),
    ADD COLUMN IF NOT EXISTS billing_period VARCHAR(20) DEFAULT 'MONTHLY',
    ADD COLUMN IF NOT EXISTS current_period_started_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP;

-- Backfill: match existing plan_code to a plan row
UPDATE tenant t
SET current_plan_id = p.id
FROM platform_plan p
WHERE t.plan_code = p.code
  AND t.current_plan_id IS NULL;

-- ---------- transaction: distinguish payment types ----------
-- Existing rows are all invoice payments between customer & merchant
ALTER TABLE transaction
    ADD COLUMN IF NOT EXISTS transaction_type VARCHAR(30) NOT NULL DEFAULT 'INVOICE_PAYMENT';

-- For subscription charges, we still need to know which plan
ALTER TABLE transaction
    ADD COLUMN IF NOT EXISTS subscription_plan_id BIGINT REFERENCES platform_plan(id);

COMMENT ON COLUMN transaction.transaction_type IS
    'INVOICE_PAYMENT | PLATFORM_SUBSCRIPTION';

-- ---------- idempotency_key: allow tenant-scoped keys ----------
-- Already has tenant_id; nothing to change there.

-- ---------- subscription_event: append-only lifecycle log ----------
CREATE TABLE IF NOT EXISTS subscription_event (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT NOT NULL REFERENCES tenant(id),
    event_type        VARCHAR(40) NOT NULL,       -- SUBSCRIBED | UPGRADED | DOWNGRADED | CANCELLED | RESUMED | RENEWED | PAYMENT_FAILED
    from_plan_id      BIGINT REFERENCES platform_plan(id),
    to_plan_id        BIGINT REFERENCES platform_plan(id),
    transaction_id    BIGINT REFERENCES transaction(id),
    notes             TEXT,
    actor_user_id     BIGINT REFERENCES app_user(id),
    created_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sub_event_tenant
    ON subscription_event(tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tenant_subscription_status
    ON tenant(subscription_status);

CREATE INDEX IF NOT EXISTS idx_tx_type
    ON transaction(transaction_type);