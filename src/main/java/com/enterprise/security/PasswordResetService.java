package com.enterprise.security;

import com.enterprise.audit.UserActivityLogService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.notification.EmailNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private static final int TOKEN_BYTES = 32;       // 256-bit entropy
    private static final int EXPIRY_MINUTES = 30;    // token lifetime
    private static final int MAX_REQUESTS_PER_HOUR = 3;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final EmailNotificationService emailService;
    private final UserActivityLogService activityLogService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    public PasswordResetService(UserRepository userRepository,
                                PasswordResetTokenRepository tokenRepository,
                                EmailNotificationService emailService,
                                UserActivityLogService activityLogService) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.emailService = emailService;
        this.activityLogService = activityLogService;
    }

    /**
     * Request a password reset. Always returns silently (no leak of whether
     * the email exists) — only logs and sends email if user found.
     */
    @Transactional
    public void requestReset(String email, HttpServletRequest request) {
        Optional<AppUser> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            log.info("Password reset requested for unknown email: {}", email);
            return; // silent — don't reveal
        }

        AppUser user = userOpt.get();

        // Rate limit
        LocalDateTime hourAgo = LocalDateTime.now().minusHours(1);
        long recent = tokenRepository.countByUserIdAndCreatedAtAfter(user.getId(), hourAgo);
        if (recent >= MAX_REQUESTS_PER_HOUR) {
            log.warn("Rate limit exceeded for password reset: {}", email);
            return; // silent
        }

        // Invalidate any prior unused tokens
        tokenRepository.invalidateAllForUser(user.getId());

        // Generate token
        String rawToken = generateToken();
        String tokenHash = sha256(rawToken);

        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setTokenHash(tokenHash);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(EXPIRY_MINUTES));
        token.setRequestedFromIp(clientIp(request));
        tokenRepository.save(token);

        // Send email
        String resetUrl = baseUrl + "/reset-password?token=" + rawToken;
        String subject = "Reset your PayFlow password";
        String html = buildResetEmail(user.getEmail(), resetUrl, EXPIRY_MINUTES);
        emailService.sendHtmlEmailAsync(user.getEmail(), subject, html);

        activityLogService.logActivity(
                user.getId(),
                "PASSWORD_RESET_REQUESTED",
                "Reset link emailed to " + user.getEmail(),
                request
        );

        log.info("Password reset email sent to {}", email);
    }

    /**
     * Verify a token is usable and return the user it belongs to.
     */
    public AppUser verifyToken(String rawToken) {
        if (rawToken == null || rawToken.isEmpty()) {
            throw new IllegalArgumentException("Missing token");
        }

        String hash = sha256(rawToken);
        PasswordResetToken token = tokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired reset link"));

        if (!token.isUsable()) {
            throw new IllegalArgumentException("This reset link has expired or already been used");
        }

        return userRepository.findById(token.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    /**
     * Consume the token — mark as used.
     */
    @Transactional
    public void consumeToken(String rawToken) {
        String hash = sha256(rawToken);
        tokenRepository.findByTokenHash(hash).ifPresent(t -> {
            t.setUsed(true);
            t.setUsedAt(LocalDateTime.now());
            tokenRepository.save(t);
        });
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        if (ip != null && ip.contains(",")) ip = ip.split(",")[0].trim();
        return ip;
    }

    private String buildResetEmail(String email, String resetUrl, int expiryMinutes) {
        return """
            <html>
            <body style="font-family:'Segoe UI',Arial,sans-serif;background:#f0f4f8;padding:32px;">
              <div style="max-width:560px;margin:0 auto;background:white;border-radius:16px;
                          overflow:hidden;box-shadow:0 8px 24px rgba(11,26,51,0.08);">
                <div style="background:linear-gradient(135deg,#0b1a33,#1a3a6e);padding:24px 28px;color:white;">
                  <h1 style="margin:0;font-size:1.25rem;font-weight:500;">Reset your password</h1>
                </div>
                <div style="padding:28px;">
                  <p style="font-size:0.9375rem;color:#334155;line-height:1.6;">Hi,</p>
                  <p style="font-size:0.9375rem;color:#334155;line-height:1.6;">
                    We received a request to reset the password for your PayFlow account
                    <strong>%s</strong>.
                  </p>
                  <p style="font-size:0.9375rem;color:#334155;line-height:1.6;">
                    Click the button below to choose a new password. This link expires in
                    <strong>%d minutes</strong> and can only be used once.
                  </p>
                  <a href="%s"
                     style="display:inline-block;background:linear-gradient(135deg,#0b1a33,#1a3a6e);
                            color:white;padding:12px 28px;border-radius:12px;text-decoration:none;
                            font-weight:400;margin-top:12px;">Reset Password →</a>
                  <p style="font-size:0.75rem;color:#94a3b8;margin-top:24px;">
                    If you didn't request this, you can safely ignore this email — your password
                    won't change.
                  </p>
                </div>
              </div>
            </body>
            </html>
            """.formatted(email, expiryMinutes, resetUrl);
    }
}