-- ============================================================
-- V96: Feature flag for invoice reminders
-- ============================================================

INSERT INTO feature_flag (name, enabled, description, tenant_id, updated_at)
SELECT 'INVOICE_REMINDERS', true, 'Send automated payment reminders for overdue invoices', t.id, NOW()
FROM tenant t
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flag f
    WHERE f.name = 'INVOICE_REMINDERS' AND f.tenant_id = t.id
);