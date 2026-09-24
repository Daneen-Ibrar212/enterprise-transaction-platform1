-- ============================================================
-- V86: Add 2FA admin override fields to app_user
-- ============================================================

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS two_factor_disabled_by_admin BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS two_factor_disabled_at TIMESTAMP;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS two_factor_disabled_by BIGINT;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS two_factor_disable_expires_at TIMESTAMP;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS two_factor_disable_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_app_user_2fa_disabled ON app_user(two_factor_disabled_by_admin);
CREATE INDEX IF NOT EXISTS idx_app_user_2fa_expires ON app_user(two_factor_disable_expires_at);

COMMENT ON COLUMN app_user.two_factor_disabled_by_admin IS 'Super Admin temporarily disabled 2FA for this user';
COMMENT ON COLUMN app_user.two_factor_disable_expires_at IS 'When the 2FA override expires (null = permanent until re-enabled)';
COMMENT ON COLUMN app_user.two_factor_disable_reason IS 'Reason for disabling 2FA (lost device, etc.)';