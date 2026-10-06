package com.enterprise.api;

import com.enterprise.currency.CurrencyCodes;
import com.enterprise.tenant.Tenant;
import com.enterprise.tenant.TenantRepository;
import com.enterprise.tenant.TenantSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;

@Controller
@RequestMapping("/admin/tenants")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminTenantController {

    private static final Logger log = LoggerFactory.getLogger(AdminTenantController.class);

    private final TenantRepository tenantRepository;
    private final TenantSeeder tenantSeeder;

    public AdminTenantController(TenantRepository tenantRepository,
                                 TenantSeeder tenantSeeder) {
        this.tenantRepository = tenantRepository;
        this.tenantSeeder = tenantSeeder;
    }

    @GetMapping
    public String listTenants(Model model) {
        List<Tenant> tenants = tenantRepository.findAll();
        model.addAttribute("tenants", tenants);
        return "admin/tenants/list";
    }

    @GetMapping("/create")
    public String showCreateForm(Model model) {
        model.addAttribute("tenant", new Tenant());
        model.addAttribute("currencies", CurrencyCodes.getAllCurrencies());
        return "admin/tenants/create";
    }

    @PostMapping
    public String createTenant(@ModelAttribute Tenant tenant, RedirectAttributes redirectAttributes) {
        try {
            tenant.setCreatedAt(LocalDateTime.now());
            tenant.setBaseCurrency(tenant.getBaseCurrency() != null ? tenant.getBaseCurrency() : "GBP");
            Tenant saved = tenantRepository.save(tenant);

            // ✅ SEED DEFAULT FEATURE FLAGS FOR NEW TENANT
            try {
                tenantSeeder.seedDefaultFeatureFlags(saved.getId());
                log.info("✅ Seeded feature flags for new tenant: {} ({})", saved.getId(), saved.getName());
            } catch (Exception e) {
                log.warn("⚠️ Failed to seed feature flags for tenant {}: {}", saved.getId(), e.getMessage());
                // Don't fail the tenant creation just because flag seeding failed
            }

            redirectAttributes.addFlashAttribute("success", "Tenant created with default feature flags.");
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                redirectAttributes.addFlashAttribute("error", "Tenant with this name already exists.");
            } else {
                redirectAttributes.addFlashAttribute("error", "Failed to create tenant: " + e.getMessage());
            }
        } catch (Exception e) {
            log.error("Error creating tenant", e);
            redirectAttributes.addFlashAttribute("error", "Failed to create tenant: " + e.getMessage());
        }
        return "redirect:/admin/tenants";
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable Long id, Model model) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Tenant not found"));
        model.addAttribute("tenant", tenant);
        model.addAttribute("currencies", CurrencyCodes.getAllCurrencies());
        return "admin/tenants/edit";
    }

    @PostMapping("/{id}")
    public String updateTenant(@PathVariable Long id, @ModelAttribute Tenant tenant, RedirectAttributes redirectAttributes) {
        Tenant existing = tenantRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Tenant not found"));
        existing.setName(tenant.getName());
        existing.setDescription(tenant.getDescription());
        existing.setActive(tenant.isActive());
        existing.setBaseCurrency(tenant.getBaseCurrency() != null ? tenant.getBaseCurrency() : "GBP");
        existing.setUpdatedAt(LocalDateTime.now());
        tenantRepository.save(existing);
        redirectAttributes.addFlashAttribute("success", "Tenant updated.");
        return "redirect:/admin/tenants";
    }

    @PostMapping("/{id}/delete")
    public String deleteTenant(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        tenantRepository.deleteById(id);
        redirectAttributes.addFlashAttribute("success", "Tenant deleted.");
        return "redirect:/admin/tenants";
    }
}