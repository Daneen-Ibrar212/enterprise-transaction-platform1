-- ============================================================
-- V106: Platform plans (the plans Super Admin sells to tenants)
-- ============================================================
-- Distinct from subscription_plan (which merchants sell to their customers).
-- These are the SaaS tiers shown on the public pricing page.
-- ============================================================

CREATE TABLE IF NOT EXISTS platform_plan (
    id                  BIGSERIAL PRIMARY KEY,
    code                VARCHAR(50) NOT NULL UNIQUE,
    name                VARCHAR(100) NOT NULL,
    tagline             VARCHAR(255),
    monthly_price_pence INTEGER NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'GBP',
    invoice_limit       INTEGER,        -- NULL = unlimited
    user_limit          INTEGER,        -- NULL = unlimited
    features_json       TEXT,           -- JSON array of feature strings for display
    feature_flags_json  TEXT,           -- JSON object of feature-flag → boolean to seed
    highlighted         BOOLEAN NOT NULL DEFAULT FALSE,
    display_order       INTEGER NOT NULL DEFAULT 0,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_platform_plan_active    ON platform_plan(active, display_order);

-- ============================================================
-- Seed 3 plans
-- ============================================================

INSERT INTO platform_plan
    (code, name, tagline, monthly_price_pence, currency,
     invoice_limit, user_limit, features_json, feature_flags_json,
     highlighted, display_order, active)
VALUES
(
    'STARTER',
    'Starter',
    'For solo merchants getting started',
    10000, 'GBP',
    100, 3,
    '["Up to 100 invoices per month","3 user accounts","Webhook delivery","Suspicion detection","Audit trail","Email support"]',
    '{"WEBHOOKS":true,"SUSPICION_DETECTION":true,"REFUNDS":false,"XERO_AUTO_SYNC":false,"RECURRING_BILLING":false,"SUBSCRIPTIONS":false,"BULK_OPERATIONS":false,"MULTI_CURRENCY":false,"AUDIT_TRAIL":true,"TWO_FACTOR_AUTH":true}',
    FALSE, 10, TRUE
),
(
    'PRO',
    'Pro',
    'For growing teams that need automation',
    30000, 'GBP',
    500, 10,
    '["Up to 500 invoices per month","10 user accounts","Everything in Starter","Refund processing","Xero auto-sync","Recurring invoices","Priority support"]',
    '{"WEBHOOKS":true,"SUSPICION_DETECTION":true,"REFUNDS":true,"XERO_AUTO_SYNC":true,"RECURRING_BILLING":true,"SUBSCRIPTIONS":true,"BULK_OPERATIONS":false,"MULTI_CURRENCY":true,"AUDIT_TRAIL":true,"TWO_FACTOR_AUTH":true}',
    TRUE, 20, TRUE
),
(
    'ENTERPRISE',
    'Enterprise',
    'Unlimited scale with full platform access',
    100000, 'GBP',
    NULL, NULL,
    '["Unlimited invoices","Unlimited users","Everything in Pro","Bulk operations","Full audit trail exports","Dedicated support"]',
    '{"WEBHOOKS":true,"SUSPICION_DETECTION":true,"REFUNDS":true,"XERO_AUTO_SYNC":true,"RECURRING_BILLING":true,"SUBSCRIPTIONS":true,"BULK_OPERATIONS":true,"MULTI_CURRENCY":true,"AUDIT_TRAIL":true,"TWO_FACTOR_AUTH":true}',
    FALSE, 30, TRUE
)
ON CONFLICT (code) DO NOTHING;

COMMENT ON TABLE platform_plan IS 'SaaS plans sold to tenants, shown on public pricing page';