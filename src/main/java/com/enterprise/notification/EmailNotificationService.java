package com.enterprise.notification;

import com.enterprise.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final EmailService emailService;
    private final EmailLogRepository emailLogRepository;

    public EmailNotificationService(EmailService emailService,
                                    EmailLogRepository emailLogRepository) {
        this.emailService = emailService;
        this.emailLogRepository = emailLogRepository;
    }

    /**
     * ✅ Uses the dedicated notification pool — previously every email ran on
     *    the shared `taskExecutor`, so a flood of webhook retries could
     *    starve login alerts.
     */
    @Async("notificationExecutor")
    @Transactional
    public void sendEmailAsync(String to, String subject, String body) {
        sendHtmlEmailAsync(to, subject, body);
    }

    @Async("notificationExecutor")
    @Transactional
    public void sendHtmlEmailAsync(String to, String subject, String htmlBody) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) tenantId = 1L;  // fallback for async-context calls

        EmailLog logEntry = new EmailLog(to, subject, htmlBody);
        logEntry.setTenantId(tenantId);
        emailLogRepository.save(logEntry);

        try {
            emailService.sendEmail(to, subject, htmlBody);
            logEntry.setStatus("SENT");
            logEntry.setSentAt(LocalDateTime.now());
        } catch (Exception e) {
            logEntry.setStatus("FAILED");
            logEntry.setErrorMessage(e.getMessage());
            log.error("❌ Email failed for {}: {}", to, e.getMessage());
        }
        emailLogRepository.save(logEntry);
    }

    @Transactional
    public void resendEmail(Long logId) {
        EmailLog logEntry = emailLogRepository.findById(logId)
                .orElseThrow(() -> new RuntimeException("Email log not found"));
        sendHtmlEmailAsync(logEntry.getRecipient(), logEntry.getSubject(), logEntry.getBody());
    }
}