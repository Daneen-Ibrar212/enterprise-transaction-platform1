package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.tenant.TenantContext;
import com.enterprise.webhook.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/webhook-deliveries")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class AdminWebhookDeliveryController {

    private static final Logger log = LoggerFactory.getLogger(AdminWebhookDeliveryController.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookService webhookService;
    private final WebhookRepository webhookRepository;
    private final UserRepository userRepository;

    public AdminWebhookDeliveryController(WebhookDeliveryRepository deliveryRepository,
                                          WebhookService webhookService,
                                          WebhookRepository webhookRepository,
                                          UserRepository userRepository) {
        this.deliveryRepository = deliveryRepository;
        this.webhookService = webhookService;
        this.webhookRepository = webhookRepository;
        this.userRepository = userRepository;
    }

    private AppUser currentUser() {
        return userRepository.findByEmail(
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication().getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /** Throws unless the caller can see this delivery. */
    private WebhookDelivery loadOwnedDelivery(Long id) {
        WebhookDelivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Delivery not found"));
        AppUser me = currentUser();
        if (!me.isSuperAdmin() && !delivery.getTenantId().equals(me.getTenantId())) {
            throw new AccessDeniedException("You cannot access deliveries from other tenants");
        }
        return delivery;
    }

    @GetMapping
    public String listDeliveries(@RequestParam(required = false) Long configId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size,
                                 Model model) {
        AppUser me = currentUser();
        Long tenantId = me.getTenantId();
        boolean isSuperAdmin = me.isSuperAdmin();

        Page<WebhookDelivery> deliveries;
        if (configId != null) {
            if (!isSuperAdmin) {
                WebhookConfig cfg = webhookRepository.findById(configId)
                        .orElseThrow(() -> new RuntimeException("Config not found"));
                if (!cfg.getTenantId().equals(tenantId)) {
                    throw new AccessDeniedException("Config belongs to another tenant");
                }
            }
            deliveries = deliveryRepository.findByWebhookConfigIdOrderByCreatedAtDesc(
                    configId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        } else if (isSuperAdmin) {
            deliveries = deliveryRepository.findAll(
                    PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        } else {
            deliveries = deliveryRepository.findByTenantIdOrderByCreatedAtDesc(
                    tenantId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        }

        model.addAttribute("deliveries", deliveries);
        model.addAttribute("configId", configId);
        return "admin/webhook-deliveries/list";
    }

    @PostMapping("/{id}/replay")
    @ResponseBody
    public String replayDelivery(@PathVariable Long id,
                                 @RequestParam(required = false) String reason) {
        try {
            loadOwnedDelivery(id);  // ✅ ownership check
            WebhookDelivery replay = webhookService.replayDelivery(id, reason);
            return "✅ Replay initiated (new delivery #" + replay.getId() + ")"
                    + (replay.isSuccess() ? " – SUCCESS" : " – FAILED");
        } catch (Exception e) {
            log.error("Replay failed for delivery {}", id, e);
            return "❌ Replay failed: " + e.getMessage();
        }
    }

    @PostMapping("/{id}/retry")
    @ResponseBody
    public String retryDelivery(@PathVariable Long id) {
        try {
            loadOwnedDelivery(id);  // ✅ ownership check
            webhookService.retryDelivery(id);
            return "✅ Retry initiated for delivery " + id;
        } catch (Exception e) {
            return "❌ Retry failed: " + e.getMessage();
        }
    }

    @PostMapping("/test-fail")
    @ResponseBody
    public String testFailedDelivery(@RequestParam Long configId) {
        WebhookConfig config = webhookRepository.findById(configId)
                .orElseThrow(() -> new RuntimeException("Webhook config not found"));
        AppUser me = currentUser();
        if (!me.isSuperAdmin() && !config.getTenantId().equals(me.getTenantId())) {
            throw new AccessDeniedException("Config belongs to another tenant");
        }

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setWebhookConfigId(configId);
        delivery.setTenantId(config.getTenantId());  // ✅
        delivery.setEventType(config.getEventType());
        delivery.setUrl(config.getUrl());
        delivery.setPayload("{\"test\":\"data\"}");
        delivery.setSuccess(false);
        delivery.setStatusCode(500);
        delivery.setErrorMessage("Simulated failure for testing");
        delivery.setAttemptNumber(1);
        deliveryRepository.save(delivery);
        return "Failed delivery created with ID: " + delivery.getId();
    }
}