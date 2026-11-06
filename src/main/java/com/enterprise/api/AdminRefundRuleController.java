package com.enterprise.api;

import com.enterprise.audit.RuleAuditService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.refund.RefundRule;
import com.enterprise.refund.RefundRuleRepository;
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
import java.util.Set;

@Controller
@RequestMapping("/admin/refund-rules")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class AdminRefundRuleController {

    private static final Logger log = LoggerFactory.getLogger(AdminRefundRuleController.class);

    /** Valid values for RefundRule.action */
    private static final Set<String> VALID_ACTIONS = Set.of("ALLOW", "DENY", "REQUIRE_APPROVAL");

    private final RefundRuleRepository ruleRepository;
    private final RuleAuditService ruleAuditService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public AdminRefundRuleController(RefundRuleRepository ruleRepository,
                                     RuleAuditService ruleAuditService,
                                     UserRepository userRepository,
                                     ObjectMapper objectMapper) {
        this.ruleRepository = ruleRepository;
        this.ruleAuditService = ruleAuditService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    private AppUser getCurrentAdmin(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Admin not found"));
    }

    private static void validateAction(String action) {
        if (action == null || !VALID_ACTIONS.contains(action)) {
            throw new IllegalArgumentException(
                    "Invalid action '" + action + "'. Must be one of " + VALID_ACTIONS);
        }
    }

    @GetMapping
    public String listRules(@RequestParam(required = false) String search,
                            Model model, Authentication authentication) {
        Long tenantId = getCurrentAdmin(authentication).getTenantId();
        List<RefundRule> rules = ruleRepository.findAllByTenantId(tenantId);
        if (search != null && !search.isEmpty()) {
            String lower = search.toLowerCase();
            rules = rules.stream()
                    .filter(r -> r.getConditionExpression() != null
                            && r.getConditionExpression().toLowerCase().contains(lower))
                    .toList();
        }
        model.addAttribute("rules", rules);
        model.addAttribute("search", search);

        BigDecimal threshold = null;
        for (RefundRule rule : rules) {
            if ("ALLOW".equals(rule.getAction()) && rule.getConditionExpression().contains("amount")) {
                String[] parts = rule.getConditionExpression().replace("amount", "").trim().split("\\s+");
                if (parts.length >= 2) {
                    try { threshold = new BigDecimal(parts[1]); break; }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        model.addAttribute("threshold", threshold != null ? threshold : BigDecimal.valueOf(1000));
        return "admin/refund-rules/list";
    }

    @PostMapping("/settings")
    @Transactional
    public String saveSettings(@RequestParam BigDecimal threshold,
                               Authentication authentication,
                               RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            Long tenantId = admin.getTenantId();

            List<RefundRule> existing = ruleRepository.findAllByTenantId(tenantId);
            ruleRepository.deleteAll(existing);

            RefundRule allow = new RefundRule();
            allow.setRulePriority(1);
            allow.setConditionExpression("#amount <= " + threshold);
            allow.setAction("ALLOW");
            allow.setActive(true);
            allow.setTenantId(tenantId);
            allow = ruleRepository.save(allow);
            ruleAuditService.logChange("REFUND", allow.getId(), "CREATE", null, allow, admin.getId());

            RefundRule deny = new RefundRule();
            deny.setRulePriority(2);
            deny.setConditionExpression("#amount > " + threshold);
            deny.setAction("DENY");
            deny.setActive(true);
            deny.setTenantId(tenantId);
            deny = ruleRepository.save(deny);
            ruleAuditService.logChange("REFUND", deny.getId(), "CREATE", null, deny, admin.getId());

            redirectAttributes.addFlashAttribute("success",
                    "Refund threshold updated. Amounts up to £" + threshold + " are refundable.");
        } catch (Exception e) {
            log.error("Error saving refund threshold", e);
            redirectAttributes.addFlashAttribute("error", "Failed to save threshold: " + e.getMessage());
        }
        return "redirect:/admin/refund-rules";
    }

    @GetMapping("/create")
    public String showCreateForm(Model model) {
        model.addAttribute("rule", new RefundRule());
        return "admin/refund-rules/create";
    }

    @PostMapping
    @Transactional
    public String createRule(@ModelAttribute RefundRule rule,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            validateAction(rule.getAction());
            AppUser admin = getCurrentAdmin(authentication);
            rule.setTenantId(TenantContext.getRequiredTenantId());
            rule.setActive(true);
            RefundRule saved = ruleRepository.save(rule);
            ruleAuditService.logChange("REFUND", saved.getId(), "CREATE", null, saved, admin.getId());
            redirectAttributes.addFlashAttribute("success", "Refund rule created.");
        } catch (Exception e) {
            log.error("Error creating refund rule", e);
            redirectAttributes.addFlashAttribute("error", "Failed to create rule: " + e.getMessage());
        }
        return "redirect:/admin/refund-rules";
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable Long id, Model model) {
        RefundRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Rule not found"));
        model.addAttribute("rule", rule);
        return "admin/refund-rules/edit";
    }

    @PostMapping("/{id}")
    @Transactional
    public String updateRule(@PathVariable Long id,
                             @ModelAttribute RefundRule rule,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            validateAction(rule.getAction());
            AppUser admin = getCurrentAdmin(authentication);
            RefundRule oldRule = ruleRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Rule not found"));

            rule.setId(id);
            rule.setCreatedAt(oldRule.getCreatedAt());
            rule.setTenantId(TenantContext.getRequiredTenantId());
            RefundRule saved = ruleRepository.save(rule);
            ruleAuditService.logChange("REFUND", saved.getId(), "UPDATE", oldRule, saved, admin.getId());
            redirectAttributes.addFlashAttribute("success", "Refund rule updated.");
        } catch (Exception e) {
            log.error("Error updating refund rule", e);
            redirectAttributes.addFlashAttribute("error", "Failed to update rule: " + e.getMessage());
        }
        return "redirect:/admin/refund-rules";
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public String deleteRule(@PathVariable Long id,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            RefundRule rule = ruleRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Rule not found"));
            ruleAuditService.logChange("REFUND", id, "DELETE", rule, null, admin.getId());
            ruleRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Refund rule deleted.");
        } catch (Exception e) {
            log.error("Error deleting refund rule", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete rule: " + e.getMessage());
        }
        return "redirect:/admin/refund-rules";
    }

    @PostMapping("/{id}/toggle")
    @Transactional
    public String toggleRule(@PathVariable Long id,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            RefundRule rule = ruleRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Rule not found"));

            RefundRule oldRule = new RefundRule();
            oldRule.setId(rule.getId());
            oldRule.setRulePriority(rule.getRulePriority());
            oldRule.setConditionExpression(rule.getConditionExpression());
            oldRule.setAction(rule.getAction());
            oldRule.setRequiredPermission(rule.getRequiredPermission());
            oldRule.setActive(rule.isActive());

            rule.setActive(!rule.isActive());
            rule.setTenantId(TenantContext.getRequiredTenantId());
            RefundRule saved = ruleRepository.save(rule);
            ruleAuditService.logChange("REFUND", saved.getId(), "TOGGLE", oldRule, saved, admin.getId());
            redirectAttributes.addFlashAttribute("success",
                    rule.isActive() ? "Refund rule activated." : "Refund rule deactivated.");
        } catch (Exception e) {
            log.error("Error toggling refund rule", e);
            redirectAttributes.addFlashAttribute("error", "Failed to toggle rule: " + e.getMessage());
        }
        return "redirect:/admin/refund-rules";
    }

    @GetMapping("/export")
    @ResponseBody
    public ResponseEntity<String> exportRules(Authentication authentication) {
        AppUser admin = getCurrentAdmin(authentication);
        Long tenantId = admin.getTenantId();
        List<RefundRule> rules = ruleRepository.findAllByTenantId(tenantId);
        List<java.util.Map<String, Object>> safe = rules.stream()
                .map(r -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("rulePriority", r.getRulePriority());
                    m.put("conditionExpression", r.getConditionExpression());
                    m.put("action", r.getAction());
                    m.put("requiredPermission", r.getRequiredPermission());
                    m.put("active", r.isActive());
                    return m;
                }).toList();
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=refund-rules.json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(safe));
        } catch (JsonProcessingException e) {
            return ResponseEntity.internalServerError().body("Export failed");
        }
    }

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
                return "redirect:/admin/refund-rules";
            }

            List<RefundRule> existing = ruleRepository.findAllByTenantId(tenantId);
            ruleRepository.deleteAll(existing);

            int n = 0;
            for (RuleDto dto : imported) {
                validateAction(dto.action);
                RefundRule r = new RefundRule();
                r.setRulePriority(dto.rulePriority);
                r.setConditionExpression(dto.conditionExpression);
                r.setAction(dto.action);
                r.setRequiredPermission(dto.requiredPermission);
                r.setActive(dto.active != null ? dto.active : true);
                r.setTenantId(tenantId);
                r.setCreatedAt(LocalDateTime.now());
                ruleRepository.save(r);
                n++;
            }
            redirectAttributes.addFlashAttribute("success", "Imported " + n + " refund rules.");
        } catch (Exception e) {
            log.error("Import failed", e);
            redirectAttributes.addFlashAttribute("error", "Import failed: " + e.getMessage());
        }
        return "redirect:/admin/refund-rules";
    }

    public static class RuleDto {
        public int rulePriority;
        public String conditionExpression;
        public String action;
        public String requiredPermission;
        public Boolean active;
    }
}