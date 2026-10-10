package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.security.PasswordResetService;
import com.enterprise.security.PasswordService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PasswordResetController {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetController.class);

    private final PasswordResetService resetService;
    private final PasswordService passwordService;

    public PasswordResetController(PasswordResetService resetService,
                                   PasswordService passwordService) {
        this.resetService = resetService;
        this.passwordService = passwordService;
    }

    // ============================================================
    // STEP 1 — Request form
    // ============================================================
    @GetMapping("/forgot-password")
    public String forgotForm() {
        return "account/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String forgotSubmit(@RequestParam String email,
                               HttpServletRequest request,
                               RedirectAttributes redirect) {
        try {
            resetService.requestReset(email, request);
        } catch (Exception e) {
            log.error("Forgot-password error", e);
        }
        // Always show success — don't leak whether email exists
        return "redirect:/forgot-password?sent";
    }

    // ============================================================
    // STEP 2 — Reset form (from emailed link)
    // ============================================================
    @GetMapping("/reset-password")
    public String resetForm(@RequestParam(required = false) String token, Model model) {
        if (token == null || token.isEmpty()) {
            model.addAttribute("error", "Missing or invalid reset link.");
            return "account/reset-password";
        }

        try {
            AppUser user = resetService.verifyToken(token);
            model.addAttribute("token", token);
            model.addAttribute("email", user.getEmail());
            return "account/reset-password";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "account/reset-password";
        }
    }

    @PostMapping("/reset-password")
    public String resetSubmit(@RequestParam String token,
                              @RequestParam String newPassword,
                              @RequestParam String confirmPassword,
                              HttpServletRequest request,
                              RedirectAttributes redirect) {
        try {
            if (!newPassword.equals(confirmPassword)) {
                throw new IllegalArgumentException("Passwords do not match");
            }

            AppUser user = resetService.verifyToken(token);
            passwordService.resetPassword(user, newPassword, request);
            resetService.consumeToken(token);

            redirect.addFlashAttribute("success",
                    "✅ Password reset. Please sign in with your new password.");
            return "redirect:/login";
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/reset-password?token=" + token;
        } catch (Exception e) {
            log.error("Reset failed", e);
            redirect.addFlashAttribute("error", "Reset failed: " + e.getMessage());
            return "redirect:/reset-password?token=" + token;
        }
    }
}