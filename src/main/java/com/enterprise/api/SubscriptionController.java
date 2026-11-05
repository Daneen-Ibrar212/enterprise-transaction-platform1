package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.plan.PlatformPlan;
import com.enterprise.subscription.SubscriptionChargeService;
import com.enterprise.subscription.SubscriptionEvent;
import com.enterprise.tenant.Tenant;
import com.enterprise.tenant.TenantRepository;
import com.enterprise.transaction.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/account/plan")
@PreAuthorize("isAuthenticated()")
public class SubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionController.class);

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final SubscriptionChargeService chargeService;

    public SubscriptionController(UserRepository userRepository,
                                  TenantRepository tenantRepository,
                                  SubscriptionChargeService chargeService) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.chargeService = chargeService;
    }

    private AppUser currentUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalStateException("User not found"));
    }

    private Tenant currentTenant(AppUser user) {
        return tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new IllegalStateException("Tenant not found"));
    }

    @GetMapping
    public String viewPlan(Model model, Authentication auth) {
        AppUser user = currentUser(auth);
        Tenant tenant = currentTenant(user);

        PlatformPlan current = chargeService.getCurrentPlan(tenant.getId());
        List<PlatformPlan> allPlans = chargeService.allActivePlans();
        List<Transaction> payments = chargeService.getPaymentHistory(tenant.getId());
        List<SubscriptionEvent> events = chargeService.getEventHistory(tenant.getId());

        model.addAttribute("user", user);
        model.addAttribute("tenant", tenant);
        model.addAttribute("currentPlan", current);
        model.addAttribute("plans", allPlans);
        model.addAttribute("payments", payments);
        model.addAttribute("events", events);
        return "account/plan";
    }

    @PostMapping("/change")
    public String changePlan(@RequestParam String planCode,
                             Authentication auth,
                             RedirectAttributes redirect) {
        try {
            AppUser user = currentUser(auth);
            chargeService.changePlan(user.getTenantId(), planCode, user.getId());
            redirect.addFlashAttribute("success", "Plan updated. Your new subscription is active.");
        } catch (Exception e) {
            log.warn("Plan change failed for {}: {}", auth.getName(), e.getMessage());
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/account/plan";
    }

    @PostMapping("/cancel")
    public String cancel(Authentication auth, RedirectAttributes redirect) {
        try {
            AppUser user = currentUser(auth);
            chargeService.cancelAtPeriodEnd(user.getTenantId(), user.getId());
            redirect.addFlashAttribute("success",
                    "Subscription will end at the close of the current billing period.");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/account/plan";
    }

    @PostMapping("/resume")
    public String resume(Authentication auth, RedirectAttributes redirect) {
        try {
            AppUser user = currentUser(auth);
            chargeService.resumeSubscription(user.getTenantId(), user.getId());
            redirect.addFlashAttribute("success", "Subscription resumed.");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/account/plan";
    }
}