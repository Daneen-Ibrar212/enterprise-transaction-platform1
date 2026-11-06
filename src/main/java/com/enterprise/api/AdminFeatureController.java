package com.enterprise.api;

import com.enterprise.feature.FeatureFlag;
import com.enterprise.feature.FeatureFlagService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/admin/features")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class AdminFeatureController {

    private final FeatureFlagService featureFlagService;
    private final UserRepository userRepository;

    public AdminFeatureController(FeatureFlagService featureFlagService,
                                  UserRepository userRepository) {
        this.featureFlagService = featureFlagService;
        this.userRepository = userRepository;
    }

    private AppUser getCurrentAdmin(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping
    public String listFeatures(Model model) {
        model.addAttribute("flags", featureFlagService.findAll());
        return "admin/features/list";
    }

    /**
     * ✅ POST only — previously allowed GET, which combined with disabled
     *    CSRF on /admin/** made it CSRF-triggerable via <a href>.
     */
    @PostMapping("/{id}/toggle")
    public String toggleFeature(@PathVariable Long id,
                                RedirectAttributes redirectAttributes) {
        try {
            FeatureFlag flag = featureFlagService.findAll().stream()
                    .filter(f -> f.getId().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Feature not found"));
            featureFlagService.setEnabled(flag.getName(), !flag.isEnabled());
            redirectAttributes.addFlashAttribute("success", "Feature toggled.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/admin/features";
    }

    @GetMapping("/create")
    public String showCreateForm(Model model) {
        model.addAttribute("flag", new FeatureFlag());
        return "admin/features/create";
    }

    @PostMapping
    public String createFeature(@RequestParam String name,
                                @RequestParam String description,
                                @RequestParam(required = false) Boolean enabled,
                                RedirectAttributes redirectAttributes) {
        try {
            featureFlagService.createFlag(name, description, enabled != null && enabled);
            redirectAttributes.addFlashAttribute("success", "Feature created.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/admin/features";
    }

    @PostMapping("/{id}/delete")
    public String deleteFeature(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            featureFlagService.deleteFlag(id);
            redirectAttributes.addFlashAttribute("success", "Feature deleted.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/admin/features";
    }
}