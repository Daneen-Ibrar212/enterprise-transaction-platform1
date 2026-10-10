-- ============================================================
-- V104: Password reset tokens + password change tracking
-- ============================================================

-- Reset tokens — hashed, one-time use, short-lived
CREATE TABLE IF NOT EXISTS password_reset_token (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash      VARCHAR(255) NOT NULL UNIQUE,
    expires_at      TIMESTAMP NOT NULL,
    used            BOOLEAN NOT NULL DEFAULT FALSE,
    used_at         TIMESTAMP,
    requested_from_ip VARCHAR(45),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_password_reset_user     ON password_reset_token(user_id);
CREATE INDEX IF NOT EXISTS idx_password_reset_hash     ON password_reset_token(token_hash);
CREATE INDEX IF NOT EXISTS idx_password_reset_expires  ON password_reset_token(expires_at);

-- Track when a user last changed their password
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP;
UPDATE app_user SET password_changed_at = created_at WHERE password_changed_at IS NULL;