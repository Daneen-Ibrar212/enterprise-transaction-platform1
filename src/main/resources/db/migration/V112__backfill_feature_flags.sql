-- Backfill the standard feature-flag set for any tenant that is missing flags.
-- Idempotent: uses NOT EXISTS so re-running is safe, and won't overwrite
-- flags that a tenant already has.

INSERT INTO feature_flag (tenant_id, name, enabled, description, updated_at)
SELECT t.id, f.name, f.enabled, f.description, NOW()
  FROM tenant t
  CROSS JOIN (VALUES
      ('REFUNDS',                true,  'Enable refund processing'),
      ('WEBHOOKS',               true,  'Enable webhook delivery'),
      ('SUSPICION_DETECTION',    true,  'Enable suspicion detection'),
      ('TWO_FACTOR_AUTH',        false, 'Enable two-factor authentication'),
      ('RELIABILITY_POLICIES',   true,  'Enable reliability policies'),
      ('SIMULATOR_ENABLED',      false, 'Enable simulator mode'),
      ('XERO_AUTO_SYNC',         false, 'Automatically sync invoices to Xero when approved'),
      ('EMAIL_NOTIFICATIONS',    true,  'Enable email notifications'),
      ('RECURRING_INVOICES',     true,  'Enable recurring invoice scheduling'),
      ('RECURRING_BILLING',      true,  'Enable recurring invoices'),
      ('BULK_OPERATIONS',        true,  'Enable bulk operations for invoices'),
      ('API_ACCESS',             true,  'Enable API key access'),
      ('AUDIT_LOGGING',          true,  'Enable detailed audit logging'),
      ('MULTI_CURRENCY',         true,  'Enable multi-currency support'),
      ('TAX_CALCULATION',        true,  'Enable automatic tax calculation'),
      ('RATE_LIMITING',          true,  'Enable API rate limiting'),
      ('SUBSCRIPTIONS',          true,  'Enable subscription management'),
      ('INVOICE_REMINDERS',      true,  'Send automated payment reminders for overdue invoices'),
      ('DISPUTES',               true,  'Enable dispute / chargeback management'),
      ('RECONCILIATION_AUTOFIX', true,  'Allow auto-repair of MISSING_LEDGER discrepancies')
  ) AS f(name, enabled, description)
 WHERE NOT EXISTS (
     SELECT 1
       FROM feature_flag x
      WHERE x.tenant_id = t.id
        AND x.name = f.name
 );