package com.enterprise.api;

import com.enterprise.audit.AuditRepository;
import com.enterprise.currency.ExchangeRateService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.invoice.Invoice;
import com.enterprise.invoice.InvoiceRepository;
import com.enterprise.invoice.InvoiceService;
import com.enterprise.notification.NotificationService;
import com.enterprise.ratelimit.RateLimitConfig;
import com.enterprise.ratelimit.RateLimitViolation;
import com.enterprise.reconciliation.ReconciliationRecordRepository;
import com.enterprise.reliability.DlqEntryRepository;
import com.enterprise.reporting.ReportingService;
import com.enterprise.security.RateLimitService;
import com.enterprise.tenant.Tenant;
import com.enterprise.tenant.TenantRepository;
import com.enterprise.transaction.Transaction;
import com.enterprise.transaction.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final TransactionRepository transactionRepository;
    private final AuditRepository auditRepository;
    private final ReconciliationRecordRepository reconciliationRecordRepository;
    private final DlqEntryRepository dlqEntryRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final InvoiceService invoiceService;
    private final ReportingService reportingService;
    private final InvoiceRepository invoiceRepository;
    private final TenantRepository tenantRepository;
    private final ExchangeRateService exchangeRateService;
    private final RateLimitService rateLimitService;

    public DashboardController(TransactionRepository transactionRepository,
                               AuditRepository auditRepository,
                               ReconciliationRecordRepository reconciliationRecordRepository,
                               DlqEntryRepository dlqEntryRepository,
                               NotificationService notificationService,
                               UserRepository userRepository,
                               InvoiceService invoiceService,
                               ReportingService reportingService,
                               InvoiceRepository invoiceRepository,
                               TenantRepository tenantRepository,
                               ExchangeRateService exchangeRateService,
                               RateLimitService rateLimitService) {
        this.transactionRepository = transactionRepository;
        this.auditRepository = auditRepository;
        this.reconciliationRecordRepository = reconciliationRecordRepository;
        this.dlqEntryRepository = dlqEntryRepository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.invoiceService = invoiceService;
        this.reportingService = reportingService;
        this.invoiceRepository = invoiceRepository;
        this.tenantRepository = tenantRepository;
        this.exchangeRateService = exchangeRateService;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model, Authentication authentication) {
        AppUser user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        Long userId = user.getId();
        Long tenantId = user.getTenantId();

        // ===== ROLE CHECKS =====
        boolean isSuperAdmin = user.getRoles().stream().anyMatch(r -> r.getName().equals("SUPER_ADMIN"));
        boolean isMerchantAdmin = user.getRoles().stream().anyMatch(r -> r.getName().equals("MERCHANT_ADMIN"));
        boolean isMerchant = user.getRoles().stream().anyMatch(r -> r.getName().equals("MERCHANT"));
        boolean isCustomer = user.getRoles().stream().anyMatch(r -> r.getName().equals("CUSTOMER"));

        model.addAttribute("isSuperAdmin", isSuperAdmin);
        model.addAttribute("isMerchantAdmin", isMerchantAdmin);
        model.addAttribute("isMerchant", isMerchant);
        model.addAttribute("isCustomer", isCustomer);

        // ===== ROLE BADGE =====
        String roleBadge = isSuperAdmin ? "Super Admin"
                : isMerchantAdmin ? "Merchant Admin"
                : isMerchant ? "Merchant"
                : isCustomer ? "Customer"
                : "User";
        model.addAttribute("roleBadge", roleBadge);

        // ===== COMMON STATS (tenant‑aware) =====
        long totalTransactions = transactionRepository.countByTenantId(tenantId);
        long totalAuditEvents = auditRepository.countByTenantId(tenantId);
        long pendingDlq = dlqEntryRepository.countByTenantIdAndStatus(tenantId, "PENDING");
        long reconciled = reconciliationRecordRepository.countByTenantId(tenantId);

        model.addAttribute("totalTransactions", totalTransactions);
        model.addAttribute("totalAuditEvents", totalAuditEvents);
        model.addAttribute("pendingDlq", pendingDlq);
        model.addAttribute("reconciled", reconciled);
        model.addAttribute("username", user.getEmail());
        model.addAttribute("roles", authentication.getAuthorities());

        long unreadCount = notificationService.countUnread(userId);
        var recentNotifications = notificationService.getRecentNotifications(userId, 5);
        model.addAttribute("unreadCount", unreadCount);
        model.addAttribute("notifications", recentNotifications);

        // ===== SUSPICIOUS INVOICES (Super Admin + Merchant Admin only) =====
        if (isSuperAdmin || isMerchantAdmin) {
            long suspiciousCount = invoiceService.findAll().stream()
                    .filter(inv -> inv.getRiskLevel() != null && !"GREEN".equals(inv.getRiskLevel()))
                    .count();
            model.addAttribute("suspiciousCount", suspiciousCount);
        }

        // ===== RATE LIMIT STATS (Super Admin + Merchant Admin only) =====
        if (isSuperAdmin || isMerchantAdmin) {
            try {
                List<RateLimitConfig> configs = rateLimitService.getAllConfigsForTenant(tenantId);
                long violations = rateLimitService.getViolationCountSince(tenantId, LocalDateTime.now().minusDays(7));
                List<RateLimitViolation> recentViolations = rateLimitService.getViolationsForTenant(tenantId, 5);

                model.addAttribute("totalRateLimitConfigs", configs != null ? configs.size() : 0);
                model.addAttribute("recentRateLimitViolations", violations);
                model.addAttribute("rateLimitRecentViolations", recentViolations);
            } catch (Exception e) {
                log.warn("Could not load rate limit stats: {}", e.getMessage());
                model.addAttribute("totalRateLimitConfigs", 0);
                model.addAttribute("recentRateLimitViolations", 0);
                model.addAttribute("rateLimitRecentViolations", Collections.emptyList());
            }
        }

        // ===== MERCHANT & MERCHANT_ADMIN SPECIFIC DATA =====
        if (isMerchant || isMerchantAdmin) {
            List<Invoice> recentInvoices = invoiceRepository.findByMerchantIdOrderByCreatedAtDesc(userId)
                    .stream().limit(5).collect(Collectors.toList());
            model.addAttribute("recentInvoices", recentInvoices);

            List<Transaction> recentTransactions = transactionRepository.findByMerchantIdOrderByCreatedAtDesc(userId)
                    .stream().limit(5).collect(Collectors.toList());
            model.addAttribute("recentTransactions", recentTransactions);

            List<Invoice> recentInvoicesForEmails = invoiceRepository.findByMerchantIdOrderByCreatedAtDesc(userId)
                    .stream().limit(20).collect(Collectors.toList());
            List<String> recentEmails = recentInvoicesForEmails.stream()
                    .map(Invoice::getCustomerEmail)
                    .distinct()
                    .limit(5)
                    .collect(Collectors.toList());
            model.addAttribute("recentEmails", recentEmails);

            long pendingApprovalCount = invoiceRepository.findByMerchantIdAndStatus(userId, "PENDING_APPROVAL").size();
            model.addAttribute("pendingApprovalCount", pendingApprovalCount);
        }

        // ===== CUSTOMER SPECIFIC DATA =====
        if (isCustomer) {
            List<Invoice> recentInvoices = invoiceRepository.findByCustomerEmailOrderByCreatedAtDesc(user.getEmail())
                    .stream().limit(5).collect(Collectors.toList());
            model.addAttribute("recentInvoices", recentInvoices);

            List<Transaction> recentTransactions = transactionRepository.findByCustomerIdOrderByCreatedAtDesc(userId)
                    .stream().limit(5).collect(Collectors.toList());
            model.addAttribute("recentTransactions", recentTransactions);
        }

        // ============================================================
        // MULTI-CURRENCY DASHBOARD
        // ============================================================
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        String baseCurrency = (tenant != null && tenant.getBaseCurrency() != null) ? tenant.getBaseCurrency() : "GBP";
        model.addAttribute("baseCurrency", baseCurrency);

        // ✅ Fetch tenant transactions ONCE (fixes N+1 query)
        List<Transaction> tenantTransactions = transactionRepository.findAllByTenantId(tenantId);

        BigDecimal totalVolume = tenantTransactions.stream()
                .map(tx -> exchangeRateService.convert(tx.getAmount(), tx.getCurrency(), baseCurrency))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        model.addAttribute("totalVolume", totalVolume);

        // ============================================================
        // ✅ 7-DAY OVERVIEW CHARTS (TENANT-AWARE, ZERO-FILLED)
        // ============================================================
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(6); // 7 days inclusive

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // ✅ Build 7-day window with zeros (ensures every day is present)
        Map<LocalDate, BigDecimal> dailyVolumeMap = new LinkedHashMap<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            dailyVolumeMap.put(cursor, BigDecimal.ZERO);
            cursor = cursor.plusDays(1);
        }

        // ✅ Fill in real values from tenant transactions
        for (Transaction tx : tenantTransactions) {
            LocalDate txDate = tx.getCreatedAt().toLocalDate();
            if (txDate.isBefore(startDate) || txDate.isAfter(endDate)) continue;

            BigDecimal amountInBase = exchangeRateService.convert(tx.getAmount(), tx.getCurrency(), baseCurrency);
            dailyVolumeMap.merge(txDate, amountInBase, BigDecimal::add);
        }

        List<String> labels = new ArrayList<>();
        List<BigDecimal> volumeData = new ArrayList<>();
        for (Map.Entry<LocalDate, BigDecimal> entry : dailyVolumeMap.entrySet()) {
            labels.add(entry.getKey().format(formatter));
            volumeData.add(entry.getValue());
        }

        // ✅ Status distribution — tenant-aware
        Map<String, Long> statusDist = tenantTransactions.stream()
                .filter(tx -> {
                    LocalDate txDate = tx.getCreatedAt().toLocalDate();
                    return !txDate.isBefore(startDate) && !txDate.isAfter(endDate);
                })
                .collect(Collectors.groupingBy(
                        tx -> tx.getStatus().name(),
                        Collectors.counting()
                ));

        List<String> statusLabels = new ArrayList<>(statusDist.keySet());
        List<Long> statusValues = statusLabels.stream().map(statusDist::get).collect(Collectors.toList());

        model.addAttribute("dashboardLabels", labels);
        model.addAttribute("dashboardVolumeData", volumeData);
        model.addAttribute("dashboardStatusLabels", statusLabels);
        model.addAttribute("dashboardStatusValues", statusValues);
        model.addAttribute("viewType", "merchant");
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("merchantId", userId);

        return "dashboard";
    }

    @PostMapping("/dashboard/invoice")
    public String createInvoice(
            @RequestParam String customerEmail,
            @RequestParam BigDecimal amount,
            @RequestParam String description,
            @RequestParam(defaultValue = "GBP") String currency,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        AppUser merchant = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (merchant.getRoles().stream().noneMatch(r -> r.getName().equals("MERCHANT"))) {
            redirectAttributes.addFlashAttribute("error", "Only merchants can create invoices.");
            return "redirect:/dashboard";
        }

        try {
            Invoice invoice = invoiceService.createInvoice(
                    amount,
                    description,
                    customerEmail,
                    merchant.getId(),
                    false,
                    currency
            );
            redirectAttributes.addFlashAttribute("success", "Invoice #" + invoice.getId() + " created successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to create invoice: " + e.getMessage());
        }

        return "redirect:/dashboard";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/ping")
    @ResponseBody
    public String ping() {
        return "pong";
    }
}