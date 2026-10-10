-- ============================================================
-- V100: Reconciliation repair tracking
-- ============================================================

ALTER TABLE reconciliation_detail ADD COLUMN IF NOT EXISTS
    repair_status VARCHAR(30) NOT NULL DEFAULT 'PENDING';
    -- PENDING, REPAIRED, ESCALATED, SKIPPED, FAILED

ALTER TABLE reconciliation_detail ADD COLUMN IF NOT EXISTS
    repaired_at TIMESTAMP;

ALTER TABLE reconciliation_detail ADD COLUMN IF NOT EXISTS
    repaired_by BIGINT;

ALTER TABLE reconciliation_detail ADD COLUMN IF NOT EXISTS
    repair_notes TEXT;

CREATE INDEX IF NOT EXISTS idx_recon_detail_repair_status
    ON reconciliation_detail(repair_status);