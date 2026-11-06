package com.enterprise.feature;

import java.util.Map;

/**
 * The default feature flags that every new tenant gets.
 * Add new flags here — they'll automatically apply to new tenants.
 *
 * ⚠️ IMPORTANT: every flag name that appears in a call to
 *    FeatureFlagService.setEnabled(...) or isEnabled(...) MUST be listed in
 *    DEFAULTS. If it isn't, setEnabled(...) throws
 *    IllegalArgumentException("Feature flag not found").
 *
 *    Flags used elsewhere in the codebase that were previously missing:
 *      • INVOICE_REMINDERS  — AdminReminderController, ReminderScheduler
 *      • DISPUTES           — DisputeScheduler
 *      • SIMULATOR_ENABLED  — SimulatorService
 */
public final class FeatureFlagDefaults {

    private FeatureFlagDefaults() {}

    public static final Map<String, Boolean> DEFAULTS = Map.ofEntries(
        Map.entry("TWO_FACTOR_AUTH", true),
        Map.entry("WEBHOOKS", true),
        Map.entry("REFUNDS", true),
        Map.entry("SUSPICION_DETECTION", true),
        Map.entry("XERO_AUTO_SYNC", false),
        Map.entry("RECURRING_BILLING", true),
        Map.entry("SUBSCRIPTIONS", true),
        Map.entry("BULK_OPERATIONS", true),
        Map.entry("AUDIT_TRAIL", true),
        Map.entry("MULTI_CURRENCY", true),
        // ✅ ADDED — referenced by ReminderScheduler / AdminReminderController
        Map.entry("INVOICE_REMINDERS", true),
        // ✅ ADDED — referenced by DisputeScheduler
        Map.entry("DISPUTES", true),
        // ✅ ADDED — referenced by SimulatorService (off in prod by default)
        Map.entry("SIMULATOR_ENABLED", false)
    );

    public static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
        Map.entry("TWO_FACTOR_AUTH", "Require 2FA for admin users"),
        Map.entry("WEBHOOKS", "Enable webhook delivery"),
        Map.entry("REFUNDS", "Enable refund processing"),
        Map.entry("SUSPICION_DETECTION", "Enable suspicion rules"),
        Map.entry("XERO_AUTO_SYNC", "Auto-sync invoices to Xero"),
        Map.entry("RECURRING_BILLING", "Enable recurring invoices"),
        Map.entry("SUBSCRIPTIONS", "Enable subscription management"),
        Map.entry("BULK_OPERATIONS", "Enable bulk approve/reject"),
        Map.entry("AUDIT_TRAIL", "Enable audit logging"),
        Map.entry("MULTI_CURRENCY", "Enable multi-currency support"),
        Map.entry("INVOICE_REMINDERS", "Send automatic payment reminders for unpaid invoices"),
        Map.entry("DISPUTES", "Enable dispute / chargeback management"),
        Map.entry("SIMULATOR_ENABLED", "Enable payment simulator (sim_* tokens in description)")
    );
}