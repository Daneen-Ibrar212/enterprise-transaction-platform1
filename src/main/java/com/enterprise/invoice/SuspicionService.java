package com.enterprise.invoice;

import com.enterprise.feature.FeatureFlagService;
import com.enterprise.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Suspicion evaluation service.
 *
 * ✅ Note on SuspicionEnabledEvent:
 *   The @EventListener for SuspicionEnabledEvent has been REMOVED from this
 *   class — it previously double-listened alongside InvoiceService.onSuspicionEnabled,
 *   causing every "suspicion enabled" toggle to run re-evaluation TWICE
 *   (double DB scans, double notifications, double audit entries).
 *
 *   Re-evaluation is now triggered solely by InvoiceService.onSuspicionEnabled.
 *
 * ✅ The public {@link #reEvaluateAllInvoices()} method is retained so callers
 *   (e.g. AdminSuspicionController, FeatureFlagService toggles, etc.) that
 *   invoke it programmatically continue to work without a template/controller
 *   change. It simply delegates to InvoiceService.
 */
@Service
public class SuspicionService {

    private static final Logger log = LoggerFactory.getLogger(SuspicionService.class);

    private final SuspicionRuleRepository ruleRepository;
    private final FeatureFlagService featureFlagService;
    private final InvoiceService invoiceService;
    private final ExpressionParser parser = new SpelExpressionParser();

    public SuspicionService(SuspicionRuleRepository ruleRepository,
                            FeatureFlagService featureFlagService,
                            @Lazy InvoiceService invoiceService) {
        this.ruleRepository = ruleRepository;
        this.featureFlagService = featureFlagService;
        this.invoiceService = invoiceService;
    }

    // ============================================================
    // EVALUATE
    // ============================================================
    public SuspicionResult evaluate(Invoice invoice) {
        if (!featureFlagService.isEnabled("SUSPICION_DETECTION")) {
            log.debug("Suspicion detection is disabled – returning GREEN");
            return new SuspicionResult("GREEN", "Suspicion detection disabled");
        }

        Long tenantId = TenantContext.getRequiredTenantId();
        List<SuspicionRule> rules = ruleRepository.findByTenantIdAndActiveTrue(tenantId);

        EvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        context.setVariable("amount", invoice.getAmount());
        context.setVariable("description", invoice.getDescription());
        context.setVariable("customerEmail", invoice.getCustomerEmail());
        context.setVariable("merchantId", invoice.getMerchantId());

        for (SuspicionRule rule : rules) {
            try {
                Boolean matches = parser.parseExpression(rule.getConditionExpression())
                        .getValue(context, Boolean.class);
                if (Boolean.TRUE.equals(matches)) {
                    return new SuspicionResult(rule.getRiskLevel(), rule.getDescription());
                }
            } catch (Exception e) {
                log.error("Rule evaluation error (expr: {}): {}",
                        rule.getConditionExpression(), e.getMessage());
                // Fail secure: default to GREEN rather than denying every invoice
                return new SuspicionResult("GREEN", "Rule evaluation error – defaulting to GREEN");
            }
        }
        return new SuspicionResult("GREEN", "Normal invoice");
    }

    // ============================================================
    // RE-EVALUATE ALL INVOICES FOR CURRENT TENANT
    // ------------------------------------------------------------
    // Delegates to InvoiceService which owns the transaction and the
    // actual re-evaluation loop. This method exists so callers can
    // continue to invoke suspicionService.reEvaluateAllInvoices()
    // without depending on InvoiceService directly.
    // ============================================================
    public void reEvaluateAllInvoices() {
        if (!featureFlagService.isEnabled("SUSPICION_DETECTION")) {
            log.info("Suspicion detection is disabled – skipping re-evaluation");
            return;
        }
        invoiceService.reEvaluateAllInvoicesForSuspicion();
    }

    // ============================================================
    // RESULT DTO
    // ============================================================
    public static class SuspicionResult {
        private final String riskLevel;
        private final String reason;

        public SuspicionResult(String riskLevel, String reason) {
            this.riskLevel = riskLevel;
            this.reason = reason;
        }
        public String getRiskLevel() { return riskLevel; }
        public String getReason() { return reason; }
    }
}