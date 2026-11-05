package com.enterprise.api;

import com.enterprise.plan.PlatformPlan;
import com.enterprise.plan.PlatformPlanRepository;
import com.enterprise.signup.SignupRequest;
import com.enterprise.signup.SignupService;
import com.enterprise.subscription.SubscriptionChargeService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/signup")
public class SignupController {

    private static final Logger log = LoggerFactory.getLogger(SignupController.class);

    private final SignupService signupService;
    private final PlatformPlanRepository planRepository;
    private final SubscriptionChargeService subscriptionChargeService;

    public SignupController(SignupService signupService,
                            PlatformPlanRepository planRepository,
                            SubscriptionChargeService subscriptionChargeService) {
        this.signupService = signupService;
        this.planRepository = planRepository;
        this.subscriptionChargeService = subscriptionChargeService;
    }

    // ============================================================
    // FORM
    // ============================================================
    @GetMapping
    public String form(@RequestParam(required = false) String plan, Model model) {
        List<PlatformPlan> plans = planRepository.findByActiveTrueOrderByDisplayOrderAsc();
        if (plans.isEmpty()) {
            model.addAttribute("error", "No plans available at the moment. Please check back later.");
            return "signup/signup";
        }
        PlatformPlan selected = plans.stream()
                .filter(p -> p.getCode().equalsIgnoreCase(plan))
                .findFirst()
                .orElse(plans.get(0));
        model.addAttribute("plans", plans);
        model.addAttribute("selectedPlan", selected);
        return "signup/signup";
    }

    // ============================================================
    // SUBMIT
    // ============================================================
    @PostMapping
    public String submit(@ModelAttribute SignupService.SignupForm form,
                         HttpServletRequest request,
                         Model model,
                         RedirectAttributes redirect) {
        try {
            if (form.getFullName() == null || form.getFullName().isBlank())
                throw new IllegalArgumentException("Full name is required");
            if (form.getEmail() == null || !form.getEmail().contains("@"))
                throw new IllegalArgumentException("A valid email is required");
            if (form.getPassword() == null || form.getPassword().length() < 8)
                throw new IllegalArgumentException("Password must be at least 8 characters");
            if (!form.getPassword().equals(form.getConfirmPassword()))
                throw new IllegalArgumentException("Passwords do not match");
            if (form.getPlanCode() == null || form.getPlanCode().isBlank())
                throw new IllegalArgumentException("Please choose a plan");
            if (form.getRoleIntents() == null || form.getRoleIntents().isEmpty())
                throw new IllegalArgumentException("Please choose at least one account type");

            SignupService.SignupResult result = signupService.requestSignup(form, request);
            log.info("✅ Signup complete: user={}, tenant={}, plan={}, needsApproval={}",
                    result.userId(), result.tenantId(),
                    result.planCode(), result.needsManualApproval());

            if (result.needsManualApproval()) {
                redirect.addFlashAttribute("planName", result.planName());
                // Put BOTH email and signupId in the URL so /signup/pending is refreshable
                return "redirect:/signup/pending?email=" + form.getEmail()
                        + "&signupId=" + result.signupId();
            } else {
                redirect.addFlashAttribute("planName", result.planName());
                // Put signupId in the URL so /signup/payment is refreshable
                return "redirect:/signup/payment?signupId=" + result.signupId();
            }
        } catch (Exception e) {
            log.warn("Signup failed for {}: {}", form.getEmail(), e.getMessage());
            model.addAttribute("error", e.getMessage());
            List<PlatformPlan> plans = planRepository.findByActiveTrueOrderByDisplayOrderAsc();
            model.addAttribute("plans", plans);
            model.addAttribute("selectedPlan",
                    plans.stream()
                            .filter(p -> p.getCode().equalsIgnoreCase(form.getPlanCode()))
                            .findFirst()
                            .orElse(plans.isEmpty() ? null : plans.get(0)));
            model.addAttribute("form", form);
            return "signup/signup";
        }
    }

    // ============================================================
    // PENDING APPROVAL PAGE
    // ============================================================
    @GetMapping("/pending")
    public String pending(@RequestParam(required = false) Long signupId,
                          @RequestParam(required = false) String email,
                          Model model) {

        SignupRequest sr = null;

        if (signupId != null) {
            sr = signupService.getById(signupId);
        }
        if (sr == null && email != null) {
            sr = signupService.getLatestForEmail(email);
        }
        if (sr == null) {
            Object flashEmail = model.getAttribute("pendingEmail");
            if (flashEmail != null) sr = signupService.getLatestForEmail(flashEmail.toString());
        }
        if (sr == null) {
            return "redirect:/signup";
        }

        model.addAttribute("email",    sr.getEmail());
        model.addAttribute("signupId", sr.getId());
        model.addAttribute("planCode", sr.getPlanCode());
        model.addAttribute("roles",    sr.getRoleIntent());
        model.addAttribute("status",   sr.getStatus().name());
        return "signup/pending";
    }

    // ============================================================
    // STATUS API (polled by the pending page)
    // ============================================================
    @GetMapping("/status")
    @ResponseBody
    public Map<String, Object> status(@RequestParam(required = false) Long signupId,
                                      @RequestParam(required = false) String email) {
        Map<String, Object> result = new HashMap<>();

        SignupRequest sr = null;
        if (signupId != null) {
            sr = signupService.getById(signupId);
        } else if (email != null) {
            sr = signupService.getLatestForEmail(email);
        }

        if (sr == null) {
            result.put("found", false);
            return result;
        }

        result.put("found",    true);
        result.put("signupId", sr.getId());
        result.put("status",   sr.getStatus().name());
        result.put("planCode", sr.getPlanCode());
        result.put("rejected", sr.getStatus() == SignupRequest.Status.REJECTED);
        result.put("approved",
                sr.getStatus() == SignupRequest.Status.APPROVED_AWAITING_PAYMENT
                        || sr.getStatus() == SignupRequest.Status.PAID);
        result.put("paid",     sr.getStatus() == SignupRequest.Status.PAID);
        return result;
    }

    // ============================================================
    // PAYMENT PAGE
    // ============================================================
    @GetMapping("/payment")
    public String payment(@RequestParam(required = false) Long signupId,
                          Model model, RedirectAttributes redirect) {

        Long id = signupId;
        if (id == null) {
            Object flash = model.getAttribute("signupId");
            if (flash instanceof Long l) id = l;
            else if (flash instanceof Number n) id = n.longValue();
            else if (flash instanceof String s) {
                try { id = Long.parseLong(s); } catch (Exception ignored) {}
            }
        }
        if (id == null) {
            redirect.addFlashAttribute("error",
                    "Missing signup reference. Please start the signup again.");
            return "redirect:/signup";
        }

        SignupRequest sr = signupService.getById(id);
        if (sr == null) {
            redirect.addFlashAttribute("error", "That signup no longer exists.");
            return "redirect:/signup";
        }

        // Route each status to a sensible landing page
        switch (sr.getStatus()) {
            case PAID -> {
                redirect.addFlashAttribute("success",
                        "Payment already complete. Please sign in.");
                return "redirect:/login";
            }
            case PENDING_APPROVAL -> {
                redirect.addFlashAttribute("error",
                        "Your signup is still awaiting approval.");
                return "redirect:/signup/pending?signupId=" + sr.getId()
                        + "&email=" + sr.getEmail();
            }
            case REJECTED -> {
                redirect.addFlashAttribute("error", "This signup was rejected.");
                return "redirect:/signup";
            }
            case APPROVED, APPROVED_AWAITING_PAYMENT -> {
                // fall through — proceed to render the payment page
            }
        }

        PlatformPlan plan = planRepository.findByCode(sr.getPlanCode()).orElse(null);

        // One idempotency key per page load — prevents double-charge on refresh
        String idempotencyKey = "sub-" + sr.getId() + "-" + UUID.randomUUID();

        model.addAttribute("signup", sr);
        model.addAttribute("plan", plan);
        model.addAttribute("idempotencyKey", idempotencyKey);
        return "signup/payment";
    }

    @PostMapping("/payment")
    public String processPayment(@RequestParam Long signupId,
                                 @RequestParam String idempotencyKey,
                                 @RequestParam String cardNumber,
                                 @RequestParam String expiry,
                                 @RequestParam String cvv,
                                 RedirectAttributes redirect) {
        try {
            var tx = subscriptionChargeService.chargeSignup(
                    signupId, idempotencyKey, cardNumber, expiry, cvv);
            redirect.addFlashAttribute("success",
                    "Payment successful. Sign in to access your dashboard.");
            log.info("✅ Signup payment complete — signup={}, tx={}", signupId, tx.getId());
            return "redirect:/login";
        } catch (Exception e) {
            log.warn("Payment failed for signup {}: {}", signupId, e.getMessage());
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/signup/payment?signupId=" + signupId;
        }
    }
}