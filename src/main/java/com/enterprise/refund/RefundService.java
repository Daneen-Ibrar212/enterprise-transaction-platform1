package com.enterprise.refund;

import com.enterprise.audit.AuditService;
import com.enterprise.events.RefundProcessedEvent;
import com.enterprise.feature.FeatureFlagService;
import com.enterprise.ledger.LedgerService;
import com.enterprise.notification.NotificationService;
import com.enterprise.tenant.TenantContext;
import com.enterprise.transaction.Transaction;
import com.enterprise.transaction.TransactionRepository;
import com.enterprise.transaction.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRuleRepository ruleRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final FeatureFlagService featureFlagService;
    private final ExpressionParser parser = new SpelExpressionParser();

    public RefundService(RefundRuleRepository ruleRepository,
                         TransactionRepository transactionRepository,
                         LedgerService ledgerService,
                         AuditService auditService,
                         NotificationService notificationService,
                         ApplicationEventPublisher eventPublisher,
                         FeatureFlagService featureFlagService) {
        this.ruleRepository = ruleRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerService = ledgerService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
        this.featureFlagService = featureFlagService;
    }

    public RefundEligibility evaluate(Transaction transaction) {
        List<RefundRule> rules = ruleRepository.findByActiveTrueOrderByRulePriorityAsc();
        EvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        context.setVariable("amount", transaction.getAmount());
        context.setVariable("customerId", transaction.getCustomerId());
        context.setVariable("merchantId", transaction.getMerchantId());

        for (RefundRule rule : rules) {
            try {
                Boolean matches = parser.parseExpression(rule.getConditionExpression())
                        .getValue(context, Boolean.class);
                if (Boolean.TRUE.equals(matches)) {
                    return new RefundEligibility(rule.getAction(), rule.getRequiredPermission());
                }
            } catch (Exception e) {
                log.warn("Rule evaluation failed for expression {}: {}",
                        rule.getConditionExpression(), e.getMessage());
                return new RefundEligibility("DENY", null);
            }
        }
        return new RefundEligibility("DENY", null);
    }

    @Transactional
    public Transaction processRefund(Long originalTransactionId, Long adminId, String reason) {
        if (!featureFlagService.isEnabled("REFUNDS")) {
            throw new RuntimeException("Refund feature is currently disabled.");
        }

        Transaction original = transactionRepository.findById(originalTransactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        if (original.getStatus() != TransactionStatus.SETTLED) {
            throw new IllegalStateException("Only settled transactions can be refunded");
        }

        RefundEligibility eligibility = evaluate(original);
        if ("DENY".equals(eligibility.getAction())) {
            throw new RuntimeException("Refund not allowed by policy");
        }

        // ✅ Correct constructor argument order: (invoiceId, customerId, merchantId, ...)
        //    The previous code passed (invoiceId, merchantId, customerId) — swapped.
        Transaction refund = new Transaction(
                original.getInvoiceId(),
                original.getCustomerId(),   // ← customer
                original.getMerchantId(),   // ← merchant
                original.getAmount().negate(),
                "refund-" + System.currentTimeMillis()
        );
        refund.setTenantId(original.getTenantId());
        refund.setCurrency(original.getCurrency());
        refund.setStatus(TransactionStatus.REFUNDED);
        refund = transactionRepository.save(refund);

        // Ledger: reverse the original debit/credit
        ledgerService.recordCredit(original.getCustomerId(), original.getAmount(), refund.getId());
        ledgerService.recordDebit(original.getMerchantId(), original.getAmount(), refund.getId());
        ledgerService.validateTransactionBalance(refund.getId());

        original.setStatus(TransactionStatus.REFUNDED);
        transactionRepository.save(original);

        Map<String, Object> beforeMap = new HashMap<>();
        beforeMap.put("id", original.getId());
        beforeMap.put("status", original.getStatus().name());
        beforeMap.put("amount", original.getAmount());

        Map<String, Object> afterMap = new HashMap<>();
        afterMap.put("id", refund.getId());
        afterMap.put("status", refund.getStatus().name());
        afterMap.put("amount", refund.getAmount());

        auditService.recordEvent(
                "REFUND_PROCESSED",
                adminId,
                Map.of(
                        "originalTransactionId", originalTransactionId,
                        "refundTransactionId", refund.getId(),
                        "amount", original.getAmount(),
                        "reason", reason
                ),
                "Transaction",
                refund.getId(),
                beforeMap,
                afterMap
        );

        notificationService.createNotification(original.getCustomerId(),
                "REFUND_RECEIVED", "Refund Processed",
                "Refund of " + original.getAmount() + " for transaction " + originalTransactionId,
                "/transactions/" + refund.getId());
        notificationService.createNotification(original.getMerchantId(),
                "REFUND_ISSUED", "Refund Issued",
                "Refund of " + original.getAmount() + " to customer",
                "/transactions/" + refund.getId());

        eventPublisher.publishEvent(new RefundProcessedEvent(refund, original));
        return refund;
    }

    public int bulkRefundTransactions(List<Long> transactionIds, Long adminId, String reason) {
        if (!featureFlagService.isEnabled("REFUNDS")) {
            throw new RuntimeException("Refund feature is currently disabled.");
        }
        int refundedCount = 0;
        for (Long txId : transactionIds) {
            try {
                processRefund(txId, adminId, reason);
                refundedCount++;
            } catch (Exception e) {
                log.error("Failed to refund transaction {}: {}", txId, e.getMessage(), e);
            }
        }
        return refundedCount;
    }

    public static class RefundEligibility {
        private final String action;
        private final String requiredPermission;
        public RefundEligibility(String action, String requiredPermission) {
            this.action = action;
            this.requiredPermission = requiredPermission;
        }
        public String getAction() { return action; }
        public String getRequiredPermission() { return requiredPermission; }
    }
}