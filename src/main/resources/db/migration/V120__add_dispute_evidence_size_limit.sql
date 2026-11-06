-- ============================================================
-- V121 — Enforce a maximum size on dispute_evidence.content
--
-- Context: AdminDisputeController allows evidence file uploads. The
-- controller also caps at 10 MB, but the DB had no server-side guard.
-- This adds a CHECK constraint as a last line of defense.
-- ============================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_dispute_evidence_size'
    ) THEN
        ALTER TABLE dispute_evidence
            ADD CONSTRAINT chk_dispute_evidence_size
            CHECK (content IS NULL OR octet_length(content) <= 10 * 1024 * 1024);
    END IF;
END $$;

COMMENT ON CONSTRAINT chk_dispute_evidence_size ON dispute_evidence IS
    'Reject evidence files larger than 10 MB at the DB layer';