package com.enterprise.reconciliation;

import com.enterprise.audit.AuditService;
import com.enterprise.ledger.LedgerService;
import com.enterprise.reliability.DlqEntry;
import com.enterprise.reliability.DlqEntryRepository;
import com.enterprise.tenant.TenantContext;
import com.enterprise.transaction.Transaction;
import com.enterprise.transaction.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class ReconciliationAutoFixService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationAutoFixService.class);

    private final ReconciliationDetailRepository detailRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final DlqEntryRepository dlqEntryRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ReconciliationAutoFixService(ReconciliationDetailRepository detailRepository,
                                        TransactionRepository transactionRepository,
                                        LedgerService ledgerService,
                                        DlqEntryRepository dlqEntryRepository,
                                        AuditService auditService,
                                        ObjectMapper objectMapper) {
        this.detailRepository = detailRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerService = ledgerService;
        this.dlqEntryRepository = dlqEntryRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ============================================================
    // AUTO-REPAIR MISSING_LEDGER
    // ============================================================
    @Transactional
    public ReconciliationDetail repairMissingLedger(Long detailId, Long adminId) {
        ReconciliationDetail detail = detailRepository.findById(detailId)
                .orElseThrow(() -> new RuntimeException("Reconciliation detail not found"));

        if (!"MISSING_LEDGER".equals(detail.getDiscrepancyType())) {
            throw new IllegalStateException(
                "Auto-fix only supports MISSING_LEDGER. This is: " + detail.getDiscrepancyType());
        }

        if ("REPAIRED".equals(detail.getRepairStatus())) {
            log.info("Detail {} already repaired — skipping", detailId);
            return detail;
        }

        Transaction tx = transactionRepository.findById(detail.getTransactionId())
                .orElseThrow(() -> new RuntimeException("Transaction not found for detail"));

        BigDecimal amount = tx.getAmount();

        // Reconstruct: debit customer, credit merchant
        ledgerService.recordDebit(tx.getCustomerId(), amount, tx.getId());
        ledgerService.recordCredit(tx.getMerchantId(), amount, tx.getId());
        ledgerService.validateTransactionBalance(tx.getId());

        detail.setRepairStatus("REPAIRED");
        detail.setRepairedAt(LocalDateTime.now());
        detail.setRepairedBy(adminId);
        detail.setRepairNotes(String.format(
                "Auto-repaired: created DEBIT %.2f for account %d and CREDIT %.2f for account %d",
                amount, tx.getCustomerId(), amount, tx.getMerchantId()));

        ReconciliationDetail saved = detailRepository.save(detail);

        auditService.recordEvent(
                "RECONCILIATION_AUTO_REPAIRED",
                adminId,
                Map.of("detailId", detailId, "transactionId", tx.getId(),
                        "amount", amount.toPlainString(), "type", "MISSING_LEDGER"),
                "ReconciliationDetail",
                detailId,
                Map.of("repairStatus", "PENDING"),
                Map.of("repairStatus", "REPAIRED")
        );

        log.info("✅ Auto-repaired MISSING_LEDGER for transaction {} (detail {})",
                tx.getId(), detailId);
        return saved;
    }

    // ============================================================
    // ESCALATE AMOUNT_MISMATCH TO DLQ
    // ============================================================
    @Transactional
    public ReconciliationDetail escalateToDlq(Long detailId, Long adminId) {
        ReconciliationDetail detail = detailRepository.findById(detailId)
                .orElseThrow(() -> new RuntimeException("Reconciliation detail not found"));

        if ("ESCALATED".equals(detail.getRepairStatus())) {
            log.info("Detail {} already escalated — skipping", detailId);
            return detail;
        }

        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "detailId", detail.getId(),
                    "reconciliationRecordId", detail.getReconciliationRecord().getId(),
                    "transactionId", detail.getTransactionId(),
                    "discrepancyType", detail.getDiscrepancyType(),
                    "expectedAmount", detail.getExpectedAmount() != null
                            ? detail.getExpectedAmount().toPlainString() : "null",
                    "actualAmount", detail.getActualAmount() != null
                            ? detail.getActualAmount().toPlainString() : "null",
                    "description", detail.getDescription() != null ? detail.getDescription() : ""
            ));

            DlqEntry entry = new DlqEntry();
            entry.setOperationType("ReconciliationService.escalateMismatch");
            entry.setPayload(payload);
            entry.setFailureReason("Amount mismatch requires human review");
            entry.setStatus("PENDING");
            entry.setTenantId(TenantContext.getRequiredTenantId());
            dlqEntryRepository.save(entry);

            detail.setRepairStatus("ESCALATED");
            detail.setRepairedAt(LocalDateTime.now());
            detail.setRepairedBy(adminId);
            detail.setRepairNotes("Escalated to DLQ entry #" + entry.getId());
            ReconciliationDetail saved = detailRepository.save(detail);

            auditService.recordEvent(
                    "RECONCILIATION_ESCALATED_TO_DLQ",
                    adminId,
                    Map.of("detailId", detailId, "dlqEntryId", entry.getId(),
                            "transactionId", detail.getTransactionId()),
                    "ReconciliationDetail",
                    detailId,
                    Map.of("repairStatus", "PENDING"),
                    Map.of("repairStatus", "ESCALATED")
            );

            log.info("📮 Escalated reconciliation detail {} to DLQ entry {}",
                    detailId, entry.getId());
            return saved;

        } catch (Exception e) {
            log.error("Failed to escalate detail {} to DLQ", detailId, e);
            detail.setRepairStatus("FAILED");
            detail.setRepairNotes("Escalation failed: " + e.getMessage());
            return detailRepository.save(detail);
        }
    }

    // ============================================================
    // BULK REPAIR
    // ============================================================
    @Transactional
    public int bulkRepairMissingLedger(Long recordId, Long adminId) {
        List<ReconciliationDetail> targets =
                detailRepository.findByReconciliationRecordIdAndRepairStatus(recordId, "PENDING")
                        .stream()
                        .filter(d -> "MISSING_LEDGER".equals(d.getDiscrepancyType()))
                        .toList();

        int repaired = 0;
        for (ReconciliationDetail d : targets) {
            try {
                repairMissingLedger(d.getId(), adminId);
                repaired++;
            } catch (Exception e) {
                log.error("Bulk repair failed for detail {}", d.getId(), e);
            }
        }
        return repaired;
    }
}