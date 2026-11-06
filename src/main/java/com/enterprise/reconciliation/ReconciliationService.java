package com.enterprise.reconciliation;

import com.enterprise.ledger.LedgerEntry;
import com.enterprise.ledger.LedgerRepository;
import com.enterprise.tenant.TenantContext;
import com.enterprise.transaction.Transaction;
import com.enterprise.transaction.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final TransactionRepository transactionRepository;
    private final LedgerRepository ledgerRepository;
    private final ReconciliationRecordRepository recordRepository;
    private final ReconciliationDetailRepository detailRepository;

    public ReconciliationService(TransactionRepository transactionRepository,
                                 LedgerRepository ledgerRepository,
                                 ReconciliationRecordRepository recordRepository,
                                 ReconciliationDetailRepository detailRepository) {
        this.transactionRepository = transactionRepository;
        this.ledgerRepository = ledgerRepository;
        this.recordRepository = recordRepository;
        this.detailRepository = detailRepository;
    }

    @Transactional
    public ReconciliationRecord runReconciliation() {
        Long tenantId = TenantContext.getRequiredTenantId();
        log.info("Starting reconciliation for tenant {}", tenantId);
        LocalDateTime startedAt = LocalDateTime.now();

        int pageSize = 100, page = 0;
        int total = 0, matched = 0, mismatched = 0;
        int missingLedgerCount = 0, amountMismatchCount = 0;
        List<ReconciliationDetail> details = new ArrayList<>();

        // ✅ Only iterate THIS tenant's transactions explicitly — not relying on
        //    the filter to be enabled (filter is still enabled at the SQL level
        //    for consistency, but this is the authoritative scope).
        Page<Transaction> transactionPage;
        do {
            transactionPage = transactionRepository.findAll(PageRequest.of(page, pageSize));
            List<Transaction> transactions = transactionPage.getContent();
            // Belt & suspenders: skip any rows whose tenantId doesn't match
            transactions = transactions.stream()
                    .filter(tx -> tenantId.equals(tx.getTenantId()))
                    .toList();
            total += transactions.size();

            if (!transactions.isEmpty()) {
                List<Long> txIds = transactions.stream().map(Transaction::getId).collect(Collectors.toList());
                List<LedgerEntry> batchEntries = ledgerRepository.findByTransactionIdIn(txIds);
                Map<Long, List<LedgerEntry>> ledgersByTx = batchEntries.stream()
                        .collect(Collectors.groupingBy(LedgerEntry::getTransactionId));

                for (Transaction tx : transactions) {
                    List<LedgerEntry> entries = ledgersByTx.getOrDefault(tx.getId(), Collections.emptyList());
                    if (entries.isEmpty()) {
                        missingLedgerCount++;
                        mismatched++;
                        ReconciliationDetail detail = new ReconciliationDetail();
                        detail.setTransactionId(tx.getId());
                        detail.setDiscrepancyType("MISSING_LEDGER");
                        detail.setExpectedAmount(tx.getAmount());
                        detail.setActualAmount(BigDecimal.ZERO);
                        detail.setDescription("No ledger entries found for transaction");
                        details.add(detail);
                        continue;
                    }
                    BigDecimal sumDebit = entries.stream()
                            .filter(e -> "DEBIT".equals(e.getEntryType().name()))
                            .map(LedgerEntry::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal sumCredit = entries.stream()
                            .filter(e -> "CREDIT".equals(e.getEntryType().name()))
                            .map(LedgerEntry::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (sumDebit.compareTo(tx.getAmount()) != 0 || sumCredit.compareTo(tx.getAmount()) != 0) {
                        amountMismatchCount++;
                        mismatched++;
                        ReconciliationDetail detail = new ReconciliationDetail();
                        detail.setTransactionId(tx.getId());
                        detail.setDiscrepancyType("AMOUNT_MISMATCH");
                        detail.setExpectedAmount(tx.getAmount());
                        detail.setActualAmount(sumDebit);
                        detail.setDescription(String.format("Debit sum: %.2f, Credit sum: %.2f", sumDebit, sumCredit));
                        details.add(detail);
                    } else {
                        matched++;
                    }
                }
            }
            page++;
        } while (transactionPage.hasNext());

        ReconciliationRecord record = new ReconciliationRecord();
        record.setStartedAt(startedAt);
        record.setCompletedAt(LocalDateTime.now());
        record.setStatus(mismatched == 0 ? "PASS" : "FAIL");
        record.setTotalTransactions(total);
        record.setMatchedTransactions(matched);
        record.setMismatchedTransactions(mismatched);
        record.setMissingLedgerCount(missingLedgerCount);
        record.setAmountMismatchCount(amountMismatchCount);
        record.setDetails(String.format("Total: %d, Matched: %d, Mismatched: %d", total, matched, mismatched));
        record.setTenantId(tenantId);

        ReconciliationRecord savedRecord = recordRepository.save(record);
        for (ReconciliationDetail detail : details) {
            detail.setReconciliationRecord(savedRecord);
            detailRepository.save(detail);
        }
        log.info("Reconciliation completed: status={}, total={}, mismatched={}",
                record.getStatus(), total, mismatched);
        return savedRecord;
    }

    public List<ReconciliationRecord> getRecentReports() {
        return recordRepository.findTop10ByOrderByCreatedAtDesc();
    }

    public List<ReconciliationDetail> getDetailsForRecord(Long recordId) {
        return detailRepository.findByReconciliationRecordId(recordId);
    }
}