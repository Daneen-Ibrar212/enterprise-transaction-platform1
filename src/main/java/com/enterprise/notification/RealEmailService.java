package com.enterprise.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;

@Service
@Primary
@ConditionalOnProperty(name = "app.email.mode", havingValue = "smtp")
public class RealEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(RealEmailService.class);
    private final JavaMailSender mailSender;

    @Value("${app.email.from:noreply@payflow.local}")
    private String fromAddress;

    @Value("${app.email.from-name:PayFlow}")
    private String fromName;

    public RealEmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void sendEmail(String to, String subject, String body) {
        sendHtmlEmail(to, subject, body);
    }

    public void sendHtmlEmail(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            setFrom(helper);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("✅ HTML email sent to: {}", to);
        } catch (MessagingException e) {
            log.error("❌ Failed to send email to {}: {}", to, e.getMessage());
            throw new RuntimeException("Email sending failed", e);
        }
    }

    /**
     * setFrom(String, String) throws UnsupportedEncodingException (checked).
     * If the personal-name overload fails for any reason, fall back to the
     * address-only form (which never throws).
     */
    private void setFrom(MimeMessageHelper helper) throws MessagingException {
        try {
            helper.setFrom(fromAddress, fromName);
        } catch (UnsupportedEncodingException e) {
            log.warn("Failed to set from-name '{}', falling back to address only: {}",
                    fromName, e.getMessage());
            helper.setFrom(fromAddress);
        }
    }
}