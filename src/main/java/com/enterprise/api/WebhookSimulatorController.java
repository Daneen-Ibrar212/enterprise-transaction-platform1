package com.enterprise.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * ✅ SECURITY: previously this accepted ANY URL from the request and POSTed to it
 * via RestTemplate — a full SSRF gadget (AWS metadata, internal services, etc.).
 *
 * Fix:
 *  - Only allow http/https
 *  - Reject RFC1918 / loopback / link-local / ULA addresses
 *  - Reject localhost / *.internal / *.local DNS names
 */
@Controller
@RequestMapping("/admin/webhooks/simulator")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class WebhookSimulatorController {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WebhookSimulatorController(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public String simulatorPage(Model model) {
        Map<String, Object> sample = new HashMap<>();
        sample.put("eventType", "TRANSACTION_SETTLED");
        sample.put("timestamp", Instant.now().toString());
        sample.put("transactionId", 12345);
        sample.put("amount", "100.00");
        sample.put("currency", "GBP");
        try {
            model.addAttribute("samplePayload", objectMapper.writeValueAsString(sample));
        } catch (Exception e) {
            model.addAttribute("samplePayload", "{\"eventType\":\"TRANSACTION_SETTLED\"}");
        }
        model.addAttribute("eventTypes", new String[]{"TRANSACTION_SETTLED", "REFUND_PROCESSED"});
        return "admin/webhooks/simulator";
    }

    @PostMapping("/send")
    @ResponseBody
    public Map<String, Object> sendTestWebhook(@RequestParam String url,
                                               @RequestParam(required = false, defaultValue = "TRANSACTION_SETTLED") String eventType,
                                               @RequestParam(required = false) String payload) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!isPublicHttpUrl(url)) {
                response.put("success", false);
                response.put("error", "URL rejected: must be a public http(s) endpoint");
                return response;
            }

            Map<String, Object> payloadMap;
            if (payload != null && !payload.trim().isEmpty()) {
                payloadMap = objectMapper.readValue(payload, Map.class);
            } else {
                payloadMap = new HashMap<>();
                payloadMap.put("eventType", eventType);
                payloadMap.put("timestamp", Instant.now().toString());
                payloadMap.put("test", true);
            }
            String payloadJson = objectMapper.writeValueAsString(payloadMap);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(payloadJson, headers);

            long start = System.currentTimeMillis();
            ResponseEntity<String> httpResponse = restTemplate.postForEntity(url, request, String.class);
            long duration = System.currentTimeMillis() - start;

            response.put("success", true);
            response.put("statusCode", httpResponse.getStatusCode().value());
            response.put("body", httpResponse.getBody());
            response.put("durationMs", duration);
            response.put("sentPayload", payloadMap);
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        return response;
    }

    /**
     * SSRF guard: resolve the host and reject private/loopback/link-local ranges,
     * and reject hostnames that look like internal aliases.
     */
    private boolean isPublicHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) return false;
            String lower = host.toLowerCase();
            if (lower.equals("localhost") || lower.endsWith(".local")
                    || lower.endsWith(".internal") || lower.endsWith(".svc")) {
                return false;
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                        || addr.isMulticastAddress()) {
                    return false;
                }
                // IPv6 ULA (fc00::/7) or metadata 169.254.169.254
                byte[] raw = addr.getAddress();
                if (raw.length == 16) {
                    int first = raw[0] & 0xFF;
                    if ((first & 0xFE) == 0xFC) return false;
                }
                if (addr.isSiteLocalAddress()) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}