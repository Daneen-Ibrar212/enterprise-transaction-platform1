package com.enterprise.api;

import com.enterprise.reliability.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/admin/reliability")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminReliabilityController {

    private final DlqEntryRepository dlqEntryRepository;
    private final RetryPolicyRepository retryPolicyRepository;
    private final CircuitBreakerPolicyRepository circuitBreakerPolicyRepository;
    private final DlqProcessorService dlqProcessorService;
    private final DlqScheduler dlqScheduler;

    public AdminReliabilityController(DlqEntryRepository dlqEntryRepository,
                                      RetryPolicyRepository retryPolicyRepository,
                                      CircuitBreakerPolicyRepository circuitBreakerPolicyRepository,
                                      DlqProcessorService dlqProcessorService,
                                      DlqScheduler dlqScheduler) {
        this.dlqEntryRepository = dlqEntryRepository;
        this.retryPolicyRepository = retryPolicyRepository;
        this.circuitBreakerPolicyRepository = circuitBreakerPolicyRepository;
        this.dlqProcessorService = dlqProcessorService;
        this.dlqScheduler = dlqScheduler;
    }

    @GetMapping("/dlq")
    @PreAuthorize("hasPermission(null, 'reliability:view')")
    public String getDlqEntries(Model model) {
        List<DlqEntry> entries = dlqEntryRepository.findAll();
        model.addAttribute("entries", entries);
        return "admin/dlq";
    }

    @PostMapping("/dlq/{id}/retry")
    @PreAuthorize("hasPermission(null, 'reliability:retry')")
    @ResponseBody
    public String retryDlq(@PathVariable Long id) {
        try {
            dlqProcessorService.retryDlq(id);
            return "DLQ entry " + id + " retried successfully.";
        } catch (Exception e) {
            return "Failed to retry DLQ entry " + id + ": " + e.getMessage();
        }
    }

    @PostMapping("/dlq/process")
    @PreAuthorize("hasPermission(null, 'reliability:retry')")
    @ResponseBody
    public String triggerDlqProcessing() {
        dlqScheduler.processDlq();
        return "Scheduled DLQ processing triggered manually.";
    }

    @GetMapping("/policies")
    @PreAuthorize("hasPermission(null, 'reliability:view')")
    public String viewPolicies(Model model) {
        model.addAttribute("retryPolicies", retryPolicyRepository.findAll());
        model.addAttribute("circuitBreakerPolicies", circuitBreakerPolicyRepository.findAll());
        return "admin/reliability-policies";
    }

    // ✅ Removed: /test-expression endpoint.
    //    It evaluated arbitrary SpEL with StandardEvaluationContext (full method
    //    access → RCE). Not needed for any legitimate operational use.
}