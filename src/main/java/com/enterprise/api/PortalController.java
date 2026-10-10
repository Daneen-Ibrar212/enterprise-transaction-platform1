package com.enterprise.api;

import com.enterprise.customer.CustomerProfile;
import com.enterprise.customer.CustomerProfileService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.invoice.Invoice;
import com.enterprise.invoice.InvoicePdfService;
import com.enterprise.invoice.InvoiceService;
import com.enterprise.reporting.CustomerReceipt;
import com.enterprise.reporting.CustomerStatementService;
import com.enterprise.subscription.CustomerSubscription;
import com.enterprise.subscription.SubscriptionService;
import com.enterprise.transaction.Transaction;
import com.enterprise.transaction.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/portal")
@PreAuthorize("hasRole('CUSTOMER')")
public class PortalController {

    private static final Logger log = LoggerFactory.getLogger(PortalController.class);

    private final UserRepository userRepository;
    private final InvoiceService invoiceService;
    private final TransactionRepository transactionRepository;
    private final SubscriptionService subscriptionService;
    private final InvoicePdfService invoicePdfService;
    private final CustomerProfileService profileService;
    private final CustomerStatementService statementService;

    public PortalController(UserRepository userRepository,
                            InvoiceService invoiceService,
                            TransactionRepository transactionRepository,
                            SubscriptionService subscriptionService,
                            InvoicePdfService invoicePdfService,
                            CustomerProfileService profileService,
                            CustomerStatementService statementService) {
        this.userRepository = userRepository;
        this.invoiceService = invoiceService;
        this.transactionRepository = transactionRepository;
        this.subscriptionService = subscriptionService;
        this.invoicePdfService = invoicePdfService;
        this.profileService = profileService;
        this.statementService = statementService;
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private AppUser currentUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ============================================================
    // DASHBOARD
    // ============================================================
    @GetMapping
    public String dashboard(Model model, Authentication auth) {
        AppUser user = currentUser(auth);

        List<Invoice> invoices = invoiceService.getInvoicesForCustomer(user.getEmail());
        List<Transaction> transactions = transactionRepository
                .findByCustomerIdOrderByCreatedAtDesc(user.getId());
        List<CustomerSubscription> subscriptions =
                subscriptionService.getCustomerSubscriptions(user.getId());

        long unpaidCount = invoices.stream()
                .filter(i -> "APPROVED".equals(i.getStatus()))
                .count();

        BigDecimal totalPaid = transactions.stream()
                .filter(tx -> "SETTLED".equals(tx.getStatus().name()))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long activeSubs = subscriptions.stream().filter(CustomerSubscription::isActive).count();

        model.addAttribute("user", user);
        model.addAttribute("unpaidCount", unpaidCount);
        model.addAttribute("totalPaid", totalPaid);
        model.addAttribute("activeSubs", activeSubs);
        model.addAttribute("recentInvoices", invoices.stream().limit(5).collect(Collectors.toList()));
        model.addAttribute("recentPayments", transactions.stream().limit(5).collect(Collectors.toList()));
        model.addAttribute("profileComplete", profileService.getOrCreate(user.getId()).isComplete());

        return "portal/dashboard";
    }

    // ============================================================
    // INVOICES
    // ============================================================
    @GetMapping("/invoices")
    public String invoices(Model model, Authentication auth) {
        AppUser user = currentUser(auth);
        List<Invoice> invoices = invoiceService.getInvoicesForCustomer(user.getEmail());
        model.addAttribute("invoices", invoices);
        return "portal/invoices";
    }

    @GetMapping("/invoices/{id}/receipt")
    public ResponseEntity<byte[]> invoiceReceipt(@PathVariable Long id, Authentication auth) {
        AppUser user = currentUser(auth);
        Invoice invoice = invoiceService.findById(id)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));

        if (!invoice.getCustomerEmail().equalsIgnoreCase(user.getEmail())) {
            return ResponseEntity.status(403).build();
        }

        // Find matching settled transaction
        Transaction tx = transactionRepository.findByCustomerIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .filter(t -> t.getInvoiceId().equals(id) && "SETTLED".equals(t.getStatus().name()))
                .findFirst()
                .orElse(null);

        byte[] pdf = invoicePdfService.generateReceiptPdf(invoice, tx);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=receipt_invoice_" + id + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    // ============================================================
    // PAYMENTS
    // ============================================================
    @GetMapping("/payments")
    public String payments(Model model, Authentication auth) {
        AppUser user = currentUser(auth);
        List<Transaction> transactions = transactionRepository
                .findByCustomerIdOrderByCreatedAtDesc(user.getId());
        model.addAttribute("transactions", transactions);
        return "portal/payments";
    }

    // ============================================================
    // SUBSCRIPTIONS
    // ============================================================
    @GetMapping("/subscriptions")
    public String subscriptions(Model model, Authentication auth) {
        AppUser user = currentUser(auth);
        List<CustomerSubscription> subs = subscriptionService.getCustomerSubscriptions(user.getId());
        model.addAttribute("subscriptions", subs);
        return "portal/subscriptions";
    }

    @PostMapping("/subscriptions/{id}/cancel")
    public String cancelSubscription(@PathVariable Long id,
                                     Authentication auth,
                                     RedirectAttributes redirect) {
        AppUser user = currentUser(auth);
        CustomerSubscription sub = subscriptionService.getSubscriptionById(id);

        if (!sub.getCustomerId().equals(user.getId())) {
            redirect.addFlashAttribute("error", "You do not own this subscription.");
            return "redirect:/portal/subscriptions";
        }

        try {
            subscriptionService.cancelSubscription(id);
            redirect.addFlashAttribute("success", "✅ Subscription cancelled.");
        } catch (Exception e) {
            log.error("Cancel failed for sub {}", id, e);
            redirect.addFlashAttribute("error", "Failed to cancel: " + e.getMessage());
        }
        return "redirect:/portal/subscriptions";
    }

    // ============================================================
    // STATEMENTS (combined PDF)
    // ============================================================
    @GetMapping("/statements")
    public String statements(Model model, Authentication auth) {
        AppUser user = currentUser(auth);
        List<CustomerReceipt> receipts = statementService.listForUser(user.getId());
        model.addAttribute("receipts", receipts);
        return "portal/statements";
    }

    @PostMapping("/statements/generate")
    public String generateStatement(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication auth,
            RedirectAttributes redirect) {
        AppUser user = currentUser(auth);

        if (startDate.isAfter(endDate)) {
            redirect.addFlashAttribute("error", "Start date must be before end date.");
            return "redirect:/portal/statements";
        }

        try {
            statementService.generateStatement(user.getId(), user.getEmail(), startDate, endDate);
            redirect.addFlashAttribute("success", "✅ Statement generated.");
        } catch (Exception e) {
            log.error("Statement generation failed", e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/portal/statements";
    }

    @GetMapping("/statements/{id}/download")
    public ResponseEntity<byte[]> downloadStatement(@PathVariable Long id, Authentication auth) {
        AppUser user = currentUser(auth);
        CustomerReceipt receipt = statementService.getReceipt(id, user.getId());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + receipt.getFilename())
                .contentType(MediaType.APPLICATION_PDF)
                .body(receipt.getContent());
    }

    // ============================================================
    // BILLING PROFILE
    // ============================================================
    @GetMapping("/billing")
    public String billingForm(Model model, Authentication auth) {
        AppUser user = currentUser(auth);
        CustomerProfile profile = profileService.getOrCreate(user.getId());
        model.addAttribute("profile", profile);
        model.addAttribute("user", user);
        return "portal/billing";
    }

    @PostMapping("/billing")
    public String saveBilling(@ModelAttribute CustomerProfile incoming,
                              Authentication auth,
                              RedirectAttributes redirect) {
        AppUser user = currentUser(auth);
        try {
            profileService.save(user.getId(), incoming);
            redirect.addFlashAttribute("success", "✅ Billing details saved.");
        } catch (Exception e) {
            log.error("Billing save failed", e);
            redirect.addFlashAttribute("error", "Failed to save: " + e.getMessage());
        }
        return "redirect:/portal/billing";
    }
}