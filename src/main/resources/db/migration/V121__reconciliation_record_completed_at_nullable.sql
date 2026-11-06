-- ============================================================
-- V122 — Allow completed_at to be NULL on reconciliation_record
--
-- Context: ReconciliationRecord.completedAt is set at the end of a run.
-- If the process crashes mid-run, the record is persisted with startedAt
-- but no completedAt. The entity uses `nullable = false` on completedAt,
-- which prevents this from being persisted at all — but if you want
-- crash-recovery semantics, completedAt must be nullable.
--
-- Choose ONE:
--   (a) Keep completedAt NOT NULL and fix the entity to persist only
--       after the run finishes (current behaviour — no schema change).
--   (b) Make it nullable and accept records with no completion time.
--
-- This migration takes option (b) — safer for crash debugging.
-- ============================================================

ALTER TABLE reconciliation_record
    ALTER COLUMN completed_at DROP NOT NULL;

COMMENT ON COLUMN reconciliation_record.completed_at IS
    'NULL if the run crashed before completion';