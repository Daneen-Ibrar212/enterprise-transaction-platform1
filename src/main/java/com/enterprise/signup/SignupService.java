package com.enterprise.signup;

import com.enterprise.feature.FeatureFlagService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.Role;
import com.enterprise.identity.RoleRepository;
import com.enterprise.identity.UserRepository;
import com.enterprise.plan.PlatformPlan;
import com.enterprise.plan.PlatformPlanRepository;
import com.enterprise.tenant.Tenant;
import com.enterprise.tenant.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    private static final int MAX_SIGNUPS_PER_IP_PER_HOUR = 5;
    private static final int TRIAL_DAYS = 14;

    /** Roles the public signup form is allowed to grant. */
    private static final List<String> ALLOWED_SIGNUP_ROLES =
            List.of("CUSTOMER", "MERCHANT", "MERCHANT_ADMIN");

    /** Roles that require manual super-admin approval. */
    private static final List<String> MANUAL_APPROVAL_ROLES =
            List.of("MERCHANT", "MERCHANT_ADMIN");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final TenantRepository tenantRepository;
    private final SignupRequestRepository signupRepository;
    private final PlatformPlanRepository planRepository;
    private final PasswordEncoder passwordEncoder;
    private final FeatureFlagService featureFlagService;
    private final ObjectMapper objectMapper;

    public SignupService(UserRepository userRepository,
                         RoleRepository roleRepository,
                         TenantRepository tenantRepository,
                         SignupRequestRepository signupRepository,
                         PlatformPlanRepository planRepository,
                         PasswordEncoder passwordEncoder,
                         FeatureFlagService featureFlagService,
                         ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.signupRepository = signupRepository;
        this.planRepository = planRepository;
        this.passwordEncoder = passwordEncoder;
        this.featureFlagService = featureFlagService;
        this.objectMapper = objectMapper;
    }

    // ============================================================
    // REQUEST SIGNUP
    // ============================================================
    // Creates:
    //   • Tenant (inactive if manual approval needed, active if auto)
    //   • User (inactive if manual approval needed, active if auto)
    //   • SignupRequest with status PENDING_APPROVAL or APPROVED_AWAITING_PAYMENT
    //
    // Returns a result that tells the controller where to redirect.
    // ============================================================
    @Transactional
    public SignupResult requestSignup(SignupForm form, HttpServletRequest request) {
        String email = form.getEmail().trim().toLowerCase();
        String ip = clientIp(request);

        // 1. IP rate limit
        long recent = signupRepository.countByRequestedFromIpAndCreatedAtAfter(
                ip, LocalDateTime.now().minusHours(1));
        if (recent >= MAX_SIGNUPS_PER_IP_PER_HOUR) {
            throw new IllegalStateException("Too many signups from this IP. Try again in an hour.");
        }

        // 2. Validate plan
        PlatformPlan plan = planRepository.findByCode(form.getPlanCode())
                .filter(PlatformPlan::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Invalid plan: " + form.getPlanCode()));

        // 3. Validate role intent
        List<String> requestedRoles = form.getRoleIntents();
        if (requestedRoles == null || requestedRoles.isEmpty()) {
            throw new IllegalArgumentException("Please choose at least one account type");
        }
        List<String> validRoles = requestedRoles.stream()
                .filter(ALLOWED_SIGNUP_ROLES::contains)
                .distinct()
                .toList();
        if (validRoles.isEmpty()) {
            throw new IllegalArgumentException("Invalid account type selection");
        }

        // 4. Duplicate email check
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new IllegalStateException("An account with this email already exists. Please sign in.");
        }

        // 5. Decide auto-approve or manual
        boolean needsManualApproval = validRoles.stream().anyMatch(MANUAL_APPROVAL_ROLES::contains);

        // 6. Tenant name fallback — guaranteed unique
        String tenantName = (form.getCompanyName() != null && !form.getCompanyName().isBlank())
                ? form.getCompanyName().trim()
                : (form.getFullName() != null && !form.getFullName().isBlank())
                    ? form.getFullName().trim() + "'s Workspace"
                    : email + "'s Workspace";

        // Defensive uniqueness: append a suffix if the name is already taken.
        // Prevents the "duplicate key value violates unique constraint tenant_name_key"
        // error when two signups pick the same display name (e.g. "Organisation").
        if (tenantRepository.existsByName(tenantName)) {
            String base = tenantName;
            int n = 1;
            while (tenantRepository.existsByName(tenantName)) {
                n++;
                tenantName = base + " (" + n + ")";
            }
        }

        // 7. Create tenant
        Tenant tenant = new Tenant();
        tenant.setName(tenantName);
        tenant.setDescription("Tenant for " + email);
        tenant.setBaseCurrency("GBP");
        tenant.setPlanCode(plan.getCode());
        tenant.setSignupAt(LocalDateTime.now());
        tenant.setCreatedAt(LocalDateTime.now());
        tenant.setSubscriptionStatus("TRIAL");
        tenant.setTrialEndsAt(LocalDateTime.now().plusDays(TRIAL_DAYS));
        tenant.setActive(!needsManualApproval);   // customers active immediately

        tenant = tenantRepository.save(tenant);
        log.info("✅ Created tenant {} ({}) for signup {} — active={}",
                tenant.getId(), tenant.getName(), email, tenant.isActive());

        // 8. Seed feature flags — runs in the SAME transaction as the tenant
        //    insert above, so the FK check on feature_flag.tenant_id sees the
        //    new tenant row. If we used REQUIRES_NEW here, the new transaction
        //    would not see the uncommitted tenant and every insert would fail
        //    with an FK violation, silently leaving the tenant with zero flags.
        seedFeatureFlagsForPlan(tenant.getId(), plan);

        // 9. Create user
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        user.setTenantId(tenant.getId());
        user.setSuperAdmin(false);
        user.setActive(!needsManualApproval);     // customers active immediately

        Set<Role> roles = new HashSet<>();
        for (String roleName : validRoles) {
            Role r = roleRepository.findByName(roleName)
                    .orElseThrow(() -> new IllegalStateException("Role not found: " + roleName));
            roles.add(r);
        }
        user.setRoles(roles);
        user = userRepository.save(user);
        log.info("✅ Created user {} (tenant {}) with roles {}",
                user.getId(), tenant.getId(), validRoles);

        // 10. Create signup request
        SignupRequest sr = new SignupRequest();
        sr.setUserId(user.getId());
        sr.setTenantId(tenant.getId());
        sr.setFullName(form.getFullName());
        sr.setEmail(email);
        sr.setCompanyName(form.getCompanyName());
        sr.setCountryCode(form.getCountryCode());
        sr.setPlanCode(plan.getCode());
        sr.setRoleIntent(String.join(",", validRoles));
        sr.setRequestedFromIp(ip);
        sr.setCreatedAt(LocalDateTime.now());

        if (needsManualApproval) {
            sr.setStatus(SignupRequest.Status.PENDING_APPROVAL);
        } else {
            // Customer-only — auto-approve but still requires payment
            sr.setStatus(SignupRequest.Status.APPROVED_AWAITING_PAYMENT);
            sr.setApprovedAt(LocalDateTime.now());
        }
        sr = signupRepository.save(sr);
        log.info("✅ SignupRequest {} created (status={}) for user {}",
                sr.getId(), sr.getStatus(), user.getId());

        return new SignupResult(
                user.getId(),
                tenant.getId(),
                sr.getId(),
                plan.getCode(),
                plan.getName(),
                needsManualApproval
        );
    }

    // ============================================================
    // APPROVE (Super Admin)
    // ============================================================
    @Transactional
    public void approveSignup(Long signupId, Long adminId, String notes) {
        SignupRequest sr = signupRepository.findById(signupId)
                .orElseThrow(() -> new IllegalArgumentException("Signup not found"));

        if (sr.getStatus() != SignupRequest.Status.PENDING_APPROVAL) {
            throw new IllegalStateException("Signup is not pending approval");
        }

        sr.setStatus(SignupRequest.Status.APPROVED_AWAITING_PAYMENT);
        sr.setApprovedAt(LocalDateTime.now());
        sr.setApprovedBy(adminId);
        sr.setReviewedBy(adminId);
        sr.setReviewedAt(LocalDateTime.now());
        sr.setReviewNotes(notes);
        sr.setUpdatedAt(LocalDateTime.now());
        signupRepository.save(sr);

        // Activate tenant + user
        tenantRepository.findById(sr.getTenantId()).ifPresent(t -> {
            t.setActive(true);
            t.setUpdatedAt(LocalDateTime.now());
            tenantRepository.save(t);
        });
        userRepository.findById(sr.getUserId()).ifPresent(u -> {
            u.setActive(true);
            userRepository.save(u);
        });

        log.info("✅ Signup {} APPROVED by admin {} — awaiting payment", signupId, adminId);
    }

    // ============================================================
    // REJECT
    // ============================================================
    @Transactional
    public void rejectSignup(Long signupId, Long adminId, String reason) {
        SignupRequest sr = signupRepository.findById(signupId)
                .orElseThrow(() -> new IllegalArgumentException("Signup not found"));

        sr.setStatus(SignupRequest.Status.REJECTED);
        sr.setReviewedBy(adminId);
        sr.setReviewedAt(LocalDateTime.now());
        sr.setReviewNotes(reason);
        sr.setUpdatedAt(LocalDateTime.now());
        signupRepository.save(sr);

        userRepository.findById(sr.getUserId()).ifPresent(u -> {
            u.setActive(false);
            userRepository.save(u);
        });
        tenantRepository.findById(sr.getTenantId()).ifPresent(t -> {
            t.setActive(false);
            t.setUpdatedAt(LocalDateTime.now());
            tenantRepository.save(t);
        });

        log.info("Signup {} REJECTED by admin {}: {}", signupId, adminId, reason);
    }

    // ============================================================
    // SEED FEATURE FLAGS
    // ============================================================
    // NOTE: this method intentionally uses REQUIRED (not REQUIRES_NEW) so it
    // runs in the caller's transaction. The caller (requestSignup) has just
    // saved a new tenant row in the same transaction; if we started a new
    // transaction here, the FK check on feature_flag.tenant_id would fail
    // because the new transaction cannot see the uncommitted tenant.
    @Transactional(propagation = Propagation.REQUIRED)
    public void seedFeatureFlagsForPlan(Long tenantId, PlatformPlan plan) {
        // 1. Provision the platform defaults (the base set every tenant gets).
        try {
            featureFlagService.provisionDefaultsForTenant(tenantId);
        } catch (Exception e) {
            // If this fails, the tenant will have no flags and the admin
            // Feature Toggles page will show "No feature flags defined".
            // Fail loudly so we don't end up with half-provisioned tenants.
            log.error("FATAL: could not provision default feature flags for tenant {}",
                    tenantId, e);
            throw new IllegalStateException(
                    "Feature-flag provisioning failed for tenant " + tenantId, e);
        }

        // 2. Apply plan-level overrides (e.g. PRO plan may enable advanced flags).
        if (plan.getFeatureFlagsJson() == null) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Boolean> overrides =
                    objectMapper.readValue(plan.getFeatureFlagsJson(), Map.class);

            for (Map.Entry<String, Boolean> e : overrides.entrySet()) {
                try {
                    featureFlagService.setEnabledForTenant(e.getKey(), e.getValue(), tenantId);
                } catch (Exception ex) {
                    // A single unknown flag in the plan shouldn't fail the whole signup.
                    // Log at WARN so we notice, but continue with the rest.
                    log.warn("Plan override flag '{}' could not be set for tenant {}: {}",
                            e.getKey(), tenantId, ex.getMessage());
                }
            }
            log.info("✅ Applied {} feature-flag overrides for tenant {}",
                    overrides.size(), tenantId);
        } catch (Exception e) {
            log.warn("Failed to parse feature flags JSON for plan {}: {}",
                    plan.getCode(), e.getMessage());
        }
    }

    // ============================================================
    // LOOKUPS
    // ============================================================
    public List<SignupRequest> listPending() {
        return signupRepository.findByStatusOrderByCreatedAtDesc(
                SignupRequest.Status.PENDING_APPROVAL);
    }

    public List<SignupRequest> listAll() {
        return signupRepository.findAllByOrderByCreatedAtDesc();
    }

    public SignupRequest getById(Long id) {
        return signupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Signup not found"));
    }

    public SignupRequest getLatestForEmail(String email) {
        return signupRepository.findFirstByEmailIgnoreCaseOrderByCreatedAtDesc(email)
                .orElse(null);
    }

    public List<PlatformPlan> activePlans() {
        return planRepository.findByActiveTrueOrderByDisplayOrderAsc();
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private String clientIp(HttpServletRequest request) {
        if (request == null) return "unknown";
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        if (ip != null && ip.contains(",")) ip = ip.split(",")[0].trim();
        return ip != null ? ip : "unknown";
    }

    // ============================================================
    // DTOs
    // ============================================================
    public static class SignupForm {
        private String fullName;
        private String email;
        private String password;
        private String confirmPassword;
        private String companyName;
        private String countryCode;
        private String planCode;
        private List<String> roleIntents = new ArrayList<>();

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getConfirmPassword() { return confirmPassword; }
        public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
        public String getCompanyName() { return companyName; }
        public void setCompanyName(String companyName) { this.companyName = companyName; }
        public String getCountryCode() { return countryCode; }
        public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
        public String getPlanCode() { return planCode; }
        public void setPlanCode(String planCode) { this.planCode = planCode; }
        public List<String> getRoleIntents() { return roleIntents; }
        public void setRoleIntents(List<String> roleIntents) { this.roleIntents = roleIntents; }
    }

    public record SignupResult(
            Long userId,
            Long tenantId,
            Long signupId,
            String planCode,
            String planName,
            boolean needsManualApproval
    ) {}
}