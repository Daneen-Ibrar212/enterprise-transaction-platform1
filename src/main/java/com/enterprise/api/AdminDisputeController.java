package com.enterprise.api;

import com.enterprise.dispute.*;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.transaction.Transaction;
import com.enterprise.transaction.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/disputes")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class AdminDisputeController {

    private static final Logger log = LoggerFactory.getLogger(AdminDisputeController.class);

    private final DisputeService disputeService;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    public AdminDisputeController(DisputeService disputeService,
                                  TransactionRepository transactionRepository,
                                  UserRepository userRepository) {
        this.disputeService = disputeService;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
    }

    private AppUser currentUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ============================================================
    // LIST
    // ============================================================
    @GetMapping
    public String list(@RequestParam(required = false) String status,
                       Authentication auth,
                       Model model) {
        AppUser me = currentUser(auth);
        boolean isSuperAdmin = me.isSuperAdmin();

        List<Dispute> all = disputeService.listForTenant();

        List<Dispute> filtered = (status == null || status.isEmpty())
                ? all
                : all.stream()
                    .filter(d -> d.getStatus().name().equals(status))
                    .collect(Collectors.toList());

        long openCount = all.stream().filter(d -> d.getStatus().isOpen()).count();

        model.addAttribute("disputes", filtered);
        model.addAttribute("statusFilter", status);
        model.addAttribute("openCount", openCount);
        model.addAttribute("statuses", DisputeStatus.values());
        model.addAttribute("isSuperAdmin", isSuperAdmin);
        return "admin/disputes/list";
    }

    // ============================================================
    // DETAIL
    // ============================================================
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Dispute dispute = disputeService.get(id);
        Transaction tx = transactionRepository.findById(dispute.getTransactionId())
                .orElse(null);
        List<DisputeEvidence> evidence = disputeService.getEvidence(id);

        model.addAttribute("dispute", dispute);
        model.addAttribute("transaction", tx);
        model.addAttribute("evidence", evidence);
        model.addAttribute("statuses", DisputeStatus.values());
        model.addAttribute("evidenceTypes",
                List.of("RECEIPT", "SHIPPING", "COMMUNICATION", "REFUND_POLICY", "OTHER"));
        return "admin/disputes/detail";
    }

    // ============================================================
    // MANUAL OPEN
    // ============================================================
    @GetMapping("/new")
    public String newDisputeForm(@RequestParam(required = false) Long transactionId,
                                 Model model) {
        model.addAttribute("transactionId", transactionId);
        model.addAttribute("reasons", DisputeReason.values());
        return "admin/disputes/new";
    }

    @PostMapping("/new")
    public String createDispute(@RequestParam Long transactionId,
                                @RequestParam DisputeReason reason,
                                @RequestParam(required = false) String source,
                                @RequestParam(defaultValue = "7") Integer respondDays,
                                RedirectAttributes redirect) {
        try {
            Dispute d = disputeService.create(transactionId, reason, source, respondDays);
            redirect.addFlashAttribute("success", "Dispute #" + d.getId() + " created.");
            return "redirect:/admin/disputes/" + d.getId();
        } catch (Exception e) {
            log.error("Failed to create dispute", e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
            return "redirect:/admin/disputes/new?transactionId=" + transactionId;
        }
    }

    // ============================================================
    // STATUS UPDATE
    // ============================================================
    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable Long id,
                               @RequestParam DisputeStatus status,
                               @RequestParam(required = false) String notes,
                               RedirectAttributes redirect) {
        try {
            disputeService.updateStatus(id, status, notes);
            redirect.addFlashAttribute("success", "Dispute updated to " + status + ".");
        } catch (Exception e) {
            log.error("Failed to update dispute {}", id, e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/admin/disputes/" + id;
    }

    // ============================================================
    // ADD EVIDENCE
    // ============================================================
    @PostMapping("/{id}/evidence")
    public String addEvidence(@PathVariable Long id,
                              @RequestParam String evidenceType,
                              @RequestParam(required = false) String description,
                              @RequestParam(required = false) MultipartFile file,
                              Authentication auth,
                              RedirectAttributes redirect) {
        try {
            AppUser admin = currentUser(auth);

            String filename = null;
            String contentType = null;
            byte[] content = null;

            if (file != null && !file.isEmpty()) {
                filename = file.getOriginalFilename();
                contentType = file.getContentType();
                content = file.getBytes();
            }

            disputeService.addEvidence(id, evidenceType, description,
                    filename, contentType, content, admin.getId());

            redirect.addFlashAttribute("success", "Evidence added.");
        } catch (Exception e) {
            log.error("Failed to add evidence to dispute {}", id, e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/admin/disputes/" + id;
    }
}