package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.tenant.TenantContext;
import com.enterprise.transaction.Transaction;
import com.enterprise.transaction.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@PreAuthorize("isAuthenticated()")
public class TransactionViewController {

    private static final Logger log = LoggerFactory.getLogger(TransactionViewController.class);

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    public TransactionViewController(TransactionRepository transactionRepository,
                                     UserRepository userRepository) {
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
    }

    private AppUser currentUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private boolean isAdmin(AppUser user) {
        return user.getRoles().stream().anyMatch(r ->
                r.getName().equals("SUPER_ADMIN")
                        || r.getName().equals("MERCHANT_ADMIN")
                        || r.getName().equals("ADMIN"));
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         Authentication authentication,
                         Model model) {
        AppUser user = currentUser(authentication);
        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        // Tenant check — belt & suspenders (filter should already apply, but
        // this makes the intent explicit and catches filter-registration bugs)
        Long callerTenant = TenantContext.getRequiredTenantId();
        if (tx.getTenantId() != null && !tx.getTenantId().equals(callerTenant)) {
            throw new AccessDeniedException("Transaction not accessible");
        }

        // Ownership check
        boolean isMerchant = user.getId().equals(tx.getMerchantId());
        boolean isCustomer = user.getId().equals(tx.getCustomerId());
        if (!isAdmin(user) && !isMerchant && !isCustomer) {
            throw new AccessDeniedException("You do not have access to this transaction");
        }

        model.addAttribute("transaction", tx);
        return "transactions/detail";
    }

    @GetMapping
    public String listTransactions(Authentication authentication, Model model) {
        try {
            AppUser user = currentUser(authentication);
            boolean isSuperAdmin = user.getRoles().stream()
                    .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));
            boolean isMerchantAdmin = user.getRoles().stream()
                    .anyMatch(r -> r.getName().equals("MERCHANT_ADMIN"));
            boolean isMerchant = user.getRoles().stream()
                    .anyMatch(r -> r.getName().equals("MERCHANT"));
            boolean isCustomer = user.getRoles().stream()
                    .anyMatch(r -> r.getName().equals("CUSTOMER"));

            List<Transaction> transactions;
            String viewRole;

            if (isSuperAdmin) {
                // Super admin sees all transactions in their tenant.
                // (Note: prior code called findAll() which is still tenant-filtered.
                //  That is the intended behavior — "all" here means "all in my tenant".)
                transactions = transactionRepository.findAll();
                viewRole = "SUPER_ADMIN";
            } else if (isMerchantAdmin || isMerchant) {
                transactions = transactionRepository
                        .findByMerchantIdOrderByCreatedAtDesc(user.getId());
                viewRole = isMerchantAdmin ? "MERCHANT_ADMIN" : "MERCHANT";
            } else if (isCustomer) {
                transactions = transactionRepository
                        .findByCustomerIdOrderByCreatedAtDesc(user.getId());
                viewRole = "CUSTOMER";
            } else {
                transactions = List.of();
                viewRole = "UNKNOWN";
                model.addAttribute("info", "You do not have any transactions to view.");
            }

            model.addAttribute("transactions", transactions);
            model.addAttribute("userRole", viewRole);
            return "transactions/list";

        } catch (Exception e) {
            log.error("Error loading transaction list: {}", e.getMessage(), e);
            model.addAttribute("error", "Failed to load transactions: " + e.getMessage());
            model.addAttribute("transactions", List.of());
            return "transactions/list";
        }
    }
}