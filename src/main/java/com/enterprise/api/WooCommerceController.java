package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.Role;
import com.enterprise.identity.RoleRepository;
import com.enterprise.identity.UserRepository;
import com.enterprise.invoice.Invoice;
import com.enterprise.invoice.InvoiceService;
import com.enterprise.tenant.TenantContext;
import com.enterprise.transaction.PaymentRequest;
import com.enterprise.transaction.PaymentResponse;
import com.enterprise.transaction.TransactionService;
import com.enterprise.webhook.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * WooCommerce integration endpoint.
 *
 * Security model (was: no auth — critical bug):
 *   • Requires X-API-Key header (enforced by ApiKeyAuthenticationFilter).
 *   • Tenant context is set from the API-key user's tenant, NOT from the
 *     request body. A caller can only create invoices for their own tenant.
 *   • merchantId in the request is validated to belong to the caller's tenant.
 *
 * Idempotency:
 *   • /payment derives its idempotency key from (orderId, orderKey), so a
 *     retried request returns the original response instead of creating a
 *     second invoice/transaction.
 *   • /create-invoice is NOT idempotent — the same orderId will create a new
 *     invoice on each call. If that matters, add an order-id dedupe on the
 *     invoice side.
 */
@RestController
@RequestMapping("/api/woocommerce")
public class WooCommerceController {

    private static final Logger log = LoggerFactory.getLogger(WooCommerceController.class);

    private final TransactionService transactionService;
    private final InvoiceService invoiceService;
    private final WebhookService webhookService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public WooCommerceController(TransactionService transactionService,
                                 InvoiceService invoiceService,
                                 WebhookService webhookService,
                                 UserRepository userRepository,
                                 RoleRepository roleRepository,
                                 PasswordEncoder passwordEncoder) {
        this.transactionService = transactionService;
        this.invoiceService = invoiceService;
        this.webhookService = webhookService;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ============================================================
    // CREATE INVOICE (no payment)
    // ============================================================
    @PostMapping("/create-invoice")
    public ResponseEntity<Map<String, Object>> createInvoice(@RequestBody WooCommerceOrder order) {
        // ✅ Tenant comes from API key context — set by ApiKeyAuthenticationFilter.
        //    Never trust order.getTenantId() from the request body.
        Long tenantId = TenantContext.getRequiredTenantId();
        log.info("🔵 WC create-invoice for tenant: {}", tenantId);

        try {
            if (order.getMerchantId() == null) {
                throw new IllegalArgumentException("merchantId is required");
            }

            // ✅ Assert merchant belongs to the caller's tenant
            AppUser merchant = userRepository.findById(order.getMerchantId())
                    .orElseThrow(() -> new IllegalArgumentException("Merchant not found"));
            if (!tenantId.equals(merchant.getTenantId())) {
                throw new IllegalArgumentException("Merchant is not in your tenant");
            }

            AppUser customer = getOrCreateCustomer(order, tenantId);

            Invoice invoice = invoiceService.createInvoice(
                    order.getTotal(),
                    "WooCommerce Order #" + order.getOrderId(),
                    order.getBillingEmail(),
                    order.getMerchantId(),
                    false,
                    order.getCurrency(),
                    order.getOrderId(),
                    order.getWebhookUrl(),
                    order.getReturnUrl(),
                    order.getOrderKey()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("invoiceId", invoice.getId());
            response.put("status", invoice.getStatus());
            response.put("customerId", customer.getId());
            response.put("customerEmail", customer.getEmail());
            return ResponseEntity.ok(response);
        } finally {
            TenantContext.clear();
        }
    }

    // ============================================================
    // CREATE INVOICE + PROCESS PAYMENT (idempotent on orderId+orderKey)
    // ============================================================
    @PostMapping("/payment")
    public ResponseEntity<WooCommerceResponse> processWooOrder(@RequestBody WooCommerceOrder order) {
        Long tenantId = TenantContext.getRequiredTenantId();

        try {
            if (order.getMerchantId() == null) {
                throw new IllegalArgumentException("merchantId is required");
            }
            AppUser merchant = userRepository.findById(order.getMerchantId())
                    .orElseThrow(() -> new IllegalArgumentException("Merchant not found"));
            if (!tenantId.equals(merchant.getTenantId())) {
                throw new IllegalArgumentException("Merchant is not in your tenant");
            }

            if (order.getOrderId() == null
                    || order.getOrderKey() == null
                    || order.getOrderKey().isEmpty()) {
                throw new IllegalArgumentException("orderId and orderKey are required for /payment");
            }

            String idempotencyKey = "woo-" + order.getOrderId() + "-" + order.getOrderKey();

            AppUser customer = getOrCreateCustomer(order, tenantId);

            Invoice invoice = invoiceService.createInvoice(
                    order.getTotal(),
                    "WooCommerce Order #" + order.getOrderId(),
                    order.getBillingEmail(),
                    order.getMerchantId(),
                    false,
                    order.getCurrency(),
                    null, null, null, null
            );

            PaymentRequest request = new PaymentRequest();
            request.setInvoiceId(invoice.getId());
            request.setCustomerId(customer.getId());
            request.setMerchantId(order.getMerchantId());
            request.setAmount(order.getTotal());
            request.setCurrency(order.getCurrency());
            request.setDescription("WooCommerce order " + order.getOrderId());

            PaymentResponse response = transactionService.processPayment(request, idempotencyKey);

            try {
                webhookService.sendWebhooks("WOOCOMMERCE_ORDER_UPDATE", Map.of(
                        "orderId", order.getOrderId(),
                        "status", response.getStatus(),
                        "transactionId", response.getTransactionId(),
                        "customerId", customer.getId(),
                        "customerEmail", customer.getEmail()
                ));
            } catch (Exception e) {
                // Webhook failures must not fail the payment response.
                log.warn("WOOCOMMERCE_ORDER_UPDATE webhook failed for order {}: {}",
                        order.getOrderId(), e.getMessage());
            }

            WooCommerceResponse wooResponse = new WooCommerceResponse(
                    order.getOrderId(),
                    response.getStatus(),
                    response.getTransactionId(),
                    "Payment " + response.getStatus().toLowerCase()
            );
            return ResponseEntity.ok(wooResponse);
        } finally {
            TenantContext.clear();
        }
    }

    // ============================================================
    // HELPER: Get-or-create customer, tenant-scoped
    // ============================================================
    private AppUser getOrCreateCustomer(WooCommerceOrder order, Long tenantId) {
        String rawEmail = order.getBillingEmail();
        if (rawEmail == null || rawEmail.trim().isEmpty()) {
            throw new RuntimeException("Billing email is required");
        }
        // ✅ final local so lambdas can capture it
        final String email = rawEmail.trim().toLowerCase();

        // 1. Try tenant-scoped lookup first (normal path)
        Optional<AppUser> existingUser = userRepository.findByEmailIgnoreCase(email);
        if (existingUser.isPresent()) {
            AppUser user = existingUser.get();
            if (!user.isActive()) {
                user.setActive(true);
                user = userRepository.save(user);
            }
            boolean hasCustomerRole = user.getRoles().stream()
                    .anyMatch(r -> r.getName().equals("CUSTOMER"));
            if (!hasCustomerRole) {
                Role customerRole = roleRepository.findByName("CUSTOMER")
                        .orElseThrow(() -> new RuntimeException("CUSTOMER role not found"));
                user.getRoles().add(customerRole);
                user = userRepository.save(user);
            }
            return user;
        }

        // 2. Email may already exist in another tenant (email is globally unique).
        //    Reuse the existing user only if it's in the same tenant; otherwise refuse.
        Optional<AppUser> globalUser = userRepository.findByEmailIgnoreCaseGlobal(email);
        if (globalUser.isPresent()) {
            AppUser user = globalUser.get();
            if (!user.getTenantId().equals(tenantId)) {
                throw new IllegalStateException(
                        "This email is already registered in another tenant. " +
                        "Use a different billing email or contact support.");
            }
            return user;
        }

        // 3. Create a new customer
        AppUser newUser = new AppUser();
        newUser.setEmail(email);
        String password = order.getCustomerPassword() != null && !order.getCustomerPassword().isEmpty()
                ? order.getCustomerPassword()
                : UUID.randomUUID().toString();
        newUser.setPasswordHash(passwordEncoder.encode(password));
        newUser.setActive(true);
        newUser.setTenantId(tenantId);
        Role customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow(() -> new RuntimeException("CUSTOMER role not found"));
        newUser.setRoles(Set.of(customerRole));

        try {
            return userRepository.save(newUser);
        } catch (DataIntegrityViolationException e) {
            // Race — another thread created the same user. Fetch it globally.
            AppUser raced = userRepository.findByEmailIgnoreCaseGlobal(email)
                    .orElseThrow(() -> new RuntimeException(
                            "Failed to create or find user: " + email));
            if (!raced.getTenantId().equals(tenantId)) {
                throw new IllegalStateException(
                        "This email is already registered in another tenant.");
            }
            return raced;
        }
    }

    // ---------- DTOs ----------
    public static class WooCommerceOrder {
        private Long orderId;
        private String orderKey;
        private BigDecimal total;
        private String currency;
        private String billingEmail;
        private String customerPassword;
        private Long merchantId;
        private Long tenantId;   // accepted but IGNORED — tenant comes from API key
        private String webhookUrl;
        private String returnUrl;

        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public String getOrderKey() { return orderKey; }
        public void setOrderKey(String orderKey) { this.orderKey = orderKey; }
        public BigDecimal getTotal() { return total; }
        public void setTotal(BigDecimal total) { this.total = total; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public String getBillingEmail() { return billingEmail; }
        public void setBillingEmail(String billingEmail) { this.billingEmail = billingEmail; }
        public String getCustomerPassword() { return customerPassword; }
        public void setCustomerPassword(String customerPassword) { this.customerPassword = customerPassword; }
        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
        public Long getTenantId() { return tenantId; }
        public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
        public String getReturnUrl() { return returnUrl; }
        public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }
    }

    public static class WooCommerceResponse {
        private Long orderId;
        private String status;
        private Long transactionId;
        private String message;

        public WooCommerceResponse(Long orderId, String status, Long transactionId, String message) {
            this.orderId = orderId;
            this.status = status;
            this.transactionId = transactionId;
            this.message = message;
        }
        public Long getOrderId() { return orderId; }
        public String getStatus() { return status; }
        public Long getTransactionId() { return transactionId; }
        public String getMessage() { return message; }
    }
}