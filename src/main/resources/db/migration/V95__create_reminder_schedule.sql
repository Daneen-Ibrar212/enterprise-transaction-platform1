-- ============================================================
-- V95: Invoice reminder tracking
-- ============================================================

CREATE TABLE IF NOT EXISTS reminder_schedule (
    id              BIGSERIAL PRIMARY KEY,
    invoice_id      BIGINT NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    tenant_id       BIGINT NOT NULL REFERENCES tenant(id),

    reminder_type   VARCHAR(20) NOT NULL,     -- FIRST, SECOND, FINAL
    days_overdue    INT NOT NULL,             -- 7, 14, 30
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, SENT, FAILED, SKIPPED

    scheduled_for   DATE NOT NULL,
    sent_at         TIMESTAMP,
    error_message   TEXT,

    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_reminder_invoice_type UNIQUE (invoice_id, reminder_type)
);

CREATE INDEX IF NOT EXISTS idx_reminder_tenant_status ON reminder_schedule(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_reminder_invoice       ON reminder_schedule(invoice_id);
CREATE INDEX IF NOT EXISTS idx_reminder_scheduled     ON reminder_schedule(scheduled_for) WHERE status = 'PENDING';