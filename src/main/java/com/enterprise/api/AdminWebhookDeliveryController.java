package com.enterprise.api;

import com.enterprise.webhook.WebhookConfig;
import com.enterprise.webhook.WebhookRepository;
import com.enterprise.webhook.WebhookDelivery;
import com.enterprise.webhook.WebhookDeliveryRepository;
import com.enterprise.webhook.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    public AdminWebhookDeliveryController(WebhookDeliveryRepository deliveryRepository,
                                          WebhookService webhookService,
                                          WebhookRepository webhookRepository) {
        this.deliveryRepository = deliveryRepository;
        this.webhookService = webhookService;
        this.webhookRepository = webhookRepository;
    }

    @GetMapping
    public String listDeliveries(
            @RequestParam(required = false) Long configId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {

        Page<WebhookDelivery> deliveries;
        if (configId != null) {
            deliveries = deliveryRepository.findByWebhookConfigIdOrderByCreatedAtDesc(
                    configId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        } else {
            deliveries = deliveryRepository.findAll(
                    PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        }

        model.addAttribute("deliveries", deliveries);
        model.addAttribute("configId", configId);
        return "admin/webhook-deliveries/list";
    }

    // ===== REPLAY endpoint =====
    @PostMapping("/{id}/replay")
    @ResponseBody
    public String replayDelivery(@PathVariable Long id,
                                 @RequestParam(required = false) String reason) {
        try {
            WebhookDelivery replay = webhookService.replayDelivery(id, reason);
            return "✅ Replay initiated (new delivery #" + replay.getId() + ")"
                    + (replay.isSuccess() ? " – SUCCESS" : " – FAILED");
        } catch (Exception e) {
            log.error("Replay failed for delivery {}", id, e);
            return "❌ Replay failed: " + e.getMessage();
        }
    }

    // ===== RETRY endpoint =====
    @PostMapping("/{id}/retry")
    @ResponseBody
    public String retryDelivery(@PathVariable Long id) {
        try {
            webhookService.retryDelivery(id);
            return "✅ Retry initiated for delivery " + id;
        } catch (Exception e) {
            return "❌ Retry failed: " + e.getMessage();
        }
    }

    // ===== TEST FAILED DELIVERY =====
    @PostMapping("/test-fail")
    @ResponseBody
    public String testFailedDelivery(@RequestParam Long configId) {
        WebhookConfig config = webhookRepository.findById(configId)
                .orElseThrow(() -> new RuntimeException("Webhook config not found"));

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setWebhookConfigId(configId);
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