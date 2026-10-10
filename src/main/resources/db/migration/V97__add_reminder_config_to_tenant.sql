-- ============================================================
-- V97: Per-tenant reminder configuration
-- ============================================================

ALTER TABLE tenant ADD COLUMN IF NOT EXISTS reminder_first_days  INT NOT NULL DEFAULT 7;
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS reminder_second_days INT NOT NULL DEFAULT 14;
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS reminder_final_days  INT NOT NULL DEFAULT 30;
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS reminder_email_from  VARCHAR(255);