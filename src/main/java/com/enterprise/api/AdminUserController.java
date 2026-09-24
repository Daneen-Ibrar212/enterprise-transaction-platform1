package com.enterprise.api;

import com.enterprise.audit.UserActivityLogService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.Role;
import com.enterprise.identity.RoleRepository;
import com.enterprise.identity.UserRepository;
import com.enterprise.notification.NotificationService;
import com.enterprise.security.TwoFactorOverrideService;
import com.enterprise.tenant.TenantRepository;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final NotificationService notificationService;
    private final PasswordEncoder passwordEncoder;
    private final TenantRepository tenantRepository;
    private final UserActivityLogService activityLogService;
    private final TwoFactorOverrideService twoFactorOverrideService;

    public AdminUserController(UserRepository userRepository,
                               RoleRepository roleRepository,
                               NotificationService notificationService,
                               PasswordEncoder passwordEncoder,
                               TenantRepository tenantRepository,
                               UserActivityLogService activityLogService,
                               TwoFactorOverrideService twoFactorOverrideService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.notificationService = notificationService;
        this.passwordEncoder = passwordEncoder;
        this.tenantRepository = tenantRepository;
        this.activityLogService = activityLogService;
        this.twoFactorOverrideService = twoFactorOverrideService;
    }

    private AppUser getCurrentAdmin(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ============================================================
    // LIST USERS
    // ============================================================
    @GetMapping
    public String listUsers(@RequestParam(required = false) String search,
                            @RequestParam(required = false) String role,
                            Model model,
                            Authentication authentication) {

        AppUser admin = getCurrentAdmin(authentication);
        boolean isSuperAdmin = admin.isSuperAdmin();

        List<AppUser> users;
        if (isSuperAdmin) {
            users = userRepository.findAllWithoutTenantFilter();
        } else {
            users = userRepository.findAll();
        }

        if (search != null && !search.isEmpty()) {
            String lowerSearch = search.toLowerCase();
            users = users.stream()
                    .filter(u -> u.getEmail().toLowerCase().contains(lowerSearch))
                    .collect(Collectors.toList());
        }
        if (role != null && !role.isEmpty()) {
            users = users.stream()
                    .filter(u -> u.getRoles().stream().anyMatch(r -> r.getName().equals(role)))
                    .collect(Collectors.toList());
        }

        for (AppUser user : users) {
            if (user.getTenantId() != null) {
                tenantRepository.findById(user.getTenantId())
                        .ifPresent(tenant -> user.setTenantName(tenant.getName()));
            } else {
                user.setTenantName("N/A");
            }
        }

        model.addAttribute("users", users);
        model.addAttribute("search", search);
        model.addAttribute("selectedRole", role);
        model.addAttribute("allRoles", roleRepository.findAll());
        model.addAttribute("isSuperAdmin", isSuperAdmin);
        return "admin/users/list";
    }

    // ============================================================
    // IMPERSONATE
    // ============================================================
    @PostMapping("/{id}/impersonate")
    public String impersonateUser(@PathVariable Long id, HttpServletRequest request) throws Exception {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
        String switchUrl = "/admin/users/impersonate?username=" + user.getEmail();
        return "redirect:" + switchUrl;
    }

    // ============================================================
    // CREATE USER
    // ============================================================
    @GetMapping("/create")
    public String showCreateForm(Model model, Authentication authentication) {
        AppUser admin = getCurrentAdmin(authentication);
        boolean isSuperAdmin = admin.isSuperAdmin();

        model.addAttribute("user", new AppUser());
        model.addAttribute("allRoles", roleRepository.findAll());
        model.addAttribute("allTenants", isSuperAdmin ? tenantRepository.findAll() : List.of());
        model.addAttribute("currentTenant", isSuperAdmin ? null : tenantRepository.findById(admin.getTenantId()).orElse(null));
        model.addAttribute("isSuperAdmin", isSuperAdmin);
        return "admin/users/create";
    }

    @PostMapping
    public String createUser(@RequestParam String email,
                             @RequestParam String password,
                             @RequestParam(required = false) List<Long> roleIds,
                             @RequestParam(required = false) Long tenantId,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {

        AppUser admin = getCurrentAdmin(authentication);
        boolean isSuperAdmin = admin.isSuperAdmin();

        if (userRepository.findByEmail(email).isPresent()) {
            redirectAttributes.addFlashAttribute("error", "User with this email already exists.");
            return "redirect:/admin/users/create";
        }

        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(true);

        if (isSuperAdmin) {
            if (tenantId == null) tenantId = 1L;
            user.setTenantId(tenantId);
        } else {
            user.setTenantId(admin.getTenantId());
        }

        if (roleIds != null && !roleIds.isEmpty()) {
            Set<Role> roles = new HashSet<>(roleRepository.findAllById(roleIds));
            user.setRoles(roles);
        } else {
            Role defaultRole = roleRepository.findByName("MERCHANT_ADMIN")
                    .orElseThrow(() -> new RuntimeException("Default role MERCHANT_ADMIN not found"));
            user.getRoles().add(defaultRole);
        }

        userRepository.save(user);

        activityLogService.logActivity(
                user.getId(),
                "ACCOUNT_CREATED",
                "Account created by admin: " + admin.getEmail(),
                null
        );

        notificationService.createNotification(
                user.getId(),
                "ACCOUNT_CREATED",
                "Account Created",
                "An administrator has created your account. You can log in with your email and the password provided.",
                "/login"
        );

        redirectAttributes.addFlashAttribute("success", "User created successfully.");
        return "redirect:/admin/users";
    }

    // ============================================================
    // EDIT USER
    // ============================================================
    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable Long id, Model model, Authentication authentication) {
        AppUser admin = getCurrentAdmin(authentication);
        boolean isSuperAdmin = admin.isSuperAdmin();

        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!isSuperAdmin && !user.getTenantId().equals(admin.getTenantId())) {
            throw new RuntimeException("You cannot edit users from other tenants.");
        }

        model.addAttribute("user", user);
        model.addAttribute("allRoles", roleRepository.findAll());
        model.addAttribute("userRoleIds", user.getRoles().stream().map(Role::getId).collect(Collectors.toList()));
        model.addAttribute("allTenants", isSuperAdmin ? tenantRepository.findAll() : List.of());
        model.addAttribute("isSuperAdmin", isSuperAdmin);
        return "admin/users/edit";
    }

    @PostMapping("/{id}")
    public String updateUser(@PathVariable Long id,
                             @RequestParam(required = false) List<Long> roleIds,
                             @RequestParam(required = false) Boolean active,
                             @RequestParam(required = false) Long tenantId,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {

        AppUser admin = getCurrentAdmin(authentication);
        boolean isSuperAdmin = admin.isSuperAdmin();

        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!isSuperAdmin && !user.getTenantId().equals(admin.getTenantId())) {
            throw new RuntimeException("You cannot edit users from other tenants.");
        }

        Set<Role> oldRoles = new HashSet<>(user.getRoles());

        if (isSuperAdmin && tenantId != null) {
            user.setTenantId(tenantId);
        }

        if (roleIds != null && !roleIds.isEmpty()) {
            Set<Role> roles = new HashSet<>(roleRepository.findAllById(roleIds));
            user.setRoles(roles);
        } else {
            Role defaultRole = roleRepository.findByName("MERCHANT_ADMIN")
                    .orElseThrow(() -> new RuntimeException("Default role MERCHANT_ADMIN not found"));
            user.getRoles().clear();
            user.getRoles().add(defaultRole);
        }

        if (active != null) {
            user.setActive(active);
            if (!active) {
                notificationService.createNotification(
                        user.getId(),
                        "ACCOUNT_REVOKED",
                        "Account Disabled",
                        "Your account has been disabled by an administrator.",
                        "/login"
                );
                activityLogService.logActivity(
                        user.getId(),
                        "ACCOUNT_REVOKED",
                        "Account revoked by admin: " + admin.getEmail(),
                        null
                );
            } else {
                notificationService.createNotification(
                        user.getId(),
                        "ACCOUNT_RESTORED",
                        "Account Reactivated",
                        "Your account has been reactivated by an administrator.",
                        "/login"
                );
                activityLogService.logActivity(
                        user.getId(),
                        "ACCOUNT_RESTORED",
                        "Account restored by admin: " + admin.getEmail(),
                        null
                );
            }
        }

        if (!oldRoles.equals(user.getRoles())) {
            activityLogService.logActivity(
                    user.getId(),
                    "ROLE_CHANGED",
                    "Roles changed from " + oldRoles.stream().map(Role::getName).collect(Collectors.joining(", ")) +
                    " to " + user.getRoles().stream().map(Role::getName).collect(Collectors.joining(", ")),
                    null
            );
        }

        userRepository.save(user);
        redirectAttributes.addFlashAttribute("success", "User updated successfully.");
        return "redirect:/admin/users";
    }

    // ============================================================
    // REVOKE
    // ============================================================
    @PostMapping("/revoke/{userId}")
    public String revokeUserById(@PathVariable Long userId,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        AppUser admin = getCurrentAdmin(authentication);
        boolean isSuperAdmin = admin.isSuperAdmin();

        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!isSuperAdmin && !user.getTenantId().equals(admin.getTenantId())) {
            redirectAttributes.addFlashAttribute("error", "You cannot revoke users from other tenants.");
            return "redirect:/admin/users";
        }

        user.setActive(false);
        userRepository.save(user);

        notificationService.createNotification(
                user.getId(),
                "ACCOUNT_REVOKED",
                "Account Revoked",
                "Your merchant account has been revoked by an administrator.",
                "/login"
        );
        activityLogService.logActivity(
                user.getId(),
                "ACCOUNT_REVOKED",
                "Account revoked by admin: " + admin.getEmail(),
                null
        );

        redirectAttributes.addFlashAttribute("success", "User account revoked successfully.");
        return "redirect:/admin/users";
    }

    // ============================================================
    // RESTORE
    // ============================================================
    @GetMapping("/restore")
    public String restorePage(@RequestParam(required = false) String search,
                              Model model,
                              Authentication authentication) {
        AppUser admin = getCurrentAdmin(authentication);
        boolean isSuperAdmin = admin.isSuperAdmin();

        List<AppUser> inactiveUsers;
        if (isSuperAdmin) {
            inactiveUsers = userRepository.findAllWithoutTenantFilter().stream()
                    .filter(u -> !u.isActive())
                    .collect(Collectors.toList());
        } else {
            inactiveUsers = userRepository.findAll().stream()
                    .filter(u -> !u.isActive() && u.getTenantId().equals(admin.getTenantId()))
                    .collect(Collectors.toList());
        }

        if (search != null && !search.isEmpty()) {
            inactiveUsers = inactiveUsers.stream()
                    .filter(u -> u.getEmail().toLowerCase().contains(search.toLowerCase()))
                    .collect(Collectors.toList());
        }
        model.addAttribute("users", inactiveUsers);
        model.addAttribute("search", search);
        model.addAttribute("isSuperAdmin", isSuperAdmin);
        return "admin/users/restore";
    }

    @PostMapping("/{id}/restore")
    public String restoreUser(@PathVariable Long id,
                              @RequestParam(required = false) String search,
                              Authentication authentication) {
        AppUser admin = getCurrentAdmin(authentication);
        boolean isSuperAdmin = admin.isSuperAdmin();

        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!isSuperAdmin && !user.getTenantId().equals(admin.getTenantId())) {
            throw new RuntimeException("You cannot restore users from other tenants.");
        }

        user.setActive(true);
        userRepository.save(user);

        notificationService.createNotification(
                user.getId(),
                "ACCOUNT_RESTORED",
                "Account Restored",
                "Your account has been reactivated by an administrator.",
                "/login"
        );
        activityLogService.logActivity(
                user.getId(),
                "ACCOUNT_RESTORED",
                "Account restored by admin: " + admin.getEmail(),
                null
        );
        return "redirect:/admin/users/restore" + (search != null ? "?search=" + search : "");
    }

    // ============================================================
    // SUPER ADMIN: 2FA OVERRIDE
    // ============================================================
    @PostMapping("/{id}/disable-2fa")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public String disableTwoFactor(@PathVariable Long id,
                                   @RequestParam(required = false, defaultValue = "Lost device") String reason,
                                   @RequestParam(required = false) Integer hoursValid,
                                   Authentication authentication,
                                   RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            twoFactorOverrideService.disableTwoFactorForUser(id, admin.getId(), reason, hoursValid);
            redirectAttributes.addFlashAttribute("success",
                "✅ 2FA temporarily disabled. " +
                (hoursValid != null ? "Override expires in " + hoursValid + " hours." : "Override is indefinite."));
        } catch (Exception e) {
            log.error("Error disabling 2FA for user {}", id, e);
            redirectAttributes.addFlashAttribute("error", "Failed to disable 2FA: " + e.getMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/enable-2fa")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public String enableTwoFactor(@PathVariable Long id,
                                  Authentication authentication,
                                  RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = getCurrentAdmin(authentication);
            twoFactorOverrideService.enableTwoFactorForUser(id, admin.getId());
            redirectAttributes.addFlashAttribute("success", "✅ 2FA re-enabled for user.");
        } catch (Exception e) {
            log.error("Error re-enabling 2FA for user {}", id, e);
            redirectAttributes.addFlashAttribute("error", "Failed to re-enable 2FA: " + e.getMessage());
        }
        return "redirect:/admin/users";
    }
}