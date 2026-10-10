package com.enterprise.reconciliation;

import com.enterprise.notification.EmailNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

@Service
public class ReconciliationEmailService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationEmailService.class);

    private final ReconciliationDetailRepository detailRepository;
    private final EmailNotificationService emailService;

    public ReconciliationEmailService(ReconciliationDetailRepository detailRepository,
                                      EmailNotificationService emailService) {
        this.detailRepository = detailRepository;
        this.emailService = emailService;
    }

    public void sendSummary(ReconciliationRecord record, String adminEmail) {
        if (record == null || adminEmail == null || adminEmail.isEmpty()) return;

        long unresolved = detailRepository
                .findByReconciliationRecordIdAndRepairStatus(record.getId(), "PENDING")
                .size();

        String subject = record.getStatus().equals("PASS")
                ? "✅ Reconciliation passed — " + record.getStartedAt().toLocalDate()
                : "⚠️ Reconciliation found " + record.getMismatchedTransactions() + " discrepancies";

        String statusColor = record.getStatus().equals("PASS") ? "#10b981" : "#dc2626";

        String html = """
            <html>
            <body style="font-family:'Segoe UI',Arial,sans-serif; background:#f0f4f8; padding:32px;">
              <div style="max-width:640px; margin:0 auto; background:white; border-radius:16px;
                          overflow:hidden; box-shadow:0 8px 24px rgba(11,26,51,0.08);">
                <div style="background:%s; padding:24px 28px; color:white;">
                  <h1 style="margin:0; font-size:1.25rem; font-weight:500;">Reconciliation Summary</h1>
                  <p style="margin:6px 0 0 0; font-size:0.875rem; opacity:0.9;">
                    Run #%d · %s
                  </p>
                </div>
                <div style="padding:28px;">
                  <table style="width:100%%; border-collapse:collapse; margin-bottom:20px;">
                    <tr><td style="padding:8px 0; color:#64748b; font-size:0.8125rem;">Total transactions</td>
                        <td style="padding:8px 0; text-align:right; font-weight:500;">%d</td></tr>
                    <tr><td style="padding:8px 0; color:#64748b; font-size:0.8125rem;">Matched</td>
                        <td style="padding:8px 0; text-align:right; font-weight:500; color:#059669;">%d</td></tr>
                    <tr><td style="padding:8px 0; color:#64748b; font-size:0.8125rem;">Mismatched</td>
                        <td style="padding:8px 0; text-align:right; font-weight:500; color:%s;">%d</td></tr>
                    <tr><td style="padding:8px 0; color:#64748b; font-size:0.8125rem;">Missing ledger</td>
                        <td style="padding:8px 0; text-align:right; font-weight:500;">%d</td></tr>
                    <tr><td style="padding:8px 0; color:#64748b; font-size:0.8125rem;">Amount mismatches</td>
                        <td style="padding:8px 0; text-align:right; font-weight:500;">%d</td></tr>
                    <tr><td style="padding:8px 0; color:#64748b; font-size:0.8125rem;">Unresolved</td>
                        <td style="padding:8px 0; text-align:right; font-weight:500; color:#d97706;">%d</td></tr>
                  </table>
                  <a href="https://your-app.com/admin/reconciliation/%d"
                     style="display:inline-block; background:linear-gradient(135deg,#0b1a33,#1a3a6e);
                            color:white; padding:12px 28px; border-radius:12px;
                            text-decoration:none; font-weight:400;">View Details →</a>
                </div>
              </div>
            </body>
            </html>
            """.formatted(
                statusColor,
                record.getId(),
                record.getStartedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")),
                record.getTotalTransactions(),
                record.getMatchedTransactions(),
                statusColor,
                record.getMismatchedTransactions(),
                record.getMissingLedgerCount(),
                record.getAmountMismatchCount(),
                unresolved,
                record.getId()
        );

        emailService.sendHtmlEmailAsync(adminEmail, subject, html);
        log.info("📧 Sent reconciliation summary to {}", adminEmail);
    }
}