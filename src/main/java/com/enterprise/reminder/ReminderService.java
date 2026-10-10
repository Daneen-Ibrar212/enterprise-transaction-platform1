package com.enterprise.reminder;

import com.enterprise.invoice.Invoice;
import com.enterprise.invoice.InvoiceRepository;
import com.enterprise.notification.EmailNotificationService;
import com.enterprise.notification.NotificationService;
import com.enterprise.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ReminderScheduleRepository reminderRepository;
    private final InvoiceRepository invoiceRepository;
    private final EmailNotificationService emailService;
    private final NotificationService notificationService;

    public ReminderService(ReminderScheduleRepository reminderRepository,
                           InvoiceRepository invoiceRepository,
                           EmailNotificationService emailService,
                           NotificationService notificationService) {
        this.reminderRepository = reminderRepository;
        this.invoiceRepository = invoiceRepository;
        this.emailService = emailService;
        this.notificationService = notificationService;
    }

    /**
     * Scan unpaid APPROVED invoices and create reminder schedule entries.
     * Called daily by the scheduler.
     */
    @Transactional
    public int scheduleReminders(int firstDays, int secondDays, int finalDays) {
        Long tenantId = TenantContext.getRequiredTenantId();
        LocalDate today = LocalDate.now();

        // Get all approved (unpaid) invoices
        List<Invoice> unpaid = invoiceRepository.findByStatus("APPROVED");

        int created = 0;
        for (Invoice invoice : unpaid) {
            long daysOutstanding = ChronoUnit.DAYS.between(
                    invoice.getCreatedAt().toLocalDate(), today
            );

            // Decide which reminder this invoice is due for
            ReminderType type = null;
            int threshold = 0;

            if (daysOutstanding >= finalDays) {
                type = ReminderType.FINAL;
                threshold = finalDays;
            } else if (daysOutstanding >= secondDays) {
                type = ReminderType.SECOND;
                threshold = secondDays;
            } else if (daysOutstanding >= firstDays) {
                type = ReminderType.FIRST;
                threshold = firstDays;
            }

            if (type == null) continue;

            // Skip if we've already scheduled this reminder type for this invoice
            if (reminderRepository.findByInvoiceIdAndReminderType(invoice.getId(), type).isPresent()) {
                continue;
            }

            ReminderSchedule reminder = new ReminderSchedule();
            reminder.setInvoiceId(invoice.getId());
            reminder.setTenantId(tenantId);
            reminder.setReminderType(type);
            reminder.setDaysOverdue(threshold);
            reminder.setScheduledFor(today);
            reminder.setStatus("PENDING");
            reminderRepository.save(reminder);
            created++;
        }

        if (created > 0) {
            log.info("✅ Scheduled {} reminders for tenant {}", created, tenantId);
        }
        return created;
    }

    /**
     * Send all PENDING reminders due today (or earlier).
     */
    @Transactional
    public int sendDueReminders() {
        LocalDate today = LocalDate.now();
        List<ReminderSchedule> due = reminderRepository.findDueForSending(today);

        int sent = 0;
        for (ReminderSchedule reminder : due) {
            try {
                sendReminder(reminder);
                reminder.setStatus("SENT");
                reminder.setSentAt(LocalDateTime.now());
                sent++;
            } catch (Exception e) {
                log.error("Failed to send reminder {}", reminder.getId(), e);
                reminder.setStatus("FAILED");
                reminder.setErrorMessage(e.getMessage());
            }
            reminderRepository.save(reminder);
        }
        return sent;
    }

    private void sendReminder(ReminderSchedule reminder) {
        Invoice invoice = invoiceRepository.findById(reminder.getInvoiceId())
                .orElseThrow(() -> new RuntimeException("Invoice not found"));

        // If invoice got paid in the meantime, skip
        if (!"APPROVED".equals(invoice.getStatus())) {
            reminder.setStatus("SKIPPED");
            reminder.setErrorMessage("Invoice already " + invoice.getStatus());
            return;
        }

        String subject = switch (reminder.getReminderType()) {
            case FIRST -> "Payment Reminder — Invoice #" + invoice.getId();
            case SECOND -> "Second Reminder — Invoice #" + invoice.getId();
            case FINAL -> "Final Reminder — Invoice #" + invoice.getId();
        };

        String htmlBody = buildReminderEmail(invoice, reminder.getReminderType(), reminder.getDaysOverdue());

        // Send email
        emailService.sendHtmlEmailAsync(invoice.getCustomerEmail(), subject, htmlBody);

        // In-app notification for the merchant
        notificationService.createNotification(
                invoice.getMerchantId(),
                "REMINDER_SENT",
                reminder.getReminderType().getDisplayName() + " Sent",
                "Reminder sent to " + invoice.getCustomerEmail() + " for invoice #" + invoice.getId(),
                "/invoices/" + invoice.getId()
        );

        log.info("📧 Sent {} for invoice {} to {}",
                reminder.getReminderType(), invoice.getId(), invoice.getCustomerEmail());
    }

    private String buildReminderEmail(Invoice invoice, ReminderType type, int daysOverdue) {
        String urgency = switch (type) {
            case FIRST -> "friendly";
            case SECOND -> "firm";
            case FINAL -> "urgent";
        };

        String headerColor = switch (type) {
            case FIRST -> "#2d6bb8";
            case SECOND -> "#d97706";
            case FINAL -> "#dc2626";
        };

        return """
            <html>
            <body style="font-family: 'Segoe UI', Arial, sans-serif; background:#f0f4f8; padding:32px;">
                <div style="max-width:560px; margin:0 auto; background:white; border-radius:16px; overflow:hidden; box-shadow:0 8px 24px rgba(11,26,51,0.08);">
                    <div style="background:%s; padding:24px 28px; color:white;">
                        <h1 style="margin:0; font-size:1.25rem; font-weight:500;">%s</h1>
                    </div>
                    <div style="padding:28px;">
                        <p style="font-size:0.9375rem; color:#334155; line-height:1.6;">Hello,</p>
                        <p style="font-size:0.9375rem; color:#334155; line-height:1.6;">
                            This is a <strong>%s</strong> about an unpaid invoice.
                        </p>
                        <table style="width:100%%; margin:20px 0; border-collapse:collapse;">
                            <tr><td style="padding:8px 0; color:#64748b; font-size:0.8125rem;">Invoice</td>
                                <td style="padding:8px 0; text-align:right; font-weight:500;">#%d</td></tr>
                            <tr><td style="padding:8px 0; color:#64748b; font-size:0.8125rem;">Amount</td>
                                <td style="padding:8px 0; text-align:right; font-weight:500;">%s</td></tr>
                            <tr><td style="padding:8px 0; color:#64748b; font-size:0.8125rem;">Days overdue</td>
                                <td style="padding:8px 0; text-align:right; font-weight:500; color:%s;">%d days</td></tr>
                        </table>
                        <a href="%s" style="display:inline-block; background:linear-gradient(135deg,#0b1a33,#1a3a6e); color:white; padding:12px 28px; border-radius:12px; text-decoration:none; font-weight:400; margin-top:8px;">Pay Now →</a>
                        <p style="font-size:0.75rem; color:#94a3b8; margin-top:24px;">If you have already paid, please ignore this message.</p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(
                headerColor,
                type.getDisplayName(),
                urgency,
                invoice.getId(),
                invoice.getAmount().toPlainString() + " " + invoice.getCurrency(),
                headerColor,
                daysOverdue,
                "https://your-app.com/pay/" + invoice.getId()  // TODO: make configurable
        );
    }

    public List<ReminderSchedule> listForCurrentTenant() {
        Long tenantId = TenantContext.getRequiredTenantId();
        return reminderRepository.findAllByTenantId(tenantId);
    }

    public List<ReminderSchedule> listPendingForCurrentTenant() {
        Long tenantId = TenantContext.getRequiredTenantId();
        return reminderRepository.findByTenantIdAndStatus(tenantId, "PENDING");
    }
}