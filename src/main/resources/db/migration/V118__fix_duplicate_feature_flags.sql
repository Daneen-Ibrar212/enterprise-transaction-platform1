-- ============================================================
-- V118 — Clean up duplicate feature_flag rows
--
-- Symptom: V91, V112, and other flag backfills ran on top of V27 (where
-- feature_flag.name was UNIQUE) and then V68 (which fixed the constraint).
-- If V27's constraint was still live when any backfill ran, the insert
-- would have failed; if V68 ran first, backfills succeeded — either way,
-- any tenant with pre-V27 rows plus V68's re-keying can end up with
-- logical duplicates (same name, different tenant_id) that violate the
-- spirit of the flag.
--
-- This migration is a no-op for healthy data.
-- ============================================================

-- Delete duplicates within a single tenant — keep the oldest per (tenant, name)
DELETE FROM feature_flag f
WHERE f.id NOT IN (
    SELECT MIN(id)
    FROM feature_flag
    GROUP BY tenant_id, name
);

-- Reassert the composite unique constraint (idempotent — V68 already did this)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_feature_flag_name_tenant'
    ) THEN
        ALTER TABLE feature_flag ADD CONSTRAINT uk_feature_flag_name_tenant
            UNIQUE (name, tenant_id);
    END IF;
END $$;

-- Sanity check: no tenant should be missing any default flag after V112
-- (this query just reports in the log — doesn't modify data)
DO $$
DECLARE missing_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO missing_count
    FROM (
        SELECT t.id AS tenant_id,
               d.name
          FROM tenant t
         CROSS JOIN (VALUES
             ('REFUNDS'), ('WEBHOOKS'), ('SUSPICION_DETECTION'), ('TWO_FACTOR_AUTH'),
             ('SIMULATOR_ENABLED'), ('XERO_AUTO_SYNC'), ('RECURRING_BILLING'),
             ('SUBSCRIPTIONS'), ('INVOICE_REMINDERS'), ('DISPUTES'),
             ('RECONCILIATION_AUTOFIX')
         ) AS d(name)
         LEFT JOIN feature_flag f
             ON f.tenant_id = t.id AND f.name = d.name
        WHERE f.id IS NULL
    ) AS missing;

    IF missing_count > 0 THEN
        RAISE NOTICE 'V118: % tenant × flag combinations are still missing — V112 may not have run. Run V112 again if intended.', missing_count;
    END IF;
END $$;