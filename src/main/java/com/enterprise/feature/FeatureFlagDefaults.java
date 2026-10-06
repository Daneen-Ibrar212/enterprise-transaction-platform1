package com.enterprise.feature;

import java.util.Map;

/**
 * The default feature flags that every new tenant gets.
 * Add new flags here — they'll automatically apply to new tenants.
 */
public final class FeatureFlagDefaults {

    private FeatureFlagDefaults() {}

    public static final Map<String, Boolean> DEFAULTS = Map.ofEntries(
        Map.entry("TWO_FACTOR_AUTH", true),
        Map.entry("WEBHOOKS", true),
        Map.entry("REFUNDS", true),
        Map.entry("SUSPICION_DETECTION", true),
        Map.entry("XERO_AUTO_SYNC", false),         // off by default — needs connection
        Map.entry("RECURRING_BILLING", true),
        Map.entry("SUBSCRIPTIONS", true),
        Map.entry("BULK_OPERATIONS", true),
        Map.entry("AUDIT_TRAIL", true),
        Map.entry("MULTI_CURRENCY", true)
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
        Map.entry("MULTI_CURRENCY", "Enable multi-currency support")
    );
}