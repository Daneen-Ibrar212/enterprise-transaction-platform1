-- ============================================================
-- V113 — Add SHA-256 lookup column to api_key
--
-- Context: ApiKeyService.validateKey previously iterated every active key
-- and did a BCrypt match per key (O(N) × 100ms per request). This adds a
-- fast SHA-256 index so validateKey can do a single indexed lookup.
--
-- Behaviour:
--   1. Add nullable column
--   2. Deactivate any existing key (its plaintext is unrecoverable,
--      so we cannot compute the SHA-256 — users must regenerate)
--   3. Populate a non-null placeholder hash on those deactivated rows
--      so we can enforce NOT NULL
--   4. Enforce NOT NULL + unique
--   5. Index by tenant for tenant-scoped admin listing
-- ============================================================

ALTER TABLE api_key ADD COLUMN IF NOT EXISTS key_hash_sha256 VARCHAR(64);

-- Step 1: Deactivate any pre-existing keys (their plaintext is gone)
UPDATE api_key
   SET active = false
 WHERE key_hash_sha256 IS NULL;

-- Step 2: Give deactivated rows a placeholder hash so NOT NULL can be enforced.
--         These rows can never authenticate (active = false AND hash will never
--         match any real plaintext), so the placeholder value is safe.
--         Format: 'deactivated-<id>' — unique per row, satisfies the unique
--         constraint, and cannot collide with a real SHA-256 hex value.
UPDATE api_key
   SET key_hash_sha256 = 'deactivated-' || id::text
 WHERE key_hash_sha256 IS NULL;

-- Step 3: Enforce NOT NULL — any key created after this migration must
--         compute its SHA-256 at generation time.
ALTER TABLE api_key ALTER COLUMN key_hash_sha256 SET NOT NULL;

-- Step 4: Unique index for the fast lookup path
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_api_key_sha256'
    ) THEN
        ALTER TABLE api_key ADD CONSTRAINT uk_api_key_sha256
            UNIQUE (key_hash_sha256);
    END IF;
END $$;

-- Step 5: Tenant index for the admin UI listing
CREATE INDEX IF NOT EXISTS idx_api_key_tenant
    ON api_key(tenant_id);

COMMENT ON COLUMN api_key.key_hash_sha256 IS
    'SHA-256 of the plaintext key — fast indexed lookup for validateKey()';
COMMENT ON COLUMN api_key.key_hash IS
    'BCrypt hash of the plaintext key — secondary verification only';