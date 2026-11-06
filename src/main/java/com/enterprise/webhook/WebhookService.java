package com.enterprise.webhook;

import com.enterprise.events.RefundProcessedEvent;
import com.enterprise.events.TransactionSettledEvent;
import com.enterprise.feature.FeatureFlagService;
import com.enterprise.reliability.DlqProcessorService;
import com.enterprise.reliability.RecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookRepository webhookRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final FeatureFlagService featureFlagService;
    private final RecoveryService recoveryService;
    private final DlqProcessorService dlqProcessorService;

    @Value("${app.webhook.hmac-secret:default-secret-change-me}")
    private String hmacSecret;

    public WebhookService(WebhookRepository webhookRepository,
                          WebhookDeliveryRepository deliveryRepository,
                          RestTemplate restTemplate,
                          ObjectMapper objectMapper,
                          FeatureFlagService featureFlagService,
                          RecoveryService recoveryService,
                          DlqProcessorService dlqProcessorService) {
        this.webhookRepository = webhookRepository;
        this.deliveryRepository = deliveryRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.featureFlagService = featureFlagService;
        this.recoveryService = recoveryService;
        this.dlqProcessorService = dlqProcessorService;
    }

    @PostConstruct
    public void registerDlqHandler() {
        dlqProcessorService.registerOperation(
            "WebhookService.sendWebhook",
            (context) -> {
                Long configId = (Long) context.get("configId");
                Map<String, Object> payload = (Map<String, Object>) context.get("payload");
                WebhookConfig config = webhookRepository.findById(configId)
                        .orElseThrow(() -> new RuntimeException("Webhook config not found"));
                sendWebhook(config, payload);
                return "OK";
            }
        );
    }

    // ✅ Explicit webhook executor — previously fell through to the shared
    //    `taskExecutor`, letting slow webhook deliveries starve notifications.
    @Async("webhookExecutor")
    @EventListener
    public void onTransactionSettled(TransactionSettledEvent event) {
        sendWebhooks("TRANSACTION_SETTLED", event.getTransaction());
    }

    @Async("webhookExecutor")
    @EventListener
    public void onRefundProcessed(RefundProcessedEvent event) {
        sendWebhooks("REFUND_PROCESSED", event.getRefundTransaction(), event.getOriginalTransaction());
    }

    public void sendWebhooks(String eventType, Object... payloadObjects) {
        if (!featureFlagService.isEnabled("WEBHOOKS")) {
            log.info("Webhooks disabled – skipping event: {}", eventType);
            return;
        }
        List<WebhookConfig> configs = webhookRepository.findByEventTypeAndActiveTrue(eventType);
        if (configs.isEmpty()) {
            return;
        }
        Map<String, Object> payload = buildPayload(eventType, payloadObjects);

        for (WebhookConfig config : configs) {
            Map<String, Object> context = new HashMap<>();
            context.put("configId", config.getId());
            context.put("payload", payload);
            try {
                recoveryService.executeWithRetry(
                    "WebhookService.sendWebhook",
                    () -> { sendWebhook(config, payload); return null; },
                    context
                );
            } catch (Exception e) {
                log.error("Webhook delivery to {} failed after retries: {}",
                        config.getUrl(), e.getMessage());
            }
        }
    }

    @Transactional
    public void sendWebhook(WebhookConfig config, Map<String, Object> payload) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setWebhookConfigId(config.getId());
        delivery.setTenantId(config.getTenantId());
        delivery.setEventType(config.getEventType());
        delivery.setUrl(config.getUrl());
        delivery.setAttemptNumber(1);

        long startTime = System.currentTimeMillis();
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            delivery.setPayload(payloadJson);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Webhook-Signature", computeHmac(payloadJson));

            HttpEntity<String> request = new HttpEntity<>(payloadJson, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    config.getUrl(), request, String.class);

            delivery.setStatusCode(response.getStatusCode().value());
            delivery.setResponseBody(response.getBody());
            delivery.setSuccess(response.getStatusCode().is2xxSuccessful());

            if (!delivery.isSuccess()) {
                delivery.setErrorMessage("HTTP " + response.getStatusCode());
                throw new RuntimeException("Webhook returned non-2xx: " + response.getStatusCode());
            }
        } catch (Exception e) {
            delivery.setSuccess(false);
            delivery.setErrorMessage(e.getMessage());
            log.error("Webhook delivery to {} failed: {}", config.getUrl(), e.getMessage(), e);
            throw new RuntimeException("Webhook delivery failed", e);
        } finally {
            delivery.setResponseTimeMs(System.currentTimeMillis() - startTime);
            deliveryRepository.save(delivery);
        }
    }

    private Map<String, Object> buildPayload(String eventType, Object[] payloadObjects) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", eventType);
        payload.put("timestamp", java.time.Instant.now().toString());
        for (Object obj : payloadObjects) {
            try {
                payload.put(obj.getClass().getSimpleName().toLowerCase(),
                        objectMapper.convertValue(obj, Map.class));
            } catch (Exception e) {
                log.warn("Could not convert payload to map", e);
            }
        }
        return payload;
    }

    private String computeHmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec spec = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(spec);
            return bytesToHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Failed to compute HMAC", e);
            return "";
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Transactional
    public WebhookDelivery retryDelivery(Long deliveryId) {
        WebhookDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new RuntimeException("Delivery not found: " + deliveryId));
        WebhookConfig config = webhookRepository.findById(delivery.getWebhookConfigId())
                .orElseThrow(() -> new RuntimeException("Webhook config not found"));

        try {
            Map<String, Object> payload = objectMapper.readValue(delivery.getPayload(), Map.class);
            sendWebhook(config, payload);
            List<WebhookDelivery> deliveries = deliveryRepository
                    .findByWebhookConfigIdOrderByCreatedAtDesc(config.getId());
            return deliveries.isEmpty() ? delivery : deliveries.get(0);
        } catch (Exception e) {
            log.error("Retry failed for delivery {}", deliveryId, e);
            WebhookDelivery failed = new WebhookDelivery();
            failed.setWebhookConfigId(config.getId());
            failed.setTenantId(config.getTenantId());
            failed.setEventType(delivery.getEventType());
            failed.setUrl(config.getUrl());
            failed.setPayload(delivery.getPayload());
            failed.setSuccess(false);
            failed.setErrorMessage("Retry failed: " + e.getMessage());
            failed.setAttemptNumber(delivery.getAttemptNumber() + 1);
            return deliveryRepository.save(failed);
        }
    }

    @Transactional
    public WebhookDelivery replayDelivery(Long deliveryId, String reason) {
        WebhookDelivery original = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new RuntimeException("Delivery not found: " + deliveryId));
        WebhookConfig config = webhookRepository.findById(original.getWebhookConfigId())
                .orElseThrow(() -> new RuntimeException("Webhook config not found"));
        if (original.getPayload() == null || original.getPayload().isEmpty()) {
            throw new RuntimeException("Original delivery has no payload to replay");
        }

        WebhookDelivery replay = new WebhookDelivery();
        replay.setWebhookConfigId(config.getId());
        replay.setTenantId(config.getTenantId());
        replay.setEventType(original.getEventType());
        replay.setUrl(config.getUrl());
        replay.setPayload(original.getPayload());
        replay.setAttemptNumber(1);
        replay.setReplay(true);
        replay.setReplayOfId(deliveryId);
        replay.setReplayReason(reason);

        long startTime = System.currentTimeMillis();
        try {
            String payloadJson = original.getPayload();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Webhook-Signature", computeHmac(payloadJson));
            headers.set("X-Webhook-Replay", "true");
            headers.set("X-Webhook-Replay-Of", String.valueOf(deliveryId));

            HttpEntity<String> request = new HttpEntity<>(payloadJson, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    config.getUrl(), request, String.class);

            replay.setStatusCode(response.getStatusCode().value());
            replay.setResponseBody(response.getBody());
            replay.setSuccess(response.getStatusCode().is2xxSuccessful());
            replay.setResponseTimeMs(System.currentTimeMillis() - startTime);
            if (!replay.isSuccess()) {
                replay.setErrorMessage("HTTP " + response.getStatusCode());
            }
        } catch (Exception e) {
            replay.setSuccess(false);
            replay.setErrorMessage(e.getMessage());
            replay.setResponseTimeMs(System.currentTimeMillis() - startTime);
            log.error("Replay {} failed: {}", deliveryId, e.getMessage());
        }
        return deliveryRepository.save(replay);
    }
}