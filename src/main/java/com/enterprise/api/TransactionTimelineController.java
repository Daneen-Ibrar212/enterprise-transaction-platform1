package com.enterprise.api;

import com.enterprise.audit.AuditEvent;
import com.enterprise.audit.AuditRepository;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.tenant.TenantContext;
import com.enterprise.transaction.Transaction;
import com.enterprise.transaction.TransactionRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/transactions")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN', 'MERCHANT', 'CUSTOMER')")
public class TransactionTimelineController {

    private final TransactionRepository transactionRepository;
    private final AuditRepository auditRepository;
    private final UserRepository userRepository;

    public TransactionTimelineController(TransactionRepository transactionRepository,
                                         AuditRepository auditRepository,
                                         UserRepository userRepository) {
        this.transactionRepository = transactionRepository;
        this.auditRepository = auditRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/{id}/timeline")
    public String timeline(@PathVariable Long id,
                           Authentication authentication,
                           Model model) {
        AppUser user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        // Tenant check
        Long callerTenant = TenantContext.getRequiredTenantId();
        if (tx.getTenantId() != null && !tx.getTenantId().equals(callerTenant)) {
            throw new AccessDeniedException("Transaction not accessible");
        }

        // Ownership check
        boolean isAdmin = user.getRoles().stream().anyMatch(r ->
                r.getName().equals("SUPER_ADMIN") || r.getName().equals("MERCHANT_ADMIN"));
        boolean isMerchant = user.getId().equals(tx.getMerchantId());
        boolean isCustomer = user.getId().equals(tx.getCustomerId());
        if (!isAdmin && !isMerchant && !isCustomer) {
            throw new AccessDeniedException("You do not have access to this transaction");
        }

        List<AuditEvent> events = auditRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtAsc("Transaction", id);
        model.addAttribute("transaction", tx);
        model.addAttribute("events", events);
        return "transactions/timeline";
    }
}