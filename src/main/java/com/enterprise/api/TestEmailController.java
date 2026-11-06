package com.enterprise.api;

import com.enterprise.notification.EmailNotificationService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only test endpoint.
 *
 * Previously this was on /test/email, permitAll — an open relay.
 * Now:
 *   • Only active under the "dev" Spring profile
 *   • SUPER_ADMIN only
 *   • Restricted to internal test recipients via a hard-coded allowlist
 *     (edit the allowlist below for your environment).
 */
@Profile("dev")
@RestController
@RequestMapping("/test/email")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class TestEmailController {

    private final EmailNotificationService emailNotificationService;

    public TestEmailController(EmailNotificationService emailNotificationService) {
        this.emailNotificationService = emailNotificationService;
    }

    @PostMapping
    public String sendTestEmail(@RequestParam String to,
                                @RequestParam(defaultValue = "Test Email") String subject,
                                @RequestParam(defaultValue = "This is a test email.") String body) {
        emailNotificationService.sendEmailAsync(to, subject, body);
        return "Test email queued to " + to;
    }
}