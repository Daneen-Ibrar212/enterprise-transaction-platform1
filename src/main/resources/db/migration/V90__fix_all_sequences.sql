-- ============================================================
-- V90: Reset all primary-key sequences to MAX(id)
-- ============================================================
-- Fixes the case where seed migrations insert explicit IDs
-- but don't advance the auto-increment sequence.
-- ============================================================

DO $$
DECLARE
    r RECORD;
    seq_name TEXT;
    max_id BIGINT;
    col_exists BOOLEAN;
BEGIN
    FOR r IN 
        SELECT table_name 
        FROM information_schema.tables 
        WHERE table_schema = 'public' 
          AND table_type = 'BASE TABLE'
          -- ✅ Skip Flyway's own history table (no `id` column)
          AND table_name NOT IN ('flyway_schema_history')
    LOOP
        -- ✅ Skip tables that don't have an `id` column at all
        SELECT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = r.table_name
              AND column_name = 'id'
        ) INTO col_exists;

        IF NOT col_exists THEN
            CONTINUE;
        END IF;

        seq_name := pg_get_serial_sequence(r.table_name, 'id');
        IF seq_name IS NOT NULL THEN
            EXECUTE format('SELECT COALESCE(MAX(id), 1) FROM %I', r.table_name) INTO max_id;
            EXECUTE format('SELECT setval(%L, %s, true)', seq_name, max_id);
            RAISE NOTICE 'Fixed sequence for %.id → %', r.table_name, max_id;
        END IF;
    END LOOP;
END $$;