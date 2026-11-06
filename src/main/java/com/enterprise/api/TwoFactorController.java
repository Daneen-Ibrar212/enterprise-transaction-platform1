package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.security.BackupCodeService;
import com.enterprise.security.TOTPService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/2fa")
public class TwoFactorController {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorController.class);

    private final TOTPService totpService;
    private final UserRepository userRepository;
    private final BackupCodeService backupCodeService;

    public TwoFactorController(TOTPService totpService,
                               UserRepository userRepository,
                               BackupCodeService backupCodeService) {
        this.totpService = totpService;
        this.userRepository = userRepository;
        this.backupCodeService = backupCodeService;
    }

    private AppUser getAuthenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping("/setup")
    public String setup(Authentication authentication, Model model,
                        @RequestParam(required = false) boolean required) {
        AppUser user = getAuthenticatedUser(authentication);

        if (user.isTwoFactorEnabled()) {
            model.addAttribute("enabled", true);
            model.addAttribute("backupCodesCount", backupCodeService.getRemainingCodeCount(user.getId()));
            model.addAttribute("isAdmin", isAdmin(user));
            model.addAttribute("required", required);
            return "twofactor/status";
        }

        String secretKey = totpService.generateSecretKey();
        String otpAuthUrl = totpService.getOtpAuthUrl(secretKey, user.getEmail());

        // ✅ Secret is included in the page but never logged
        model.addAttribute("secretKey", secretKey);
        model.addAttribute("otpAuthUrl", otpAuthUrl);
        model.addAttribute("required", required);
        return "twofactor/setup";
    }

    @PostMapping("/enable")
    public String enable(@RequestParam String secretKey,
                         @RequestParam int code,
                         Authentication authentication,
                         HttpServletRequest request,
                         Model model) {
        AppUser user = getAuthenticatedUser(authentication);
        String trimmedSecret = secretKey.trim();

        // ✅ Do NOT log the secret. Log only the user + outcome.
        log.info("Enabling 2FA for user {}", user.getEmail());

        if (totpService.verifyCode(trimmedSecret, code)) {
            user.setSecretKey(trimmedSecret);
            user.setTwoFactorEnabled(true);
            userRepository.save(user);

            HttpSession session = request.getSession();
            session.removeAttribute("2FA_REQUIRED");

            List<String> backupCodes = backupCodeService.generateBackupCodes(user.getId());
            model.addAttribute("enabled", true);
            model.addAttribute("backupCodes", backupCodes);
            model.addAttribute("isAdmin", isAdmin(user));
            return "twofactor/status";
        } else {
            model.addAttribute("error", "Invalid code. Please try again.");
            model.addAttribute("secretKey", trimmedSecret);
            model.addAttribute("otpAuthUrl",
                    totpService.getOtpAuthUrl(trimmedSecret, user.getEmail()));
            model.addAttribute("required", true);
            return "twofactor/setup";
        }
    }

    @PostMapping("/disable")
    public String disable(Authentication authentication) {
        AppUser user = getAuthenticatedUser(authentication);
        // ✅ Any user with an admin role (SUPER_ADMIN, MERCHANT_ADMIN, ADMIN) cannot self-disable
        if (isAdmin(user)) {
            throw new SecurityException("Administrators cannot disable two-factor authentication.");
        }
        user.setTwoFactorEnabled(false);
        user.setSecretKey(null);
        userRepository.save(user);
        backupCodeService.regenerateCodes(user.getId());
        return "redirect:/2fa/setup?disabled";
    }

    @GetMapping("/verify")
    public String verifyForm() {
        return "twofactor/verify";
    }

    @PostMapping("/verify")
    public String verify(@RequestParam(required = false) Integer code,
                         @RequestParam(required = false) String backupCode,
                         Authentication authentication,
                         HttpSession session,
                         Model model) {
        try {
            AppUser user = getAuthenticatedUser(authentication);
            if (!user.isTwoFactorEnabled()) {
                return "redirect:/dashboard";
            }

            boolean verified = false;
            if (code != null) {
                verified = totpService.verifyCode(user.getSecretKey(), code);
            }
            if (!verified && backupCode != null && !backupCode.isEmpty()) {
                verified = backupCodeService.verifyBackupCode(user.getId(), backupCode);
            }

            if (verified) {
                session.setAttribute("2FA_AUTHENTICATED", true);
                session.removeAttribute("2FA_PENDING");
                return "redirect:/dashboard";
            }

            model.addAttribute("error", "Invalid code. Please try again.");
            return "twofactor/verify";
        } catch (Exception e) {
            log.error("2FA verification error", e);
            model.addAttribute("error", "Internal error: " + e.getMessage());
            return "twofactor/verify";
        }
    }

    @GetMapping("/backup-codes")
    public String viewBackupCodes(Authentication authentication, Model model) {
        AppUser user = getAuthenticatedUser(authentication);
        if (!user.isTwoFactorEnabled()) {
            return "redirect:/2fa/setup";
        }
        long remaining = backupCodeService.getRemainingCodeCount(user.getId());
        model.addAttribute("remainingCount", remaining);
        model.addAttribute("hasCodes", remaining > 0);
        model.addAttribute("isAdmin", isAdmin(user));
        return "twofactor/backup-codes";
    }

    @PostMapping("/backup-codes/regenerate")
    public String regenerateBackupCodes(Authentication authentication, Model model) {
        AppUser user = getAuthenticatedUser(authentication);
        if (!user.isTwoFactorEnabled()) {
            return "redirect:/2fa/setup";
        }
        List<String> newCodes = backupCodeService.generateBackupCodes(user.getId());
        model.addAttribute("backupCodes", newCodes);
        model.addAttribute("regenerated", true);
        model.addAttribute("isAdmin", isAdmin(user));
        return "twofactor/status";
    }

    private static boolean isAdmin(AppUser user) {
        return user.getRoles().stream().anyMatch(r ->
                "ADMIN".equals(r.getName())
                        || "SUPER_ADMIN".equals(r.getName())
                        || "MERCHANT_ADMIN".equals(r.getName()));
    }
}