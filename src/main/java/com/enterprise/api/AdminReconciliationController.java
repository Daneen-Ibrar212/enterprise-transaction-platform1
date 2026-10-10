package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.reconciliation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/reconciliation")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class AdminReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(AdminReconciliationController.class);

    private final ReconciliationService reconciliationService;
    private final ReconciliationAutoFixService autoFixService;
    private final ReconciliationDetailRepository detailRepository;
    private final UserRepository userRepository;

    public AdminReconciliationController(ReconciliationService reconciliationService,
                                         ReconciliationAutoFixService autoFixService,
                                         ReconciliationDetailRepository detailRepository,
                                         UserRepository userRepository) {
        this.reconciliationService = reconciliationService;
        this.autoFixService = autoFixService;
        this.detailRepository = detailRepository;
        this.userRepository = userRepository;
    }

    private AppUser currentUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @PostMapping("/run")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'reconciliation:run')")
    public ReconciliationRecord runReconciliation() {
        return reconciliationService.runReconciliation();
    }

    // ============================================================
    // TEST-ONLY: Manual trigger via browser
    // ============================================================
    @GetMapping("/run-now")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public String runNow(RedirectAttributes redirect) {
        try {
            ReconciliationRecord r = reconciliationService.runReconciliation();
            redirect.addFlashAttribute("success",
                    "Reconciliation run #" + r.getId() + " completed: " + r.getStatus());
        } catch (Exception e) {
            log.error("Manual reconciliation failed", e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/admin/reconciliation";
    }

    // ============================================================
    // AUTO-FIX — MISSING_LEDGER
    // ============================================================
    @PostMapping("/details/{detailId}/auto-fix")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public String autoFix(@PathVariable Long detailId,
                          Authentication auth,
                          RedirectAttributes redirect) {
        Long recordId = recordIdFor(detailId);
        try {
            AppUser admin = currentUser(auth);
            autoFixService.repairMissingLedger(detailId, admin.getId());
            redirect.addFlashAttribute("success", "✅ Ledger entries reconstructed.");
        } catch (Exception e) {
            log.error("Auto-fix failed for detail {}", detailId, e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/admin/reconciliation/" + recordId;
    }

    // ============================================================
    // ESCALATE — AMOUNT_MISMATCH
    // ============================================================
    @PostMapping("/details/{detailId}/escalate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public String escalate(@PathVariable Long detailId,
                           Authentication auth,
                           RedirectAttributes redirect) {
        Long recordId = recordIdFor(detailId);
        try {
            AppUser admin = currentUser(auth);
            autoFixService.escalateToDlq(detailId, admin.getId());
            redirect.addFlashAttribute("success", "📮 Escalated to DLQ.");
        } catch (Exception e) {
            log.error("Escalation failed for detail {}", detailId, e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/admin/reconciliation/" + recordId;
    }

    // ============================================================
    // BULK REPAIR
    // ============================================================
    @PostMapping("/{recordId}/bulk-repair")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public String bulkRepair(@PathVariable Long recordId,
                             Authentication auth,
                             RedirectAttributes redirect) {
        try {
            AppUser admin = currentUser(auth);
            int n = autoFixService.bulkRepairMissingLedger(recordId, admin.getId());
            redirect.addFlashAttribute("success", "✅ Repaired " + n + " ledger discrepancies.");
        } catch (Exception e) {
            log.error("Bulk repair failed for record {}", recordId, e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/admin/reconciliation/" + recordId;
    }

    // ============================================================
    // REST endpoints (keep existing behaviour)
    // ============================================================
    @GetMapping("/reports")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'reconciliation:view_report')")
    public List<ReconciliationRecord> getRecentReports() {
        return reconciliationService.getRecentReports();
    }

    @GetMapping("/reports/{id}/details")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'reconciliation:view_report')")
    public List<ReconciliationDetail> getDetails(@PathVariable Long id) {
        return reconciliationService.getDetailsForRecord(id);
    }

    private Long recordIdFor(Long detailId) {
        return detailRepository.findById(detailId)
                .map(d -> d.getReconciliationRecord().getId())
                .orElse(0L);
    }
}