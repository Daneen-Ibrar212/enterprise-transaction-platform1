-- ============================================================
-- V117 — Seed permission tokens used by @PreAuthorize annotations
--         that are missing from the initial V3 seed
--
-- Without these rows, every @PreAuthorize("hasPermission(null, 'X')")
-- check returns 403 for every user, including SUPER_ADMIN.
--
-- Missing tokens found by static analysis of the codebase:
--   reconciliation:run, reconciliation:view_report (V3 has these ✓)
--   audit:view, audit:verify                         (V3 has these ✓)
--   reliability:view, reliability:retry              (MISSING)
--   feature:manage, feature:view                     (MISSING)
--   user:manage                                      (MISSING)
--   dispute:manage                                   (MISSING)
--   report:view                                      (MISSING)
--   webhook:manage                                   (MISSING)
-- ============================================================

INSERT INTO permission (resource, action, description) VALUES
    ('reliability',   'view',        'View reliability dashboard, DLQ, policies'),
    ('reliability',   'retry',       'Retry DLQ entries manually'),
    ('feature',       'view',        'View feature toggles for the tenant'),
    ('feature',       'manage',      'Create, toggle, delete feature toggles'),
    ('user',          'manage',      'Create, edit, revoke, restore users'),
    ('dispute',       'manage',      'Manage disputes / chargebacks'),
    ('report',        'view',        'View reports and dashboards'),
    ('webhook',       'manage',      'Create, edit, delete webhook endpoints')
ON CONFLICT (resource, action) DO NOTHING;

-- ============================================================
-- Grant new permissions to SUPER_ADMIN and ADMIN
-- ============================================================

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name IN ('SUPER_ADMIN', 'ADMIN')
  AND p.resource IN ('reliability', 'feature', 'user', 'dispute', 'report', 'webhook')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- MERCHANT_ADMIN gets everything except reliability (SUPER_ADMIN-only)
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'MERCHANT_ADMIN'
  AND p.resource IN ('feature', 'user', 'dispute', 'report', 'webhook')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- AUDITOR gets report:view only
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.name = 'AUDITOR'
  AND p.resource = 'report' AND p.action = 'view'
ON CONFLICT (role_id, permission_id) DO NOTHING;

COMMENT ON TABLE permission IS
    'Permission tokens used by @PreAuthorize("hasPermission(null, "resource:action")")';