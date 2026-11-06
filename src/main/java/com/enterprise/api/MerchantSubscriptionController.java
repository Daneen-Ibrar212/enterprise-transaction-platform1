package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.Role;
import com.enterprise.identity.RoleRepository;
import com.enterprise.identity.UserRepository;
import com.enterprise.subscription.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Controller
@RequestMapping("/merchant/subscriptions")
@PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_ADMIN','SUPER_ADMIN')")
public class MerchantSubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(MerchantSubscriptionController.class);

    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final SubscriptionBillingService billingService;
    private final SubscriptionUsageService usageService;

    public MerchantSubscriptionController(SubscriptionService subscriptionService,
                                          UserRepository userRepository,
                                          RoleRepository roleRepository,
                                          PasswordEncoder passwordEncoder,
                                          SubscriptionBillingService billingService,
                                          SubscriptionUsageService usageService) {
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.billingService = billingService;
        this.usageService = usageService;
    }

    private AppUser currentUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /**
     * ✅ Load a subscription and assert ownership.
     *
     * Allowed if:
     *  - caller is SUPER_ADMIN
     *  - caller is the subscription's merchant
     *  - caller is a MERCHANT_ADMIN in the subscription's tenant
     */
    private CustomerSubscription loadOwnedSubscription(Long id, AppUser me) {
        CustomerSubscription sub = subscriptionService.getSubscriptionById(id);
        if (me.isSuperAdmin()) return sub;

        if (sub.getMerchantId().equals(me.getId())) return sub;

        boolean sameTenantMerchantAdmin = sub.getTenantId().equals(me.getTenantId())
                && me.getRoles().stream().anyMatch(r -> r.getName().equals("MERCHANT_ADMIN"));
        if (sameTenantMerchantAdmin) return sub;

        throw new AccessDeniedException("You do not own this subscription");
    }

    @GetMapping
    public String dashboard(Model model, Authentication authentication) {
        AppUser merchant = currentUser(authentication);
        Long merchantId = merchant.getId();

        Map<String, Object> stats = subscriptionService.getMerchantStats(merchantId);
        model.addAttribute("stats", stats);

        List<SubscriptionRequest> pendingRequests = subscriptionService.getPendingRequests(merchantId);
        model.addAttribute("pendingRequests", pendingRequests);

        Page<CustomerSubscription> subscriptions = subscriptionService
                .getMerchantSubscriptionsPaginated(merchantId,
                        PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
        model.addAttribute("subscriptions", subscriptions);

        List<SubscriptionPlan> plans = subscriptionService.getPlansForTenant(merchant.getTenantId());
        model.addAttribute("plans", plans);

        return "merchant/subscriptions/dashboard";
    }

    // ============================================================
    // PLAN MANAGEMENT
    // ============================================================
    @GetMapping("/plans")
    public String managePlans(Model model, Authentication authentication) {
        AppUser merchant = currentUser(authentication);
        model.addAttribute("plans",
                subscriptionService.getPlansForTenant(merchant.getTenantId()));
        return "merchant/subscriptions/plans";
    }

    @PostMapping("/plans/create")
    public String createPlan(@ModelAttribute SubscriptionPlan plan,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        try {
            AppUser merchant = currentUser(authentication);
            plan.setTenantId(merchant.getTenantId());
            subscriptionService.createPlan(plan);
            redirectAttributes.addFlashAttribute("success", "✅ Plan created successfully");
        } catch (Exception e) {
            log.error("Error creating plan", e);
            redirectAttributes.addFlashAttribute("error", "Failed to create plan: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions/plans";
    }

    @GetMapping("/plans/{id}/edit")
    public String editPlanForm(@PathVariable Long id,
                               Authentication authentication,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        try {
            AppUser merchant = currentUser(authentication);
            List<SubscriptionPlan> plans = subscriptionService.getPlansForTenant(merchant.getTenantId());
            SubscriptionPlan plan = plans.stream()
                    .filter(p -> p.getId().equals(id))
                    .findFirst()
                    .orElse(null);
            if (plan == null) {
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
    // CUSTOMER VIEW
    // ============================================================
    @GetMapping("/customers")
    public String customerSubscriptions(Model model, Authentication authentication) {
        AppUser merchant = currentUser(authentication);
        Page<CustomerSubscription> subscriptions = subscriptionService
                .getMerchantSubscriptionsPaginated(merchant.getId(),
                        PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")));
        model.addAttribute("subscriptions", subscriptions);
        model.addAttribute("plans",
                subscriptionService.getPlansForTenant(merchant.getTenantId()));
        return "merchant/subscriptions/customers";
    }

    // ============================================================
    // REQUESTS
    // ============================================================
    @GetMapping("/requests")
    public String pendingRequests(Model model, Authentication authentication) {
        AppUser merchant = currentUser(authentication);
        model.addAttribute("requests", subscriptionService.getPendingRequests(merchant.getId()));
        return "merchant/subscriptions/requests";
    }

    @PostMapping("/requests/{id}/approve")
    public String approveRequest(@PathVariable Long id,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        try {
            AppUser admin = currentUser(authentication);
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
            AppUser admin = currentUser(authentication);
            subscriptionService.rejectRequest(id, admin.getId(), reason);
            redirectAttributes.addFlashAttribute("success", "✅ Subscription request rejected.");
        } catch (Exception e) {
            log.error("Error rejecting request", e);
            redirectAttributes.addFlashAttribute("error", "Failed to reject: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions/requests";
    }

    // ============================================================
    // SUBSCRIBE CUSTOMER (manual)
    // ============================================================
    @PostMapping("/subscribe")
    public String subscribeCustomer(@RequestParam Long planId,
                                    @RequestParam String customerEmail,
                                    @RequestParam(required = false) String customerPassword,
                                    Authentication authentication,
                                    RedirectAttributes redirectAttributes) {
        try {
            AppUser merchant = currentUser(authentication);
            if (customerEmail == null || customerEmail.trim().isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Customer email is required");
                return "redirect:/merchant/subscriptions";
            }
            String trimmedEmail = customerEmail.trim();
            Optional<AppUser> existing = userRepository.findByEmailIgnoreCase(trimmedEmail);

            AppUser customer;
            if (existing.isPresent()) {
                customer = existing.get();
            } else {
                String password = customerPassword != null && !customerPassword.isEmpty()
                        ? customerPassword : UUID.randomUUID().toString();
                AppUser newUser = new AppUser();
                newUser.setEmail(trimmedEmail);
                newUser.setPasswordHash(passwordEncoder.encode(password));
                newUser.setActive(true);
                newUser.setTenantId(merchant.getTenantId());
                Role customerRole = roleRepository.findByName("CUSTOMER")
                        .orElseThrow(() -> new RuntimeException("CUSTOMER role not found"));
                newUser.setRoles(Set.of(customerRole));
                customer = userRepository.save(newUser);
            }

            subscriptionService.createRequest(customer.getId(), trimmedEmail, merchant.getId(), planId);
            redirectAttributes.addFlashAttribute("success",
                    "✅ Subscription request created for " + trimmedEmail + ".");
        } catch (Exception e) {
            log.error("Error subscribing customer", e);
            redirectAttributes.addFlashAttribute("error", "Failed to subscribe customer: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions";
    }

    // ============================================================
    // BILLING PAGE — ownership checked
    // ============================================================
    @GetMapping("/{id}/billing")
    public String billingPage(@PathVariable Long id, Model model, Authentication auth) {
        AppUser me = currentUser(auth);
        CustomerSubscription sub = loadOwnedSubscription(id, me);

        List<SubscriptionPlan> plans = subscriptionService.getPlansForTenant(sub.getTenantId());
        List<SubscriptionUsage> usage = usageService.listUsage(id);
        Map<String, Object> invoicePreview = billingService.previewNextInvoice(id);

        model.addAttribute("subscription", sub);
        model.addAttribute("plans", plans);
        model.addAttribute("usage", usage);
        model.addAttribute("invoicePreview", invoicePreview);
        return "merchant/subscriptions/billing";
    }

    // ============================================================
    // PAUSE / RESUME — ownership checked
    // ============================================================
    @PostMapping("/{id}/pause")
    public String pause(@PathVariable Long id,
                        @RequestParam(required = false) String reason,
                        Authentication auth,
                        RedirectAttributes redirect) {
        try {
            AppUser me = currentUser(auth);
            loadOwnedSubscription(id, me);  // ✅ ownership
            billingService.pauseSubscription(id, reason);
            redirect.addFlashAttribute("success", "⏸️ Subscription paused.");
        } catch (Exception e) {
            log.error("Pause failed for sub {}", id, e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions/" + id + "/billing";
    }

    @PostMapping("/{id}/resume")
    public String resume(@PathVariable Long id,
                         Authentication auth,
                         RedirectAttributes redirect) {
        try {
            AppUser me = currentUser(auth);
            loadOwnedSubscription(id, me);  // ✅ ownership
            billingService.resumeSubscription(id);
            redirect.addFlashAttribute("success", "▶️ Subscription resumed.");
        } catch (Exception e) {
            log.error("Resume failed for sub {}", id, e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions/" + id + "/billing";
    }

    // ============================================================
    // PRORATION PREVIEW — ownership checked
    // ============================================================
    @GetMapping(value = "/{id}/preview-proration", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> previewProration(@PathVariable Long id,
                                              @RequestParam Long newPlanId,
                                              Authentication auth) {
        try {
            AppUser me = currentUser(auth);
            loadOwnedSubscription(id, me);  // ✅ ownership
            return ResponseEntity.ok(billingService.previewProration(id, newPlanId));
        } catch (Exception e) {
            log.error("Proration preview failed for sub {} → plan {}", id, newPlanId, e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "subscriptionId", id,
                    "newPlanId", newPlanId
            ));
        }
    }

    // ============================================================
    // UPGRADE / DOWNGRADE — ownership checked
    // ============================================================
    @PostMapping("/{id}/upgrade")
    public String upgrade(@PathVariable Long id,
                          @RequestParam Long planId,
                          Authentication auth,
                          RedirectAttributes redirect) {
        try {
            AppUser admin = currentUser(auth);
            loadOwnedSubscription(id, admin);  // ✅ ownership
            billingService.upgradeWithProration(id, planId, admin.getId());
            redirect.addFlashAttribute("success", "⬆️ Subscription upgraded.");
        } catch (Exception e) {
            log.error("Upgrade failed for sub {}", id, e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions/" + id + "/billing";
    }

    @PostMapping("/{id}/downgrade")
    public String downgrade(@PathVariable Long id,
                            @RequestParam Long planId,
                            Authentication auth,
                            RedirectAttributes redirect) {
        try {
            AppUser admin = currentUser(auth);
            loadOwnedSubscription(id, admin);  // ✅ ownership
            billingService.downgradeWithProration(id, planId, admin.getId());
            redirect.addFlashAttribute("success", "⬇️ Subscription downgraded.");
        } catch (Exception e) {
            log.error("Downgrade failed for sub {}", id, e);
            redirect.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions/" + id + "/billing";
    }

    // ============================================================
    // USAGE — ownership checked
    // ============================================================
    @PostMapping("/{id}/usage")
    @ResponseBody
    public ResponseEntity<?> recordUsage(@PathVariable Long id,
                                         @RequestParam String metric,
                                         @RequestParam(defaultValue = "1") Long delta,
                                         @RequestParam(defaultValue = "0") Long included,
                                         @RequestParam(defaultValue = "0") BigDecimal overageRate,
                                         Authentication auth) {
        try {
            AppUser me = currentUser(auth);
            loadOwnedSubscription(id, me);  // ✅ ownership — prevents billing manipulation
            SubscriptionUsage usage = usageService.recordUsage(id, metric, delta, included, overageRate);
            return ResponseEntity.ok(Map.of(
                    "metric", usage.getMetricName(),
                    "count", usage.getUsageCount(),
                    "periodStart", usage.getPeriodStart().toString(),
                    "periodEnd", usage.getPeriodEnd().toString()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ============================================================
    // CANCEL — ownership checked
    // ============================================================
    @PostMapping("/{id}/cancel")
    public String cancelSubscription(@PathVariable Long id,
                                     Authentication auth,
                                     RedirectAttributes redirectAttributes) {
        try {
            AppUser me = currentUser(auth);
            loadOwnedSubscription(id, me);  // ✅ ownership
            subscriptionService.cancelSubscription(id);
            redirectAttributes.addFlashAttribute("success", "✅ Subscription cancelled successfully");
        } catch (Exception e) {
            log.error("Error cancelling subscription", e);
            redirectAttributes.addFlashAttribute("error", "Failed to cancel: " + e.getMessage());
        }
        return "redirect:/merchant/subscriptions";
    }
}