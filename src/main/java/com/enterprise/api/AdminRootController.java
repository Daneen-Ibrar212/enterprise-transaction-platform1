package com.enterprise.api;

import com.enterprise.audit.AuditRepository;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.invoice.InvoiceRepository;
import com.enterprise.reconciliation.ReconciliationRecordRepository;
import com.enterprise.reliability.DlqEntryRepository;
import com.enterprise.transaction.TransactionRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class AdminRootController {

    private final TransactionRepository transactionRepository;
    private final AuditRepository auditRepository;
    private final DlqEntryRepository dlqEntryRepository;
    private final ReconciliationRecordRepository reconciliationRecordRepository;
    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;

    public AdminRootController(TransactionRepository transactionRepository,
                               AuditRepository auditRepository,
                               DlqEntryRepository dlqEntryRepository,
                               ReconciliationRecordRepository reconciliationRecordRepository,
                               InvoiceRepository invoiceRepository,
                               UserRepository userRepository) {
        this.transactionRepository = transactionRepository;
        this.auditRepository = auditRepository;
        this.dlqEntryRepository = dlqEntryRepository;
        this.reconciliationRecordRepository = reconciliationRecordRepository;
        this.invoiceRepository = invoiceRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String adminRoot(Model model, Authentication authentication) {
        AppUser user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
        Long tenantId = user.getTenantId();

        boolean isSuperAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        boolean isMerchantAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_MERCHANT_ADMIN"));

        long totalTransactions = transactionRepository.countByTenantId(tenantId);
        long totalAuditEvents = auditRepository.countByTenantId(tenantId);
        long pendingDlq = dlqEntryRepository.countByTenantIdAndStatus(tenantId, "PENDING");
        long reconciliationRuns = reconciliationRecordRepository.countByTenantId(tenantId);

        model.addAttribute("totalTransactions", totalTransactions);
        model.addAttribute("totalAuditEvents", totalAuditEvents);
        model.addAttribute("pendingDlq", pendingDlq);
        model.addAttribute("reconciliationRuns", reconciliationRuns);  // renamed from "reconciled"

        if (isSuperAdmin || isMerchantAdmin) {
            model.addAttribute("suspiciousCount",
                    invoiceRepository.countSuspiciousByTenantId(tenantId));
        }
        model.addAttribute("isSuperAdmin", isSuperAdmin);
        model.addAttribute("isMerchantAdmin", isMerchantAdmin);
        model.addAttribute("roleBadge", isSuperAdmin ? "Super Admin" : "Merchant Admin");
        model.addAttribute("viewType", "admin");
        model.addAttribute("tenantId", tenantId);
        return "admin/dashboard";
    }
}