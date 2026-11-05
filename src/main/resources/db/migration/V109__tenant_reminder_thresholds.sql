-- ============================================================
-- V109: Per-tenant invoice reminder thresholds
-- ============================================================
-- Defaults match the "7 / 14 / 30 days" cadence.
-- Each tenant can override from Admin → Reminders.
-- ============================================================

ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS reminder_first_days  INTEGER NOT NULL DEFAULT 7,
    ADD COLUMN IF NOT EXISTS reminder_second_days INTEGER NOT NULL DEFAULT 14,
    ADD COLUMN IF NOT EXISTS reminder_final_days  INTEGER NOT NULL DEFAULT 30;

COMMENT ON COLUMN tenant.reminder_first_days  IS 'Days after invoice creation to send 1st reminder';
COMMENT ON COLUMN tenant.reminder_second_days IS 'Days after invoice creation to send 2nd reminder';
COMMENT ON COLUMN tenant.reminder_final_days  IS 'Days after invoice creation to send final reminder';