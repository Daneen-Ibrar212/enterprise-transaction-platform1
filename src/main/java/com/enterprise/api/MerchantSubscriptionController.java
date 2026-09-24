package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.Role;
import com.enterprise.identity.RoleRepository;
import com.enterprise.identity.UserRepository;
import com.enterprise.subscription.CustomerSubscription;
import com.enterprise.subscription.SubscriptionPlan;
import com.enterprise.subscription.SubscriptionRequest;
import com.enterprise.subscription.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Controller
@RequestMapping("/merchant/subscriptions")
@PreAuthorize("hasRole('MERCHANT')")
public class MerchantSubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(MerchantSubscriptionController.class);

    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public MerchantSubscriptionController(SubscriptionService subscriptionService,
                                          UserRepository userRepository,
                                          RoleRepository roleRepository,
                                          PasswordEncoder passwordEncoder) {
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ============================================================
    // DASHBOARD
    // ============================================================
    @GetMapping
    public String dashboard(Model model, Authentication authentication) {
        AppUser merchant = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Long merchantId = merchant.getId();

        // Stats
        Map<String, Object> stats = subscriptionService.getMerchantStats(merchantId);
        model.addAttribute("stats", stats);

        // Pending requests
        List<SubscriptionRequest> pendingRequests = subscriptionService.getPendingRequests(merchantId);
        model.addAttribute("pendingRequests", pendingRequests);

        // Recent subscriptions
        Page<CustomerSubscription> subscriptions = subscriptionService.getMerchantSubscriptionsPaginated(
                merchantId, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        model.addAttribute("subscriptions", subscriptions);

        // Plans
        List<SubscriptionPlan> plans = subscriptionService.getPlansForTenant(merchant.getTenantId());
        model.addAttribute("plans", plans);

        return "merchant/subscriptions/dashboard";
    }

    // ============================================================
    // PLAN MANAGEMENT
    // ============================================================
    @GetMapping("/plans")
    public String managePlans(Model model, Authentication authentication) {
        AppUser merchant = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<SubscriptionPlan> plans = subscriptionService.getPlansForTenant(merchant.getTenantId());
        model.addAttribute("plans", plans);
        return "merchant/subscriptions/plans";
    }

    @PostMapping("/plans/create")
    public String createPlan(@ModelAttribute SubscriptionPlan plan,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            AppUser merchant = userRepository.findByEmail(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));
            plan.setTenantId(merchant.getTenantId());
            subscriptionService.createPlan(plan);
            redirectAttributes.addFlashAttribute("success", "✅ Plan created successfully");
        } catch (Exception e) {
            log.error("Error creating plan", e);
            redirectAttributes.addFlashAttribute("error", "Failed to create plan: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions/plans";
    }

    // ============================================================
    // EDIT PLAN FORM (GET) - NEW
    // ============================================================
    @GetMapping("/plans/{id}/edit")
    public String editPlanForm(@PathVariable Long id,
                               Authentication authentication,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        try {
            AppUser merchant = userRepository.findByEmail(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Fetch all plans for this tenant and find the matching one
            List<SubscriptionPlan> plans = subscriptionService.getPlansForTenant(merchant.getTenantId());

            SubscriptionPlan plan = plans.stream()
                    .filter(p -> p.getId().equals(id))
                    .findFirst()
                    .orElse(null);

            if (plan == null) {
                log.warn("Plan {} not found or doesn't belong to tenant {}", id, merchant.getTenantId());
                redirectAttributes.addFlashAttribute("error", "Plan not found");
                return "redirect:/merchant/subscriptions/plans";
            }

            model.addAttribute("plan", plan);
            return "merchant/subscriptions/plan-edit";

        } catch (Exception e) {
            log.error("Error loading plan {}", id, e);
            redirectAttributes.addFlashAttribute("error", "Failed to load plan: " + e.getMessage());
            return "redirect:/merchant/subscriptions/plans";
        }
    }

    @PostMapping("/plans/{id}/update")
    public String updatePlan(@PathVariable Long id,
                             @ModelAttribute SubscriptionPlan plan,
                             RedirectAttributes redirectAttributes) {
        try {
            subscriptionService.updatePlan(id, plan);
            redirectAttributes.addFlashAttribute("success", "✅ Plan updated successfully");
        } catch (Exception e) {
            log.error("Error updating plan", e);
            redirectAttributes.addFlashAttribute("error", "Failed to update plan: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions/plans";
    }

    @PostMapping("/plans/{id}/delete")
    public String deletePlan(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            subscriptionService.deletePlan(id);
            redirectAttributes.addFlashAttribute("success", "✅ Plan deleted successfully");
        } catch (Exception e) {
            log.error("Error deleting plan", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete plan: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions/plans";
    }

    // ============================================================
    // CUSTOMER SUBSCRIPTIONS VIEW
    // ============================================================
    @GetMapping("/customers")
    public String customerSubscriptions(Model model, Authentication authentication) {
        AppUser merchant = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Page<CustomerSubscription> subscriptions = subscriptionService.getMerchantSubscriptionsPaginated(
                merchant.getId(), PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        model.addAttribute("subscriptions", subscriptions);

        List<SubscriptionPlan> plans = subscriptionService.getPlansForTenant(merchant.getTenantId());
        model.addAttribute("plans", plans);

        return "merchant/subscriptions/customers";
    }

    // ============================================================
    // SUBSCRIPTION REQUESTS
    // ============================================================
    @GetMapping("/requests")
    public String pendingRequests(Model model, Authentication authentication) {
        AppUser merchant = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<SubscriptionRequest> requests = subscriptionService.getPendingRequests(merchant.getId());
        model.addAttribute("requests", requests);
        return "merchant/subscriptions/requests";
    }

    @PostMapping("/requests/{id}/approve")
    public String approveRequest(@PathVariable Long id,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = userRepository.findByEmail(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("Admin not found"));

            subscriptionService.approveRequest(id, admin.getId());
            redirectAttributes.addFlashAttribute("success", "✅ Subscription request approved!");

        } catch (Exception e) {
            log.error("Error approving request", e);
            redirectAttributes.addFlashAttribute("error", "Failed to approve: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions/requests";
    }

    @PostMapping("/requests/{id}/reject")
    public String rejectRequest(@PathVariable Long id,
                                @RequestParam(required = false) String reason,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = userRepository.findByEmail(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("Admin not found"));

            subscriptionService.rejectRequest(id, admin.getId(), reason);
            redirectAttributes.addFlashAttribute("success", "✅ Subscription request rejected.");

        } catch (Exception e) {
            log.error("Error rejecting request", e);
            redirectAttributes.addFlashAttribute("error", "Failed to reject: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions/requests";
    }

    // ============================================================
    // SUBSCRIBE CUSTOMER (Manual)
    // ============================================================
    @PostMapping("/subscribe")
    public String subscribeCustomer(@RequestParam Long planId,
                                    @RequestParam String customerEmail,
                                    @RequestParam(required = false) String customerPassword,
                                    Authentication authentication,
                                    RedirectAttributes redirectAttributes) {
        try {
            log.info("📝 Subscribing customer {} to plan {}", customerEmail, planId);

            AppUser merchant = userRepository.findByEmail(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            if (customerEmail == null || customerEmail.trim().isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Customer email is required");
                return "redirect:/merchant/subscriptions";
            }

            String trimmedEmail = customerEmail.trim();

            // Check if customer exists
            Optional<AppUser> existingCustomer = userRepository.findByEmailIgnoreCase(trimmedEmail);

            AppUser customer;
            if (existingCustomer.isPresent()) {
                customer = existingCustomer.get();
                log.info("👤 Customer already exists: {} (ID: {})", trimmedEmail, customer.getId());
            } else {
                log.info("📝 Creating new customer: {}", trimmedEmail);

                String password = customerPassword != null && !customerPassword.isEmpty()
                        ? customerPassword
                        : UUID.randomUUID().toString();

                AppUser newUser = new AppUser();
                newUser.setEmail(trimmedEmail);
                newUser.setPasswordHash(passwordEncoder.encode(password));
                newUser.setActive(true);
                newUser.setTenantId(merchant.getTenantId());

                Role customerRole = roleRepository.findByName("CUSTOMER")
                        .orElseThrow(() -> new RuntimeException("CUSTOMER role not found"));
                newUser.setRoles(Set.of(customerRole));

                customer = userRepository.save(newUser);
                log.info("✅ Created new customer: {} (ID: {})", trimmedEmail, customer.getId());
            }

            // Create subscription request (needs admin approval)
            subscriptionService.createRequest(
                    customer.getId(),
                    trimmedEmail,
                    merchant.getId(),
                    planId
            );

            log.info("✅ Subscription request created for customer {}", trimmedEmail);
            redirectAttributes.addFlashAttribute("success",
                    "✅ Subscription request created for " + trimmedEmail + ". Awaiting admin approval.");

        } catch (Exception e) {
            log.error("❌ Error subscribing customer: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "Failed to subscribe customer: " + e.getMessage());
        }

        return "redirect:/merchant/subscriptions";
    }

    // ============================================================
    // SUBSCRIPTION MANAGEMENT
    // ============================================================
    @PostMapping("/{id}/upgrade")
    public String upgradeSubscription(@PathVariable Long id,
                                      @RequestParam Long planId,
                                      RedirectAttributes redirectAttributes) {
        try {
            subscriptionService.upgradeSubscription(id, planId);
            redirectAttributes.addFlashAttribute("success", "✅ Subscription upgraded successfully");
        } catch (Exception e) {
            log.error("Error upgrading subscription", e);
            redirectAttributes.addFlashAttribute("error", "Failed to upgrade: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions";
    }

    @PostMapping("/{id}/downgrade")
    public String downgradeSubscription(@PathVariable Long id,
                                        @RequestParam Long planId,
                                        RedirectAttributes redirectAttributes) {
        try {
            subscriptionService.downgradeSubscription(id, planId);
            redirectAttributes.addFlashAttribute("success", "✅ Subscription downgraded successfully");
        } catch (Exception e) {
            log.error("Error downgrading subscription", e);
            redirectAttributes.addFlashAttribute("error", "Failed to downgrade: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions";
    }

    @PostMapping("/{id}/cancel")
    public String cancelSubscription(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            subscriptionService.cancelSubscription(id);
            redirectAttributes.addFlashAttribute("success", "✅ Subscription cancelled successfully");
        } catch (Exception e) {
            log.error("Error cancelling subscription", e);
            redirectAttributes.addFlashAttribute("error", "Failed to cancel: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions";
    }
}