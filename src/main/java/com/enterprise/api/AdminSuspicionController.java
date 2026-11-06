package com.enterprise.api;

import com.enterprise.audit.RuleAuditService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.invoice.SuspicionRule;
import com.enterprise.invoice.SuspicionRuleRepository;
import com.enterprise.invoice.SuspicionService;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Controller
@RequestMapping("/admin/suspicion-rules")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class AdminSuspicionController {

    private static final Logger log = LoggerFactory.getLogger(AdminSuspicionController.class);

    private static final Set<String> VALID_RISK_LEVELS = Set.of("GREEN", "YELLOW", "RED");

    private final SuspicionRuleRepository ruleRepository;
    private final SuspicionService suspicionService;
    private final RuleAuditService ruleAuditService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public AdminSuspicionController(SuspicionRuleRepository ruleRepository,
                                    SuspicionService suspicionService,
                                    RuleAuditService ruleAuditService,
                                    UserRepository userRepository,
                                    ObjectMapper objectMapper) {
        this.ruleRepository = ruleRepository;
        this.suspicionService = suspicionService;
        this.ruleAuditService = ruleAuditService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    private AppUser getCurrentAdmin(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Admin not found"));
    }

    private static void validateRisk(String risk) {
        if (risk == null || !VALID_RISK_LEVELS.contains(risk)) {
            throw new IllegalArgumentException("Invalid risk level '" + risk + "'");
        }
    }

    /**
     * ✅ Build the SpEL expression safely. The keyword goes through a
     *    String-escaped regex — no raw SpEL injection possible.
     */
    private static String buildKeywordExpression(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("keyword is required");
        }
        // Escape regex meta-characters so the keyword is treated literally
        String escaped = java.util.regex.Pattern.quote(keyword.trim().toLowerCase());
        return "#description != null and #description.toLowerCase() matches \".*" + escaped + ".*\"";
    }

    @GetMapping
    public String listRules(Model model, Authentication authentication) {
        Long tenantId = getCurrentAdmin(authentication).getTenantId();
        List<SuspicionRule> rules = ruleRepository.findAllByTenantId(tenantId);
        model.addAttribute("rules", rules);
        return "admin/suspicion-rules/list";
    }

    @GetMapping("/create")
    public String showCreateForm(Model model) {
        model.addAttribute("rule", new SuspicionRule());
        return "admin/suspicion-rules/create";
    }

    @PostMapping
    @Transactional
    public String createRule(@RequestParam String keyword,
                             @RequestParam String riskLevel,
                             @RequestParam(required = false) String description,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            validateRisk(riskLevel);
            AppUser admin = getCurrentAdmin(authentication);

            SuspicionRule rule = new SuspicionRule();
            rule.setPriority(100);
            rule.setConditionExpression(buildKeywordExpression(keyword));
            rule.setRiskLevel(riskLevel);
            rule.setDescription(description != null ? description : "Keyword: " + keyword);
            rule.setActive(true);
            rule.setTenantId(TenantContext.getRequiredTenantId());
            SuspicionRule saved = ruleRepository.save(rule);
            ruleAuditService.logChange("SUSPICION", saved.getId(), "CREATE", null, saved, admin.getId());

            suspicionService.reEvaluateAllInvoices();
            redirectAttributes.addFlashAttribute("success", "Suspicion rule created.");
        } catch (Exception e) {
            log.error("Error creating suspicion rule", e);
            redirectAttributes.addFlashAttribute("error", "Failed to create rule: " + e.getMessage());
        }
        return "redirect:/admin/suspicion-rules";
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable Long id, Model model) {
        SuspicionRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Rule not found"));
        model.addAttribute("rule", rule);
        return "admin/suspicion-rules/edit";
    }

    @PostMapping("/{id}")
    @Transactional
    public String updateRule(@PathVariable Long id,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) String conditionExpression,
                             @RequestParam String riskLevel,
                             @RequestParam(required = false) String description,
                             @RequestParam(required = false) Boolean active,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            validateRisk(riskLevel);
            AppUser admin = getCurrentAdmin(authentication);
            SuspicionRule rule = ruleRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Rule not found"));

            SuspicionRule oldRule = new SuspicionRule();
            oldRule.setId(rule.getId());
            oldRule.setPriority(rule.getPriority());
            oldRule.setConditionExpression(rule.getConditionExpression());
            oldRule.setRiskLevel(rule.getRiskLevel());
            oldRule.setDescription(rule.getDescription());
            oldRule.setActive(rule.isActive());

            if (keyword != null && !keyword.trim().isEmpty()) {
                rule.setConditionExpression(buildKeywordExpression(keyword));
            } else if (conditionExpression != null && !conditionExpression.trim().isEmpty()) {
                rule.setConditionExpression(conditionExpression);
            }

            rule.setRiskLevel(riskLevel);
            rule.setDescription(description);
            if (active != null) rule.setActive(active);
            rule.setTenantId(TenantContext.getRequiredTenantId());
            SuspicionRule saved = ruleRepository.save(rule);
            ruleAuditService.logChange("SUSPICION", saved.getId(), "UPDATE", oldRule, saved, admin.getId());

            suspicionService.reEvaluateAllInvoices();
            redirectAttributes.addFlashAttribute("success", "Suspicion rule updated.");
        } catch (Exception e) {
            log.error("Error updating suspicion rule", e);
            redirectAttributes.addFlashAttribute("error", "Failed to update rule: " + e.getMessage());
        }
        return "redirect:/admin/suspicion-rules";
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public String deleteRule(@PathVariable Long id,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            SuspicionRule rule = ruleRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Rule not found"));
            ruleAuditService.logChange("SUSPICION", id, "DELETE", rule, null, admin.getId());
            ruleRepository.deleteById(id);
            suspicionService.reEvaluateAllInvoices();
            redirectAttributes.addFlashAttribute("success", "Suspicion rule deleted.");
        } catch (Exception e) {
            log.error("Error deleting suspicion rule", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete rule: " + e.getMessage());
        }
        return "redirect:/admin/suspicion-rules";
    }

    @PostMapping("/{id}/toggle")
    @Transactional
    public String toggleRule(@PathVariable Long id,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            SuspicionRule rule = ruleRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Rule not found"));

            SuspicionRule oldRule = new SuspicionRule();
            oldRule.setId(rule.getId());
            oldRule.setPriority(rule.getPriority());
            oldRule.setConditionExpression(rule.getConditionExpression());
            oldRule.setRiskLevel(rule.getRiskLevel());
            oldRule.setDescription(rule.getDescription());
            oldRule.setActive(rule.isActive());

            rule.setActive(!rule.isActive());
            rule.setTenantId(TenantContext.getRequiredTenantId());
            SuspicionRule saved = ruleRepository.save(rule);
            ruleAuditService.logChange("SUSPICION", saved.getId(), "TOGGLE", oldRule, saved, admin.getId());

            suspicionService.reEvaluateAllInvoices();
            redirectAttributes.addFlashAttribute("success",
                    rule.isActive() ? "Rule activated." : "Rule deactivated.");
        } catch (Exception e) {
            log.error("Error toggling suspicion rule", e);
            redirectAttributes.addFlashAttribute("error", "Failed to toggle rule: " + e.getMessage());
        }
        return "redirect:/admin/suspicion-rules";
    }

    @GetMapping("/export")
    @ResponseBody
    public ResponseEntity<String> exportRules(Authentication authentication) {
        AppUser admin = getCurrentAdmin(authentication);
        List<SuspicionRule> rules = ruleRepository.findAllByTenantId(admin.getTenantId());
        List<java.util.Map<String, Object>> safe = rules.stream()
                .map(r -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("priority", r.getPriority());
                    m.put("conditionExpression", r.getConditionExpression());
                    m.put("riskLevel", r.getRiskLevel());
                    m.put("description", r.getDescription());
                    m.put("active", r.isActive());
                    return m;
                }).toList();
        try {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=suspicion-rules.json")
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
                return "redirect:/admin/suspicion-rules";
            }

            List<SuspicionRule> existing = ruleRepository.findAllByTenantId(tenantId);
            ruleRepository.deleteAll(existing);

            int n = 0;
            for (RuleDto dto : imported) {
                validateRisk(dto.riskLevel);
                SuspicionRule r = new SuspicionRule();
                r.setPriority(dto.priority);
                r.setConditionExpression(dto.conditionExpression);
                r.setRiskLevel(dto.riskLevel);
                r.setDescription(dto.description);
                r.setActive(dto.active != null ? dto.active : true);
                r.setTenantId(tenantId);
                r.setCreatedAt(LocalDateTime.now());
                ruleRepository.save(r);
                n++;
            }
            suspicionService.reEvaluateAllInvoices();
            redirectAttributes.addFlashAttribute("success", "Imported " + n + " suspicion rules.");
        } catch (Exception e) {
            log.error("Import failed", e);
            redirectAttributes.addFlashAttribute("error", "Import failed: " + e.getMessage());
        }
        return "redirect:/admin/suspicion-rules";
    }

    public static class RuleDto {
        public int priority;
        public String conditionExpression;
        public String riskLevel;
        public String description;
        public Boolean active;
    }
}