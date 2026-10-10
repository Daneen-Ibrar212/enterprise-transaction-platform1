-- ============================================================
-- V94: Customer-generated statement PDFs
-- ============================================================

CREATE TABLE IF NOT EXISTS customer_receipt (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES app_user(id),
    tenant_id       BIGINT NOT NULL REFERENCES tenant(id),

    filename        VARCHAR(255) NOT NULL,
    start_date      DATE NOT NULL,
    end_date        DATE NOT NULL,
    content         BYTEA NOT NULL,
    content_type    VARCHAR(50) NOT NULL DEFAULT 'application/pdf',

    generated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_customer_receipt_tenant ON customer_receipt(tenant_id);
CREATE INDEX IF NOT EXISTS idx_customer_receipt_user   ON customer_receipt(user_id);
CREATE INDEX IF NOT EXISTS idx_customer_receipt_generated ON customer_receipt(generated_at DESC);