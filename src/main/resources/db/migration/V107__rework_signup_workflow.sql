-- ============================================================
-- V107: Rework signup workflow
-- ============================================================
-- 1. New statuses on signup_request: PENDING_APPROVAL, APPROVED,
--    APPROVED_AWAITING_PAYMENT, PAID, REJECTED
-- 2. Multi-role intent (comma-separated string in role_intent)
-- 3. Approval audit columns
-- 4. Tenant gets subscription_status + trial_ends_at
-- ============================================================

-- ---------- signup_request ----------
ALTER TABLE signup_request
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS approved_by BIGINT REFERENCES app_user(id),
    ADD COLUMN IF NOT EXISTS paid_at TIMESTAMP;

-- Existing rows keep whatever status they had.
-- New workflow uses: PENDING_APPROVAL | APPROVED | APPROVED_AWAITING_PAYMENT | PAID | REJECTED

-- Make sure role_intent can hold multiple values
ALTER TABLE signup_request
    ALTER COLUMN role_intent TYPE VARCHAR(100);

COMMENT ON COLUMN signup_request.role_intent IS
    'Comma-separated role codes, e.g. MERCHANT or MERCHANT,MERCHANT_ADMIN';

-- ---------- tenant ----------
ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS subscription_status VARCHAR(30) NOT NULL DEFAULT 'TRIAL',
    ADD COLUMN IF NOT EXISTS trial_ends_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS subscription_renews_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS grace_period_ends_at TIMESTAMP;

-- Backfill existing tenants so they have a sane state
UPDATE tenant
SET subscription_status = 'ACTIVE',
    trial_ends_at = NULL
WHERE subscription_status = 'TRIAL'
  AND plan_code IS NOT NULL
  AND plan_code <> '';

COMMENT ON COLUMN tenant.subscription_status IS
    'TRIAL | ACTIVE | PAST_DUE | CANCELLED | SUSPENDED';
COMMENT ON COLUMN tenant.trial_ends_at IS
    'When the 14-day free trial ends. NULL after subscription activates.';
COMMENT ON COLUMN tenant.grace_period_ends_at IS
    'If payment fails, grace period before account is suspended.';

CREATE INDEX IF NOT EXISTS idx_signup_request_status
    ON signup_request(status);

CREATE INDEX IF NOT EXISTS idx_tenant_subscription_status
    ON tenant(subscription_status);