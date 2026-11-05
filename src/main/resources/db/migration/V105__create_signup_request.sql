-- ============================================================
-- V105: Signup requests for public self-service onboarding
-- ============================================================
-- Public visitors sign up for MERCHANT or MERCHANT_ADMIN accounts.
-- Account is created immediately (active=true) but a review record
-- is stored so Super Admin can track/follow-up.
-- ============================================================

CREATE TABLE IF NOT EXISTS signup_request (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    tenant_id           BIGINT NOT NULL REFERENCES tenant(id),
    full_name           VARCHAR(255) NOT NULL,
    email               VARCHAR(255) NOT NULL,
    company_name        VARCHAR(255),
    country_code        VARCHAR(2),
    plan_code           VARCHAR(50) NOT NULL,
    role_intent         VARCHAR(50) NOT NULL,        -- MERCHANT | MERCHANT_ADMIN
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW',
                                                     -- PENDING_REVIEW | REVIEWED | REJECTED
    review_notes        TEXT,
    reviewed_by         BIGINT REFERENCES app_user(id),
    reviewed_at         TIMESTAMP,
    requested_from_ip   VARCHAR(45),
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_signup_status        ON signup_request(status);
CREATE INDEX IF NOT EXISTS idx_signup_email         ON signup_request(email);
CREATE INDEX IF NOT EXISTS idx_signup_tenant        ON signup_request(tenant_id);
CREATE INDEX IF NOT EXISTS idx_signup_created_at    ON signup_request(created_at DESC);

-- Plan code on tenant so we know which plan each tenant is on
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS plan_code VARCHAR(50);
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS signup_at TIMESTAMP;

-- Backfill: existing tenants get Enterprise (they're your seed data)
UPDATE tenant SET plan_code = 'ENTERPRISE' WHERE plan_code IS NULL;

COMMENT ON COLUMN tenant.plan_code IS 'Platform plan: STARTER, PRO, ENTERPRISE';
COMMENT ON COLUMN tenant.signup_at IS 'When this tenant signed up (null for seed tenants)';