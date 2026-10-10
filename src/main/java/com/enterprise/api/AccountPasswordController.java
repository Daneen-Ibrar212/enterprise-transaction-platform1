package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.security.PasswordService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/account")
public class AccountPasswordController {

    private static final Logger log = LoggerFactory.getLogger(AccountPasswordController.class);

    private final UserRepository userRepository;
    private final PasswordService passwordService;

    public AccountPasswordController(UserRepository userRepository,
                                     PasswordService passwordService) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
    }

    private AppUser currentUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ============================================================
    // CHANGE PASSWORD FORM
    // ============================================================
    @GetMapping("/password")
    public String changeForm(Model model, Authentication auth) {
        AppUser user = currentUser(auth);
        model.addAttribute("user", user);
        return "account/password";
    }

    // ============================================================
    // CHANGE PASSWORD ACTION
    // ============================================================
    @PostMapping("/password")
    public String change(@RequestParam String currentPassword,
                         @RequestParam String newPassword,
                         @RequestParam String confirmPassword,
                         Authentication auth,
                         HttpServletRequest request,
                         RedirectAttributes redirect) {
        try {
            if (!newPassword.equals(confirmPassword)) {
                throw new IllegalArgumentException("New passwords do not match");
            }

            AppUser user = currentUser(auth);
            passwordService.changePassword(user, currentPassword, newPassword, request);

            redirect.addFlashAttribute("success",
                    "✅ Password changed. You have been signed out of all other devices.");
            // Redirect to login — current session was invalidated
            return "redirect:/login?passwordChanged";
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/account/password";
        } catch (Exception e) {
            log.error("Password change failed", e);
            redirect.addFlashAttribute("error", "Failed to change password: " + e.getMessage());
            return "redirect:/account/password";
        }
    }
}