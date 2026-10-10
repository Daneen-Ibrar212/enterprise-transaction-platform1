package com.enterprise.api;

import com.enterprise.audit.RuleAudit;
import com.enterprise.audit.RuleAuditService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.Permission;
import com.enterprise.identity.Role;
import com.enterprise.identity.RolePermissionService;
import com.enterprise.identity.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/roles")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminRoleController {

    private static final Logger log = LoggerFactory.getLogger(AdminRoleController.class);

    private final RolePermissionService service;
    private final UserRepository userRepository;
    private final RuleAuditService ruleAuditService;

    public AdminRoleController(RolePermissionService service,
                               UserRepository userRepository,
                               RuleAuditService ruleAuditService) {
        this.service = service;
        this.userRepository = userRepository;
        this.ruleAuditService = ruleAuditService;
    }

    private AppUser currentUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ============================================================
    // MATRIX VIEW
    // ============================================================
    @GetMapping
    public String matrix(Model model) {
        List<Role> roles = service.getAllRoles();
        List<Permission> permissions = service.getAllPermissions();
        Map<Long, Set<Long>> matrix = service.getMatrix();

        // Sort roles in a sensible order for display
        List<String> order = List.of("CUSTOMER","MERCHANT","ADMIN","AUDITOR","SUPER_ADMIN",
                                     "MERCHANT_ADMIN","SUPER_ADMIN");
        roles.sort(Comparator.comparingInt(r -> {
            int i = order.indexOf(r.getName());
            return i < 0 ? 999 : i;
        }));

        // Group permissions by resource for better readability
        Map<String, List<Permission>> grouped = permissions.stream()
                .collect(Collectors.groupingBy(Permission::getResource, TreeMap::new, Collectors.toList()));
        grouped.values().forEach(list -> list.sort(Comparator.comparing(Permission::getAction)));

        model.addAttribute("roles", roles);
        model.addAttribute("groupedPermissions", grouped);
        model.addAttribute("matrix", matrix);
        return "admin/roles/list";
    }

    // ============================================================
    // SAVE
    // ============================================================
    @PostMapping("/save")
    public String save(@RequestParam Map<String, String> allParams,
                       Authentication auth,
                       RedirectAttributes redirect) {
        try {
            AppUser admin = currentUser(auth);
            Long adminId = admin.getId();

            // Params arrive as "perm_<roleId>_<permissionId>" = "on" for checked
            Map<Long, Set<Long>> desired = new HashMap<>();
            for (Role role : service.getAllRoles()) {
                desired.put(role.getId(), new HashSet<>());
            }

            for (Map.Entry<String, String> e : allParams.entrySet()) {
                String key = e.getKey();
                if (!key.startsWith("perm_")) continue;
                String[] parts = key.split("_");
                if (parts.length != 3) continue;
                try {
                    Long roleId = Long.parseLong(parts[1]);
                    Long permId = Long.parseLong(parts[2]);
                    desired.computeIfAbsent(roleId, k -> new HashSet<>()).add(permId);
                } catch (NumberFormatException ignored) {}
            }

            int changedRoles = 0;
            for (Map.Entry<Long, Set<Long>> entry : desired.entrySet()) {
                service.updateRolePermissions(entry.getKey(), entry.getValue(), adminId);
                changedRoles++;
            }

            redirect.addFlashAttribute("success",
                    "✅ Updated permissions for " + changedRoles + " roles.");
        } catch (Exception e) {
            log.error("Failed to save role permissions", e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/admin/roles";
    }

    // ============================================================
    // HISTORY
    // ============================================================
    @GetMapping("/history")
    public String history(Model model) {
        List<RuleAudit> audits = ruleAuditService.getAuditsByRuleType("PERMISSION");
        model.addAttribute("audits", audits);
        return "admin/roles/history";
    }
}