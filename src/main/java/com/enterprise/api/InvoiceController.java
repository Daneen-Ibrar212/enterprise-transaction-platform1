package com.enterprise.api;

import com.enterprise.currency.ExchangeRateService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.invoice.*;
import com.enterprise.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Controller
@RequestMapping("/invoices")
public class InvoiceController {

    private static final Logger log = LoggerFactory.getLogger(InvoiceController.class);

    private final InvoiceService invoiceService;
    private final UserRepository userRepository;
    private final InvoiceMessageService messageService;
    private final ExchangeRateService exchangeRateService;
    private final InvoicePdfService invoicePdfService;

    public InvoiceController(InvoiceService invoiceService,
                             UserRepository userRepository,
                             InvoiceMessageService messageService,
                             ExchangeRateService exchangeRateService,
                             InvoicePdfService invoicePdfService) {
        this.invoiceService = invoiceService;
        this.userRepository = userRepository;
        this.messageService = messageService;
        this.exchangeRateService = exchangeRateService;
        this.invoicePdfService = invoicePdfService;
    }

    /**
     * Loads an invoice and asserts the caller can access it.
     *
     * Access rules:
     *  - SUPER_ADMIN / MERCHANT_ADMIN / ADMIN  → any invoice in their tenant
     *  - MERCHANT  → invoices they own
     *  - CUSTOMER  → invoices where they're the customer email
     */
    private Invoice loadAccessibleInvoice(Long id, AppUser user) {
        Invoice invoice = invoiceService.findById(id)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));

        // Tenant check — belt and suspenders (filter should already apply)
        Long callerTenant = TenantContext.getRequiredTenantId();
        if (invoice.getTenantId() != null && !invoice.getTenantId().equals(callerTenant)) {
            throw new AccessDeniedException("Invoice not accessible");
        }

        boolean isAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN")
                            || r.getName().equals("MERCHANT_ADMIN")
                            || r.getName().equals("ADMIN"));
        if (isAdmin) return invoice;

        boolean isMerchant = user.getId().equals(invoice.getMerchantId());
        boolean isCustomer = invoice.getCustomerEmail().equalsIgnoreCase(user.getEmail());
        if (!isMerchant && !isCustomer) {
            throw new AccessDeniedException("You do not have access to this invoice");
        }
        return invoice;
    }

    private AppUser currentUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ============================================================
    // CREATE
    // ============================================================
    @GetMapping("/create")
    public String showCreateForm(Model model) {
        model.addAttribute("invoice", new Invoice());
        model.addAttribute("currencies", com.enterprise.currency.CurrencyCodes.getAllCurrencies());
        return "invoice/create";
    }

    @PostMapping("/create")
    public String createInvoice(@RequestParam BigDecimal amount,
                                @RequestParam String description,
                                @RequestParam String customerEmail,
                                @RequestParam(defaultValue = "GBP") String currency,
                                Authentication authentication) {
        AppUser merchant = currentUser(authentication);
        boolean requiresApproval = amount.compareTo(BigDecimal.valueOf(5000)) > 0;
        invoiceService.createInvoice(amount, description, customerEmail,
                merchant.getId(), requiresApproval, currency);
        return "redirect:/dashboard";
    }

    // ============================================================
    // LISTS
    // ============================================================
    @GetMapping("/customer")
    public String customerInvoices(Authentication authentication, Model model) {
        AppUser user = currentUser(authentication);
        model.addAttribute("invoices",
                invoiceService.getInvoicesForCustomer(user.getEmail()));
        return "invoice/customer-list";
    }

    @GetMapping("/merchant")
    public String merchantInvoices(Authentication authentication, Model model) {
        AppUser merchant = currentUser(authentication);
        model.addAttribute("invoices",
                invoiceService.getInvoicesForMerchant(merchant.getId()));
        return "invoice/merchant-list";
    }

    // ============================================================
    // VIEW
    // ============================================================
    @GetMapping("/{id}")
    public String viewInvoice(@PathVariable Long id,
                              Authentication authentication,
                              Model model) {
        try {
            AppUser user = currentUser(authentication);
            Invoice invoice = loadAccessibleInvoice(id, user);
            model.addAttribute("invoice", invoice);
            model.addAttribute("symbol", exchangeRateService.getSymbol(invoice.getCurrency()));
            return "invoice/detail";
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error loading invoice {}", id, e);
            model.addAttribute("error", "Could not load invoice details.");
            return "error";
        }
    }

    // ============================================================
    // EDIT
    // ============================================================
    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable Long id,
                               Authentication authentication,
                               Model model) {
        AppUser user = currentUser(authentication);
        Invoice invoice = loadAccessibleInvoice(id, user);
        model.addAttribute("invoice", invoice);
        model.addAttribute("currencies", com.enterprise.currency.CurrencyCodes.getAllCurrencies());
        return "invoice/edit";
    }

    @PostMapping("/{id}")
    public String updateInvoice(@PathVariable Long id,
                                @RequestParam String description,
                                @RequestParam(required = false) String currency,
                                Authentication authentication) {
        AppUser user = currentUser(authentication);
        Invoice invoice = loadAccessibleInvoice(id, user);

        // Only merchant (owner) or admin can edit
        boolean canEdit = invoice.getMerchantId().equals(user.getId())
                || user.getRoles().stream().anyMatch(r ->
                        r.getName().equals("SUPER_ADMIN")
                        || r.getName().equals("MERCHANT_ADMIN")
                        || r.getName().equals("ADMIN"));
        if (!canEdit) throw new AccessDeniedException("You cannot edit this invoice");

        invoice.setDescription(description);
        if (currency != null && !currency.isEmpty()) invoice.setCurrency(currency);
        invoice.setUpdatedAt(java.time.LocalDateTime.now());
        invoiceService.updateInvoice(invoice);
        return "redirect:/invoices/" + id;
    }

    // ============================================================
    // PDF — access-controlled
    // ============================================================
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long id,
                                            Authentication authentication) {
        try {
            AppUser user = currentUser(authentication);
            Invoice invoice = loadAccessibleInvoice(id, user);

            if (!"APPROVED".equals(invoice.getStatus())
                    && !"PAID".equals(invoice.getStatus())
                    && !"PENDING_APPROVAL".equals(invoice.getStatus())) {
                return ResponseEntity.badRequest().build();
            }

            byte[] pdf = invoicePdfService.generateInvoicePdf(invoice);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "invoice_" + id + ".pdf");
            headers.setContentLength(pdf.length);
            return ResponseEntity.ok().headers(headers).body(pdf);
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error generating PDF for invoice {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{id}/pay")
    public String redirectPay(@PathVariable Long id,
                              Authentication authentication) {
        AppUser user = currentUser(authentication);
        loadAccessibleInvoice(id, user);  // access check
        return "redirect:/pay/" + id;
    }

    // ============================================================
    // CHAT — access-controlled
    // ============================================================
    @GetMapping("/{id}/messages")
    public String viewChat(@PathVariable Long id,
                           Model model,
                           Authentication authentication) {
        AppUser user = currentUser(authentication);
        Invoice invoice = loadAccessibleInvoice(id, user);
        List<InvoiceMessage> messages = messageService.getMessagesForInvoice(id);
        model.addAttribute("invoice", invoice);
        model.addAttribute("messages", messages);
        model.addAttribute("userId", user.getId());
        return "invoice/chat";
    }

    @PostMapping("/{id}/messages")
    public String sendMessage(@PathVariable Long id,
                              @RequestParam String message,
                              Authentication authentication) {
        AppUser user = currentUser(authentication);
        Invoice invoice = loadAccessibleInvoice(id, user);
        messageService.sendMessage(invoice, user, message);
        return "redirect:/invoices/" + id + "/messages";
    }
}