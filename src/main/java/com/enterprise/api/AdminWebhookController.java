package com.enterprise.api;

import com.enterprise.feature.FeatureFlagService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.tenant.TenantContext;
import com.enterprise.webhook.WebhookConfig;
import com.enterprise.webhook.WebhookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Controller
@RequestMapping("/admin/webhooks")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class AdminWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AdminWebhookController.class);

    private final WebhookRepository webhookRepository;
    private final FeatureFlagService featureFlagService;
    private final UserRepository userRepository;

    public AdminWebhookController(WebhookRepository webhookRepository,
                                  FeatureFlagService featureFlagService,
                                  UserRepository userRepository) {
        this.webhookRepository = webhookRepository;
        this.featureFlagService = featureFlagService;
        this.userRepository = userRepository;
    }

    private AppUser getCurrentAdmin(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private void assertTenantAccess(WebhookConfig webhook, AppUser admin) {
        if (admin.isSuperAdmin()) return;
        if (!Objects.equals(webhook.getTenantId(), admin.getTenantId())) {
            throw new AccessDeniedException("You cannot access webhooks from other tenants");
        }
    }

    // ============================================================
    // LIST — shows every webhook for the tenant (fan-out friendly)
    // ============================================================
    @GetMapping
    public String listWebhooks(Model model, Authentication authentication) {
        AppUser admin = getCurrentAdmin(authentication);
        boolean isSuperAdmin = admin.isSuperAdmin();
        List<WebhookConfig> webhooks;

        if (isSuperAdmin) {
            webhooks = webhookRepository.findAllWithoutTenantFilter();
            log.debug("Super Admin {} viewing {} webhooks", admin.getEmail(), webhooks.size());
        } else {
            Long tenantId = TenantContext.getRequiredTenantId();
            webhooks = webhookRepository.findAllByTenantId(tenantId);
            log.debug("Merchant Admin {} viewing {} webhooks for tenant {}",
                    admin.getEmail(), webhooks.size(), tenantId);
        }

        model.addAttribute("webhooks", webhooks);
        model.addAttribute("webhooksEnabled", featureFlagService.isEnabled("WEBHOOKS"));
        model.addAttribute("isSuperAdmin", isSuperAdmin);
        return "admin/webhooks/list";
    }

    // ============================================================
    // CREATE — allow multiple per (tenant, event), reject exact dup URL
    // ============================================================
    @GetMapping("/create")
    public String showCreateForm(Model model) {
        model.addAttribute("webhook", new WebhookConfig());
        model.addAttribute("eventTypes", List.of("TRANSACTION_SETTLED", "REFUND_PROCESSED"));
        return "admin/webhooks/create";
    }

    @PostMapping
    public String createWebhook(@ModelAttribute WebhookConfig webhook,
                                RedirectAttributes redirectAttributes) {
        try {
            Long tenantId = TenantContext.getRequiredTenantId();
            webhook.setTenantId(tenantId);

            if (webhookRepository.existsByTenantIdAndEventTypeAndUrl(
                    tenantId, webhook.getEventType(), webhook.getUrl())) {
                redirectAttributes.addFlashAttribute("error",
                        "A webhook for this event and URL already exists.");
                return "redirect:/admin/webhooks";
            }

            webhook.setCreatedAt(LocalDateTime.now());
            webhook.setUpdatedAt(LocalDateTime.now());

            log.info("📝 Creating webhook for tenant {}: name={}, url={}, event={}, active={}",
                    tenantId, webhook.getName(), webhook.getUrl(),
                    webhook.getEventType(), webhook.isActive());

            webhookRepository.save(webhook);
            redirectAttributes.addFlashAttribute("success", "Webhook created.");
        } catch (Exception e) {
            log.error("Failed to create webhook", e);
            redirectAttributes.addFlashAttribute("error",
                    "Failed to create webhook: " + e.getMessage());
        }
        return "redirect:/admin/webhooks";
    }

    // ============================================================
    // EDIT
    // ============================================================
    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable Long id,
                               Model model,
                               Authentication authentication) {
        AppUser admin = getCurrentAdmin(authentication);
        WebhookConfig webhook = webhookRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Webhook not found"));
        assertTenantAccess(webhook, admin);

        model.addAttribute("webhook", webhook);
        model.addAttribute("eventTypes", List.of("TRANSACTION_SETTLED", "REFUND_PROCESSED"));
        return "admin/webhooks/edit";
    }

    @PostMapping("/{id}")
    public String updateWebhook(@PathVariable Long id,
                                @ModelAttribute WebhookConfig webhook,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            WebhookConfig existing = webhookRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Webhook not found"));
            assertTenantAccess(existing, admin);

            // Guard against editing into a duplicate (same tenant+event+url as another row)
            if (webhookRepository.existsByTenantIdAndEventTypeAndUrlAndIdNot(
                    existing.getTenantId(), webhook.getEventType(), webhook.getUrl(), id)) {
                redirectAttributes.addFlashAttribute("error",
                        "Another webhook for this event and URL already exists.");
                return "redirect:/admin/webhooks";
            }

            existing.setName(webhook.getName());
            existing.setUrl(webhook.getUrl());
            existing.setEventType(webhook.getEventType());
            existing.setActive(webhook.isActive());
            existing.setUpdatedAt(LocalDateTime.now());

            webhookRepository.save(existing);
            redirectAttributes.addFlashAttribute("success", "Webhook updated.");
        } catch (Exception e) {
            log.error("Failed to update webhook {}", id, e);
            redirectAttributes.addFlashAttribute("error",
                    "Failed to update webhook: " + e.getMessage());
        }
        return "redirect:/admin/webhooks";
    }

    // ============================================================
    // DELETE (POST only — fixes 405)
    // ============================================================
    @PostMapping("/{id}/delete")
    public String deleteWebhook(@PathVariable Long id,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            WebhookConfig webhook = webhookRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Webhook not found"));
            assertTenantAccess(webhook, admin);

            webhookRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Webhook deleted.");
            log.info("🗑️ Webhook {} deleted by {}", id, admin.getEmail());
        } catch (Exception e) {
            log.error("Failed to delete webhook {}", id, e);
            redirectAttributes.addFlashAttribute("error",
                    "Failed to delete webhook: " + e.getMessage());
        }
        return "redirect:/admin/webhooks";
    }

    // ============================================================
    // TOGGLE (POST only — fixes 405)
    // ============================================================
    @PostMapping("/{id}/toggle")
    public String toggleWebhook(@PathVariable Long id,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            WebhookConfig webhook = webhookRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Webhook not found"));
            assertTenantAccess(webhook, admin);

            webhook.setActive(!webhook.isActive());
            webhook.setUpdatedAt(LocalDateTime.now());
            webhookRepository.save(webhook);

            redirectAttributes.addFlashAttribute("success",
                    webhook.isActive() ? "Webhook activated." : "Webhook deactivated.");
        } catch (Exception e) {
            log.error("Failed to toggle webhook {}", id, e);
            redirectAttributes.addFlashAttribute("error",
                    "Failed to toggle webhook: " + e.getMessage());
        }
        return "redirect:/admin/webhooks";
    }
}