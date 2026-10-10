package com.enterprise.api;

import com.enterprise.feature.FeatureFlagService;
import com.enterprise.reminder.ReminderSchedule;
import com.enterprise.reminder.ReminderService;
import com.enterprise.tenant.Tenant;
import com.enterprise.tenant.TenantContext;
import com.enterprise.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/admin/reminders")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class AdminReminderController {

    private static final Logger log = LoggerFactory.getLogger(AdminReminderController.class);

    private final ReminderService reminderService;
    private final TenantRepository tenantRepository;
    private final FeatureFlagService featureFlagService;

    public AdminReminderController(ReminderService reminderService,
                                   TenantRepository tenantRepository,
                                   FeatureFlagService featureFlagService) {
        this.reminderService = reminderService;
        this.tenantRepository = tenantRepository;
        this.featureFlagService = featureFlagService;
    }

    // ============================================================
    // LIST
    // ============================================================
    @GetMapping
    public String listReminders(Model model) {
        List<ReminderSchedule> pending = reminderService.listPendingForCurrentTenant();
        List<ReminderSchedule> all = reminderService.listForCurrentTenant();

        model.addAttribute("pending", pending);
        model.addAttribute("all", all);
        return "admin/reminders/list";
    }

    // ============================================================
    // SEND NOW
    // ============================================================
    @PostMapping("/run-now")
    public String runNow(RedirectAttributes redirect) {
        try {
            int sent = reminderService.sendDueReminders();
            redirect.addFlashAttribute("success", "Sent " + sent + " reminders.");
        } catch (Exception e) {
            log.error("Failed to run reminders", e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/admin/reminders";
    }

    // ============================================================
    // SETTINGS — GET
    // ============================================================
    @GetMapping("/settings")
    public String settingsForm(Model model) {
        Long tenantId = TenantContext.getRequiredTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new RuntimeException("Tenant not found"));

        model.addAttribute("tenant", tenant);
        model.addAttribute("enabled", featureFlagService.isEnabled("INVOICE_REMINDERS"));
        return "admin/reminders/settings";
    }

    // ============================================================
    // SETTINGS — SAVE
    // ============================================================
    @PostMapping("/settings")
    public String saveSettings(@RequestParam int firstDays,
                               @RequestParam int secondDays,
                               @RequestParam int finalDays,
                               @RequestParam(required = false) Boolean enabled,
                               RedirectAttributes redirect) {
        try {
            // Validation
            if (firstDays < 1) {
                throw new IllegalArgumentException("First reminder must be at least 1 day");
            }
            if (!(firstDays < secondDays && secondDays < finalDays)) {
                throw new IllegalArgumentException(
                        "Days must be strictly increasing (e.g. 7 < 14 < 30)");
            }

            Long tenantId = TenantContext.getRequiredTenantId();
            Tenant tenant = tenantRepository.findById(tenantId)
                    .orElseThrow(() -> new RuntimeException("Tenant not found"));

            tenant.setReminderFirstDays(firstDays);
            tenant.setReminderSecondDays(secondDays);
            tenant.setReminderFinalDays(finalDays);
            tenantRepository.save(tenant);

            // Toggle feature flag if requested
            if (enabled != null) {
                featureFlagService.setEnabled("INVOICE_REMINDERS", enabled);
            }

            log.info("✅ Reminder settings saved for tenant {}: {}/{}/{} days, enabled={}",
                    tenantId, firstDays, secondDays, finalDays, enabled);

            redirect.addFlashAttribute("success", "✅ Reminder settings saved.");
        } catch (Exception e) {
            log.error("Failed to save reminder settings", e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/admin/reminders/settings";
    }
}