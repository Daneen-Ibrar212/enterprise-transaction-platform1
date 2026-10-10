-- ============================================================
-- V103: Add PAUSED and EXPIRED to subscription statuses
-- ============================================================
-- No schema change needed — status is VARCHAR, we just start
-- using PAUSED and EXPIRED more explicitly. Documenting here.

-- Track paused date separately so we know when to auto-resume
ALTER TABLE customer_subscription ADD COLUMN IF NOT EXISTS paused_at TIMESTAMP;
ALTER TABLE customer_subscription ADD COLUMN IF NOT EXISTS pause_reason TEXT;
ALTER TABLE customer_subscription ADD COLUMN IF NOT EXISTS usage_based BOOLEAN NOT NULL DEFAULT FALSE;