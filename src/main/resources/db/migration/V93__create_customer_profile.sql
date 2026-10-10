-- ============================================================
-- V93: Customer billing profile
-- ============================================================

CREATE TABLE IF NOT EXISTS customer_profile (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES app_user(id),
    tenant_id       BIGINT NOT NULL REFERENCES tenant(id),

    -- Billing address
    address_line1   VARCHAR(255),
    address_line2   VARCHAR(255),
    city            VARCHAR(100),
    region          VARCHAR(100),
    postal_code     VARCHAR(20),
    country         VARCHAR(2),

    -- Business info (for B2B invoices)
    company_name    VARCHAR(255),
    vat_number      VARCHAR(50),

    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    version         BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_customer_profile_user_tenant UNIQUE (user_id, tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_customer_profile_tenant ON customer_profile(tenant_id);
CREATE INDEX IF NOT EXISTS idx_customer_profile_user   ON customer_profile(user_id);