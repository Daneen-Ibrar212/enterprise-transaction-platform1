-- ============================================================
-- V87: Webhook Replay Support
-- ============================================================

ALTER TABLE webhook_delivery ADD COLUMN IF NOT EXISTS is_replay BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE webhook_delivery ADD COLUMN IF NOT EXISTS replay_of_id BIGINT;
ALTER TABLE webhook_delivery ADD COLUMN IF NOT EXISTS replay_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_webhook_delivery_replay_of ON webhook_delivery(replay_of_id);

COMMENT ON COLUMN webhook_delivery.is_replay IS 'True if this delivery is a manual replay of an earlier one';
COMMENT ON COLUMN webhook_delivery.replay_of_id IS 'ID of the original delivery that was replayed';
COMMENT ON COLUMN webhook_delivery.replay_reason IS 'Optional reason provided by the admin';

-- ============================================================
-- V87: Add response_time_ms to webhook_delivery
-- ============================================================

ALTER TABLE webhook_delivery ADD COLUMN IF NOT EXISTS response_time_ms BIGINT;

COMMENT ON COLUMN webhook_delivery.response_time_ms IS 'Time taken for the HTTP request in milliseconds';