package com.enterprise.api;

import com.enterprise.audit.RuleAuditService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.invoice.ApprovalRule;
import com.enterprise.invoice.ApprovalRuleRepository;
import com.enterprise.invoice.InvoiceService;
import com.enterprise.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@Controller
@RequestMapping("/admin/approval-rules")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class AdminApprovalController {

    private static final Logger log = LoggerFactory.getLogger(AdminApprovalController.class);

    private final ApprovalRuleRepository ruleRepository;
    private final InvoiceService invoiceService;
    private final RuleAuditService ruleAuditService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public AdminApprovalController(ApprovalRuleRepository ruleRepository,
                                   InvoiceService invoiceService,
                                   RuleAuditService ruleAuditService,
                                   UserRepository userRepository,
                                   ObjectMapper objectMapper) {
        this.ruleRepository = ruleRepository;
        this.invoiceService = invoiceService;
        this.ruleAuditService = ruleAuditService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    private AppUser getCurrentAdmin(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Admin not found"));
    }

    @GetMapping
    public String index(@RequestParam(required = false) String search,
                        Model model, Authentication authentication) {
        Long tenantId = getCurrentAdmin(authentication).getTenantId();
        List<ApprovalRule> rules = ruleRepository.findAllByTenantId(tenantId);
        if (search != null && !search.isEmpty()) {
            String lower = search.toLowerCase();
            rules = rules.stream()
                    .filter(r -> r.getDescription() != null && r.getDescription().toLowerCase().contains(lower))
                    .toList();
        }
        model.addAttribute("rules", rules);
        model.addAttribute("search", search);

        BigDecimal threshold = null;
        for (ApprovalRule rule : rules) {
            if (!rule.isRequiresApproval() && rule.getConditionExpression().contains("#amount")) {
                String[] parts = rule.getConditionExpression().replace("#amount", "").trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        threshold = new BigDecimal(parts[1]);
                        break;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        model.addAttribute("threshold", threshold != null ? threshold : BigDecimal.valueOf(10000));
        return "admin/approval-rules/index";
    }

    @PostMapping("/settings")
    @Transactional
    public String saveSettings(@RequestParam BigDecimal threshold,
                               Authentication authentication,
                               RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            Long tenantId = admin.getTenantId();

            List<ApprovalRule> existing = ruleRepository.findAllByTenantId(tenantId);
            ruleRepository.deleteAll(existing);

            ApprovalRule autoRule = new ApprovalRule();
            autoRule.setPriority(1);
            autoRule.setConditionExpression("#amount <= " + threshold);
            autoRule.setRequiresApproval(false);
            autoRule.setDescription("Amount £" + threshold + " or less – auto-approved");
            autoRule.setActive(true);
            autoRule.setTenantId(tenantId);
            autoRule = ruleRepository.save(autoRule);
            ruleAuditService.logChange("APPROVAL", autoRule.getId(), "CREATE", null, autoRule, admin.getId());

            ApprovalRule requireRule = new ApprovalRule();
            requireRule.setPriority(2);
            requireRule.setConditionExpression("#amount > " + threshold);
            requireRule.setRequiresApproval(true);
            requireRule.setDescription("Amount over £" + threshold + " – requires approval");
            requireRule.setActive(true);
            requireRule.setTenantId(tenantId);
            requireRule = ruleRepository.save(requireRule);
            ruleAuditService.logChange("APPROVAL", requireRule.getId(), "CREATE", null, requireRule, admin.getId());

            invoiceService.reEvaluateAllInvoices();
            redirectAttributes.addFlashAttribute("success", "Threshold updated and all invoices re-evaluated.");
        } catch (Exception e) {
            log.error("Error saving threshold", e);
            redirectAttributes.addFlashAttribute("error", "Failed to save threshold: " + e.getMessage());
        }
        return "redirect:/admin/approval-rules";
    }

    @GetMapping("/create")
    public String showCreateForm(Model model) {
        model.addAttribute("rule", new ApprovalRule());
        return "admin/approval-rules/create";
    }

    @PostMapping
    @Transactional
    public String createRule(@ModelAttribute ApprovalRule rule,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            rule.setTenantId(TenantContext.getRequiredTenantId());
            rule.setActive(true);
            ApprovalRule saved = ruleRepository.save(rule);
            ruleAuditService.logChange("APPROVAL", saved.getId(), "CREATE", null, saved, admin.getId());
            invoiceService.reEvaluateAllInvoices();
            redirectAttributes.addFlashAttribute("success", "Rule created and all invoices re-evaluated.");
        } catch (Exception e) {
            log.error("Error creating rule", e);
            redirectAttributes.addFlashAttribute("error", "Failed to create rule: " + e.getMessage());
        }
        return "redirect:/admin/approval-rules";
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable Long id, Model model) {
        ApprovalRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Rule not found"));
        model.addAttribute("rule", rule);
        return "admin/approval-rules/edit";
    }

    @PostMapping("/{id}")
    @Transactional
    public String updateRule(@PathVariable Long id,
                             @ModelAttribute ApprovalRule rule,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            ApprovalRule oldRule = ruleRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Rule not found"));

            rule.setId(id);
            rule.setCreatedAt(oldRule.getCreatedAt());
            rule.setTenantId(TenantContext.getRequiredTenantId());
            ApprovalRule saved = ruleRepository.save(rule);
            ruleAuditService.logChange("APPROVAL", saved.getId(), "UPDATE", oldRule, saved, admin.getId());
            invoiceService.reEvaluateAllInvoices();
            redirectAttributes.addFlashAttribute("success", "Rule updated and all invoices re-evaluated.");
        } catch (Exception e) {
            log.error("Error updating rule", e);
            redirectAttributes.addFlashAttribute("error", "Failed to update rule: " + e.getMessage());
        }
        return "redirect:/admin/approval-rules";
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public String deleteRule(@PathVariable Long id,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            ApprovalRule rule = ruleRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Rule not found"));
            ruleAuditService.logChange("APPROVAL", id, "DELETE", rule, null, admin.getId());
            ruleRepository.deleteById(id);
            invoiceService.reEvaluateAllInvoices();
            redirectAttributes.addFlashAttribute("success", "Rule deleted and all invoices re-evaluated.");
        } catch (Exception e) {
            log.error("Error deleting rule", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete rule: " + e.getMessage());
        }
        return "redirect:/admin/approval-rules";
    }

    @PostMapping("/{id}/toggle")
    @Transactional
    public String toggleRule(@PathVariable Long id,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            ApprovalRule rule = ruleRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Rule not found"));

            ApprovalRule oldRule = new ApprovalRule();
            oldRule.setId(rule.getId());
            oldRule.setPriority(rule.getPriority());
            oldRule.setConditionExpression(rule.getConditionExpression());
            oldRule.setRequiresApproval(rule.isRequiresApproval());
            oldRule.setDescription(rule.getDescription());
            oldRule.setActive(rule.isActive());

            rule.setActive(!rule.isActive());
            rule.setTenantId(TenantContext.getRequiredTenantId());
            ApprovalRule saved = ruleRepository.save(rule);
            ruleAuditService.logChange("APPROVAL", saved.getId(), "TOGGLE", oldRule, saved, admin.getId());
            invoiceService.reEvaluateAllInvoices();
            redirectAttributes.addFlashAttribute("success",
                    rule.isActive() ? "Rule activated." : "Rule deactivated.");
        } catch (Exception e) {
            log.error("Error toggling rule", e);
            redirectAttributes.addFlashAttribute("error", "Failed to toggle rule: " + e.getMessage());
        }
        return "redirect:/admin/approval-rules";
    }

    // ============================================================
    // EXPORT — clean JSON of only business fields
    // ============================================================
    @GetMapping("/export")
    @ResponseBody
    public ResponseEntity<String> exportRules(Authentication authentication) {
        AppUser admin = getCurrentAdmin(authentication);
        Long tenantId = admin.getTenantId();
        List<ApprovalRule> rules = ruleRepository.findAllByTenantId(tenantId);

        // ✅ Serialize only the fields that should round-trip
        List<java.util.Map<String, Object>> safe = rules.stream()
                .map(r -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("priority", r.getPriority());
                    m.put("conditionExpression", r.getConditionExpression());
                    m.put("requiresApproval", r.isRequiresApproval());
                    m.put("description", r.getDescription());
                    m.put("active", r.isActive());
                    return m;
                })
                .toList();
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=approval-rules.json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(safe));
        } catch (JsonProcessingException e) {
            return ResponseEntity.internalServerError().body("Export failed");
        }
    }

    // ============================================================
    // IMPORT — read into DTO, then construct entities with explicit fields.
    //          NEVER bind user JSON directly into @Entity (mass assignment).
    // ============================================================
    @PostMapping("/import")
    @Transactional
    public String importRules(@RequestParam("file") MultipartFile file,
                              Authentication authentication,
                              RedirectAttributes redirectAttributes) {
        AppUser admin = getCurrentAdmin(authentication);
        Long tenantId = admin.getTenantId();
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            List<RuleDto> imported = objectMapper.readValue(content, new TypeReference<List<RuleDto>>() {});
            if (imported == null || imported.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Import file is empty");
                return "redirect:/admin/approval-rules";
            }

            List<ApprovalRule> existing = ruleRepository.findAllByTenantId(tenantId);
            ruleRepository.deleteAll(existing);

            int n = 0;
            for (RuleDto dto : imported) {
                ApprovalRule r = new ApprovalRule();
                r.setPriority(dto.priority);
                r.setConditionExpression(dto.conditionExpression);
                r.setRequiresApproval(dto.requiresApproval);
                r.setDescription(dto.description);
                r.setActive(dto.active != null ? dto.active : true);
                r.setTenantId(tenantId);
                r.setCreatedAt(LocalDateTime.now());
                ruleRepository.save(r);
                n++;
            }
            invoiceService.reEvaluateAllInvoices();
            redirectAttributes.addFlashAttribute("success", "Imported " + n + " approval rules.");
        } catch (Exception e) {
            log.error("Import failed", e);
            redirectAttributes.addFlashAttribute("error", "Import failed: " + e.getMessage());
        }
        return "redirect:/admin/approval-rules";
    }

    /** ✅ DTO restricts what a JSON file can set. No `id`, `version`, `tenantId`, `createdAt`. */
    public static class RuleDto {
        public int priority;
        public String conditionExpression;
        public boolean requiresApproval;
        public String description;
        public Boolean active;
    }
}