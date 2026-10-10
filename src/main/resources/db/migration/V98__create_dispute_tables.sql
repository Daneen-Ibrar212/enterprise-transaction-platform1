-- ============================================================
-- V98: Dispute / chargeback management
-- ============================================================

CREATE TABLE IF NOT EXISTS dispute (
    id                  BIGSERIAL PRIMARY KEY,
    transaction_id      BIGINT NOT NULL REFERENCES transaction(id),
    tenant_id           BIGINT NOT NULL REFERENCES tenant(id),

    -- External reference (from Stripe/bank/webhook)
    external_id         VARCHAR(255),
    source              VARCHAR(30) NOT NULL DEFAULT 'MANUAL',  -- STRIPE, BANK, MANUAL

    -- Core info
    amount              NUMERIC(19,2) NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    reason              VARCHAR(50) NOT NULL,     -- FRAUDULENT, PRODUCT_NOT_RECEIVED, etc.
    status              VARCHAR(30) NOT NULL DEFAULT 'OPENED',

    -- Customer / merchant context
    customer_email      VARCHAR(255),
    merchant_id         BIGINT NOT NULL,

    -- Deadlines
    opened_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    respond_by          TIMESTAMP NOT NULL,
    resolved_at         TIMESTAMP,

    -- Outcome
    outcome_notes       TEXT,

    -- Payout hold flag
    payout_held         BOOLEAN NOT NULL DEFAULT TRUE,

    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP,
    version             BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_dispute_tenant       ON dispute(tenant_id);
CREATE INDEX IF NOT EXISTS idx_dispute_transaction  ON dispute(transaction_id);
CREATE INDEX IF NOT EXISTS idx_dispute_status       ON dispute(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_dispute_deadline     ON dispute(respond_by) WHERE status IN ('OPENED','UNDER_REVIEW');

CREATE TABLE IF NOT EXISTS dispute_evidence (
    id              BIGSERIAL PRIMARY KEY,
    dispute_id      BIGINT NOT NULL REFERENCES dispute(id) ON DELETE CASCADE,
    tenant_id       BIGINT NOT NULL REFERENCES tenant(id),

    evidence_type   VARCHAR(30) NOT NULL,     -- RECEIPT, SHIPPING, COMMUNICATION, REFUND_POLICY, OTHER
    description     TEXT,
    filename        VARCHAR(255),
    content_type    VARCHAR(100),
    content         BYTEA,                    -- optional — for small files

    submitted_by    BIGINT NOT NULL,
    submitted_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dispute_evidence_dispute ON dispute_evidence(dispute_id);
CREATE INDEX IF NOT EXISTS idx_dispute_evidence_tenant  ON dispute_evidence(tenant_id);