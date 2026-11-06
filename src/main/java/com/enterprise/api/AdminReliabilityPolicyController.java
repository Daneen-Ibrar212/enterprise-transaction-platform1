package com.enterprise.api;

import com.enterprise.reliability.*;
import com.enterprise.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;

@Controller
@RequestMapping("/admin/reliability/policies")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminReliabilityPolicyController {

    private final RetryPolicyRepository retryPolicyRepository;
    private final CircuitBreakerPolicyRepository circuitBreakerPolicyRepository;
    private final FailureClassificationRuleRepository failureClassificationRuleRepository;

    public AdminReliabilityPolicyController(RetryPolicyRepository retryPolicyRepository,
                                            CircuitBreakerPolicyRepository circuitBreakerPolicyRepository,
                                            FailureClassificationRuleRepository failureClassificationRuleRepository) {
        this.retryPolicyRepository = retryPolicyRepository;
        this.circuitBreakerPolicyRepository = circuitBreakerPolicyRepository;
        this.failureClassificationRuleRepository = failureClassificationRuleRepository;
    }

    // ===================== RETRY POLICIES =====================
    @GetMapping("/retry")
    public String listRetryPolicies(Model model) {
        model.addAttribute("policies", retryPolicyRepository.findAll());
        return "admin/reliability/policies/retry/list";
    }

    @GetMapping("/retry/create")
    public String showCreateRetryForm(Model model) {
        model.addAttribute("policy", new RetryPolicy());
        return "admin/reliability/policies/retry/form";
    }

    @PostMapping("/retry")
    public String createRetryPolicy(@ModelAttribute RetryPolicy policy, RedirectAttributes redirectAttributes) {
        try {
            policy.setCreatedAt(LocalDateTime.now());
            policy.setUpdatedAt(LocalDateTime.now());
            // ✅ Set tenant ID
            policy.setTenantId(TenantContext.getRequiredTenantId());
            retryPolicyRepository.save(policy);
            redirectAttributes.addFlashAttribute("success", "✅ Retry policy created successfully.");
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage().contains("duplicate key") || e.getMessage().contains("unique constraint")) {
                redirectAttributes.addFlashAttribute("error", "❌ A retry policy with operation type '" + policy.getOperationType() + "' already exists. Please use Edit instead.");
            } else {
                redirectAttributes.addFlashAttribute("error", "❌ Failed to create retry policy: " + e.getMessage());
            }
            return "redirect:/admin/reliability/policies/retry/create";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ Failed to create retry policy: " + e.getMessage());
            return "redirect:/admin/reliability/policies/retry/create";
        }
        return "redirect:/admin/reliability/policies/retry";
    }

    @GetMapping("/retry/{id}/edit")
    public String showEditRetryForm(@PathVariable Long id, Model model) {
        RetryPolicy policy = retryPolicyRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Retry policy not found"));
        model.addAttribute("policy", policy);
        return "admin/reliability/policies/retry/form";
    }

    @PostMapping("/retry/{id}")
    public String updateRetryPolicy(@PathVariable Long id,
                                    @ModelAttribute RetryPolicy policy,
                                    RedirectAttributes redirectAttributes) {
        try {
            RetryPolicy existing = retryPolicyRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Retry policy not found"));
            existing.setOperationType(policy.getOperationType());
            existing.setMaxAttempts(policy.getMaxAttempts());
            existing.setBackoffStrategy(policy.getBackoffStrategy());
            existing.setBaseDelayMs(policy.getBaseDelayMs());
            existing.setMaxDelayMs(policy.getMaxDelayMs());
            existing.setJitterEnabled(policy.isJitterEnabled());
            existing.setActive(policy.isActive());
            existing.setUpdatedAt(LocalDateTime.now());
            // ✅ Set tenant ID (preserve existing)
            existing.setTenantId(TenantContext.getRequiredTenantId());
            retryPolicyRepository.save(existing);
            redirectAttributes.addFlashAttribute("success", "✅ Retry policy updated successfully.");
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage().contains("duplicate key") || e.getMessage().contains("unique constraint")) {
                redirectAttributes.addFlashAttribute("error", "❌ Operation type '" + policy.getOperationType() + "' already exists on another policy.");
            } else {
                redirectAttributes.addFlashAttribute("error", "❌ Failed to update retry policy: " + e.getMessage());
            }
            return "redirect:/admin/reliability/policies/retry/" + id + "/edit";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ Failed to update retry policy: " + e.getMessage());
            return "redirect:/admin/reliability/policies/retry/" + id + "/edit";
        }
        return "redirect:/admin/reliability/policies/retry";
    }

    @PostMapping("/retry/{id}/toggle")
    public String toggleRetryPolicy(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            RetryPolicy policy = retryPolicyRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Retry policy not found"));
            policy.setActive(!policy.isActive());
            policy.setUpdatedAt(LocalDateTime.now());
            retryPolicyRepository.save(policy);
            redirectAttributes.addFlashAttribute("success", "✅ Retry policy toggled.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ Failed to toggle retry policy: " + e.getMessage());
        }
        return "redirect:/admin/reliability/policies/retry";
    }

    @PostMapping("/retry/{id}/delete")
    public String deleteRetryPolicy(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            retryPolicyRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "✅ Retry policy deleted.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ Failed to delete retry policy: " + e.getMessage());
        }
        return "redirect:/admin/reliability/policies/retry";
    }

    // ===================== CIRCUIT BREAKER POLICIES =====================
    @GetMapping("/circuit-breaker")
    public String listCircuitBreakerPolicies(Model model) {
        model.addAttribute("policies", circuitBreakerPolicyRepository.findAll());
        return "admin/reliability/policies/circuit-breaker/list";
    }

    @GetMapping("/circuit-breaker/create")
    public String showCreateCircuitBreakerForm(Model model) {
        model.addAttribute("policy", new CircuitBreakerPolicy());
        return "admin/reliability/policies/circuit-breaker/form";
    }

    @PostMapping("/circuit-breaker")
    public String createCircuitBreakerPolicy(@ModelAttribute CircuitBreakerPolicy policy, RedirectAttributes redirectAttributes) {
        try {
            policy.setCreatedAt(LocalDateTime.now());
            policy.setUpdatedAt(LocalDateTime.now());
            // ✅ Set tenant ID
            policy.setTenantId(TenantContext.getRequiredTenantId());
            circuitBreakerPolicyRepository.save(policy);
            redirectAttributes.addFlashAttribute("success", "✅ Circuit breaker policy created successfully.");
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage().contains("duplicate key") || e.getMessage().contains("unique constraint")) {
                redirectAttributes.addFlashAttribute("error", "❌ A circuit breaker policy with operation type '" + policy.getOperationType() + "' already exists. Please use Edit instead.");
            } else {
                redirectAttributes.addFlashAttribute("error", "❌ Failed to create circuit breaker policy: " + e.getMessage());
            }
            return "redirect:/admin/reliability/policies/circuit-breaker/create";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ Failed to create circuit breaker policy: " + e.getMessage());
            return "redirect:/admin/reliability/policies/circuit-breaker/create";
        }
        return "redirect:/admin/reliability/policies/circuit-breaker";
    }

    @GetMapping("/circuit-breaker/{id}/edit")
    public String showEditCircuitBreakerForm(@PathVariable Long id, Model model) {
        CircuitBreakerPolicy policy = circuitBreakerPolicyRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Circuit breaker policy not found"));
        model.addAttribute("policy", policy);
        return "admin/reliability/policies/circuit-breaker/form";
    }

    @PostMapping("/circuit-breaker/{id}")
    public String updateCircuitBreakerPolicy(@PathVariable Long id,
                                             @ModelAttribute CircuitBreakerPolicy policy,
                                             RedirectAttributes redirectAttributes) {
        try {
            CircuitBreakerPolicy existing = circuitBreakerPolicyRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Circuit breaker policy not found"));
            existing.setOperationType(policy.getOperationType());
            existing.setFailureThreshold(policy.getFailureThreshold());
            existing.setSuccessThreshold(policy.getSuccessThreshold());
            existing.setTimeoutMs(policy.getTimeoutMs());
            existing.setEvaluationWindowSec(policy.getEvaluationWindowSec());
            existing.setActive(policy.isActive());
            existing.setUpdatedAt(LocalDateTime.now());
            // ✅ Set tenant ID
            existing.setTenantId(TenantContext.getRequiredTenantId());
            circuitBreakerPolicyRepository.save(existing);
            redirectAttributes.addFlashAttribute("success", "✅ Circuit breaker policy updated successfully.");
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage().contains("duplicate key") || e.getMessage().contains("unique constraint")) {
                redirectAttributes.addFlashAttribute("error", "❌ Operation type '" + policy.getOperationType() + "' already exists on another policy.");
            } else {
                redirectAttributes.addFlashAttribute("error", "❌ Failed to update circuit breaker policy: " + e.getMessage());
            }
            return "redirect:/admin/reliability/policies/circuit-breaker/" + id + "/edit";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ Failed to update circuit breaker policy: " + e.getMessage());
            return "redirect:/admin/reliability/policies/circuit-breaker/" + id + "/edit";
        }
        return "redirect:/admin/reliability/policies/circuit-breaker";
    }

    @PostMapping("/circuit-breaker/{id}/toggle")
    public String toggleCircuitBreakerPolicy(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            CircuitBreakerPolicy policy = circuitBreakerPolicyRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Circuit breaker policy not found"));
            policy.setActive(!policy.isActive());
            policy.setUpdatedAt(LocalDateTime.now());
            circuitBreakerPolicyRepository.save(policy);
            redirectAttributes.addFlashAttribute("success", "✅ Circuit breaker policy toggled.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ Failed to toggle circuit breaker policy: " + e.getMessage());
        }
        return "redirect:/admin/reliability/policies/circuit-breaker";
    }

    @PostMapping("/circuit-breaker/{id}/delete")
    public String deleteCircuitBreakerPolicy(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            circuitBreakerPolicyRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "✅ Circuit breaker policy deleted.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ Failed to delete circuit breaker policy: " + e.getMessage());
        }
        return "redirect:/admin/reliability/policies/circuit-breaker";
    }

    // ===================== FAILURE CLASSIFICATION RULES =====================
    @GetMapping("/failure-classification")
    public String listFailureClassificationRules(Model model) {
        model.addAttribute("rules", failureClassificationRuleRepository.findAll());
        return "admin/reliability/policies/failure-classification/list";
    }

    @GetMapping("/failure-classification/create")
    public String showCreateFailureClassificationForm(Model model) {
        model.addAttribute("rule", new FailureClassificationRule());
        return "admin/reliability/policies/failure-classification/form";
    }

 @PostMapping("/failure-classification")
public String createFailureClassificationRule(@ModelAttribute FailureClassificationRule rule,
                                               RedirectAttributes redirectAttributes) {
    try {
        rule.setCreatedAt(LocalDateTime.now());
        // ✅ REQUIRED: FailureClassificationRule.tenantId is NOT NULL.
        rule.setTenantId(com.enterprise.tenant.TenantContext.getRequiredTenantId());
        failureClassificationRuleRepository.save(rule);
        redirectAttributes.addFlashAttribute("success", "✅ Failure classification rule created.");
    } catch (Exception e) {
        redirectAttributes.addFlashAttribute("error", "❌ Failed to create rule: " + e.getMessage());
    }
    return "redirect:/admin/reliability/policies/failure-classification";
}

    @GetMapping("/failure-classification/{id}/edit")
    public String showEditFailureClassificationForm(@PathVariable Long id, Model model) {
        FailureClassificationRule rule = failureClassificationRuleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Rule not found"));
        model.addAttribute("rule", rule);
        return "admin/reliability/policies/failure-classification/form";
    }

    @PostMapping("/failure-classification/{id}")
    public String updateFailureClassificationRule(@PathVariable Long id,
                                                  @ModelAttribute FailureClassificationRule rule,
                                                  RedirectAttributes redirectAttributes) {
        try {
            FailureClassificationRule existing = failureClassificationRuleRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Rule not found"));
            existing.setConditionExpression(rule.getConditionExpression());
            existing.setCategory(rule.getCategory());
            existing.setSeverity(rule.getSeverity());
            existing.setAction(rule.getAction());
            existing.setPriority(rule.getPriority());
            existing.setActive(rule.isActive());
            failureClassificationRuleRepository.save(existing);
            redirectAttributes.addFlashAttribute("success", "✅ Rule updated.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ Failed to update rule: " + e.getMessage());
            return "redirect:/admin/reliability/policies/failure-classification/" + id + "/edit";
        }
        return "redirect:/admin/reliability/policies/failure-classification";
    }

    @PostMapping("/failure-classification/{id}/toggle")
    public String toggleFailureClassificationRule(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            FailureClassificationRule rule = failureClassificationRuleRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Rule not found"));
            rule.setActive(!rule.isActive());
            failureClassificationRuleRepository.save(rule);
            redirectAttributes.addFlashAttribute("success", "✅ Rule toggled.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ Failed to toggle rule: " + e.getMessage());
        }
        return "redirect:/admin/reliability/policies/failure-classification";
    }

    @PostMapping("/failure-classification/{id}/delete")
    public String deleteFailureClassificationRule(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            failureClassificationRuleRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "✅ Rule deleted.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "❌ Failed to delete rule: " + e.getMessage());
        }
        return "redirect:/admin/reliability/policies/failure-classification";
    }
}