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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Payment page.
 *
 * Security model (was: permitAll — critical bug):
 *   • Requires authentication — /pay/** now authenticated in SecurityConfig.
 *   • Uses the AUTHENTICATED USER's tenant, never the invoice's.
 *   • Verifies the invoice belongs to the caller's tenant before rendering.
 *   • No SSRF: webhook delivery uses the safe WebhookService.sendWebhooks,
 *     not a raw RestTemplate POST to invoice.webhookUrl.
 */
@Controller
@RequestMapping("/pay")
public class PaymentPageController {

    private static final Logger log = LoggerFactory.getLogger(PaymentPageController.class);

    private final InvoiceService invoiceService;
    private final TransactionService transactionService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public PaymentPageController(InvoiceService invoiceService,
                                 TransactionService transactionService,
                                 UserRepository userRepository,
                                 RoleRepository roleRepository,
                                 PasswordEncoder passwordEncoder) {
        this.invoiceService = invoiceService;
        this.transactionService = transactionService;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/{invoiceId}")
    public String paymentPage(@PathVariable Long invoiceId,
                              Authentication auth,
                              Model model) {
        Long callerTenant = TenantContext.getRequiredTenantId();
        Invoice invoice = invoiceService.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));

        // ✅ Tenant check: never serve an invoice from another tenant
        if (invoice.getTenantId() != null && !invoice.getTenantId().equals(callerTenant)) {
            log.warn("Cross-tenant invoice access attempt: user tenant={}, invoice tenant={}",
                    callerTenant, invoice.getTenantId());
            throw new org.springframework.security.access.AccessDeniedException("Invoice not accessible");
        }

        if (!"APPROVED".equals(invoice.getStatus())) {
            return "redirect:/dashboard?error=Invoice+not+approved+for+payment";
        }

        // Only the invoice's customer can pay it (unless admin/merchant)
        AppUser caller = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        boolean isCustomer = invoice.getCustomerEmail().equalsIgnoreCase(caller.getEmail());
        boolean isAdmin = caller.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN")
                            || r.getName().equals("MERCHANT_ADMIN")
                            || r.getName().equals("ADMIN"));
        boolean isMerchant = caller.getId().equals(invoice.getMerchantId());
        if (!isCustomer && !isAdmin && !isMerchant) {
            throw new org.springframework.security.access.AccessDeniedException("You cannot pay this invoice");
        }

        model.addAttribute("invoice", invoice);
        // ✅ Idempotency key is derived from invoice ID — a page refresh reuses the
        //    same key, preventing double-charge on refresh.
        model.addAttribute("idempotencyKey", "pay-" + invoiceId);
        return "payment/gateway";
    }

    @PostMapping("/{invoiceId}")
    public String processPayment(@PathVariable Long invoiceId,
                                 @RequestParam String idempotencyKey,
                                 @RequestParam(required = false) String cardNumber,
                                 @RequestParam(required = false) String expiry,
                                 @RequestParam(required = false) String cvv,
                                 Authentication auth,
                                 Model model) {
        Long callerTenant = TenantContext.getRequiredTenantId();

        try {
            Invoice invoice = invoiceService.findById(invoiceId)
                    .orElseThrow(() -> new RuntimeException("Invoice not found"));

            if (invoice.getTenantId() != null && !invoice.getTenantId().equals(callerTenant)) {
                throw new org.springframework.security.access.AccessDeniedException("Invoice not accessible");
            }
            if (!"APPROVED".equals(invoice.getStatus())) {
                model.addAttribute("error", "This invoice is not available for payment.");
                return "payment/error";
            }

            // ✅ Use the authenticated user — do NOT create/modify users here.
            AppUser caller = userRepository.findByEmail(auth.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Only the invoice's customer can pay it
            if (!invoice.getCustomerEmail().equalsIgnoreCase(caller.getEmail())) {
                boolean isAdmin = caller.getRoles().stream()
                        .anyMatch(r -> r.getName().equals("SUPER_ADMIN")
                                    || r.getName().equals("MERCHANT_ADMIN"));
                if (!isAdmin) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "You cannot pay this invoice");
                }
            }

            // ✅ Enforce the caller's own idempotency key namespace
            if (!idempotencyKey.startsWith("pay-" + invoiceId)) {
                throw new IllegalArgumentException("Invalid idempotency key for this invoice");
            }

            PaymentRequest request = new PaymentRequest();
            request.setInvoiceId(invoiceId);
            request.setCustomerId(caller.getId());
            request.setMerchantId(invoice.getMerchantId());
            request.setAmount(invoice.getAmount());
            request.setCurrency(invoice.getCurrency());

            PaymentResponse response = transactionService.processPayment(request, idempotencyKey);

            if ("SETTLED".equals(response.getStatus())) {
                invoiceService.markAsPaid(invoiceId, response.getTransactionId());

                // ✅ Use WebhookService (HMAC-signed, tenant-scoped) — no SSRF
                try {
                    com.enterprise.webhook.WebhookService ws =
                            applicationContext.getBean(com.enterprise.webhook.WebhookService.class);
                    ws.sendWebhooks("INVOICE_PAID", Map.of(
                            "invoiceId", invoiceId,
                            "transactionId", response.getTransactionId(),
                            "status", response.getStatus()));
                } catch (Exception e) {
                    log.warn("Post-payment webhook fan-out failed: {}", e.getMessage());
                }

                if (invoice.getReturnUrl() != null && !invoice.getReturnUrl().isEmpty()
                        && invoice.getWooOrderId() != null) {
                    String orderKey = invoice.getOrderKey() != null
                            ? invoice.getOrderKey()
                            : "wc_order_" + invoice.getWooOrderId();
                    return "redirect:" + invoice.getReturnUrl() + "/" + invoice.getWooOrderId()
                            + "/?key=" + orderKey;
                }

                model.addAttribute("response", response);
                return "payment/result";
            }

            model.addAttribute("response", response);
            return "payment/result";

        } catch (Exception e) {
            log.error("Payment error for invoice {}", invoiceId, e);
            model.addAttribute("error", "Payment failed: " + e.getMessage());
            return "payment/error";
        } finally {
            // ✅ Do NOT clear tenant — the interceptor's afterCompletion will. Clearing
            //    here breaks the notification system which runs after the controller returns.
        }
    }

    // ⚠️ Simplification: injected lazily to avoid a cyclic dep.
    // In your real code, add WebhookService to the constructor and remove this.
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext applicationContext;
}