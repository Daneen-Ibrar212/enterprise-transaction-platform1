package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.signup.SignupRequest;
import com.enterprise.signup.SignupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/admin/signups")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminSignupController {

    private static final Logger log = LoggerFactory.getLogger(AdminSignupController.class);

    private final SignupService signupService;
    private final UserRepository userRepository;

    public AdminSignupController(SignupService signupService,
                                 UserRepository userRepository) {
        this.signupService = signupService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String list(@RequestParam(required = false, defaultValue = "pending") String filter,
                       Model model) {
        List<SignupRequest> signups;
        if ("all".equalsIgnoreCase(filter)) {
            signups = signupService.listAll();
        } else {
            signups = signupService.listPending();
        }
        model.addAttribute("signups", signups);
        model.addAttribute("filter", filter);
        return "admin/signups/list";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id,
                          @RequestParam(required = false) String notes,
                          Authentication authentication,
                          RedirectAttributes redirect) {
        try {
            AppUser admin = userRepository.findByEmail(authentication.getName())
                    .orElseThrow(() -> new IllegalStateException("Admin not found"));
            signupService.approveSignup(id, admin.getId(), notes);
            redirect.addFlashAttribute("success",
                    "Signup #" + id + " approved. User can now pay for their subscription.");
        } catch (Exception e) {
            log.error("Approve failed for signup {}", id, e);
            redirect.addFlashAttribute("error", "Failed to approve: " + e.getMessage());
        }
        return "redirect:/admin/signups";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id,
                         @RequestParam String reason,
                         Authentication authentication,
                         RedirectAttributes redirect) {
        try {
            AppUser admin = userRepository.findByEmail(authentication.getName())
                    .orElseThrow(() -> new IllegalStateException("Admin not found"));
            signupService.rejectSignup(id, admin.getId(), reason);
            redirect.addFlashAttribute("success", "Signup #" + id + " rejected.");
        } catch (Exception e) {
            log.error("Reject failed for signup {}", id, e);
            redirect.addFlashAttribute("error", "Failed to reject: " + e.getMessage());
        }
        return "redirect:/admin/signups";
    }
}