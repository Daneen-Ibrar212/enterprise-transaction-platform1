package com.enterprise.api;

import com.enterprise.audit.AuditService;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * ✅ SECURITY:
 *  - POST only (previously GET allowed — CSRF via <img>)
 *  - Every impersonation start/stop is audited
 *  - Cross-tenant check uses ROLE_SUPER_ADMIN authority (not just entity flag)
 */
@Controller
@RequestMapping("/admin/users")
public class ImpersonationController {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationController.class);

    private final UserRepository userRepository;
    private final SecurityContextRepository securityContextRepository;
    private final AuditService auditService;

    public ImpersonationController(UserRepository userRepository,
                                   SecurityContextRepository securityContextRepository,
                                   AuditService auditService) {
        this.userRepository = userRepository;
        this.securityContextRepository = securityContextRepository;
        this.auditService = auditService;
    }

    /** ✅ POST only — GET is intentionally removed. */
    @PostMapping("/impersonate")
    public String impersonate(@RequestParam String username,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              RedirectAttributes redirect) {
        String trimmedUsername = username.trim();
        Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
        if (currentAuth == null || !currentAuth.isAuthenticated()) {
            throw new org.springframework.security.access.AccessDeniedException("Not authenticated");
        }

        AppUser targetUser = userRepository.findByEmailIgnoreCaseGlobal(trimmedUsername)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!targetUser.isActive()) {
            throw new RuntimeException("User account is disabled");
        }
        if (currentAuth.getName().equalsIgnoreCase(targetUser.getEmail())) {
            redirect.addFlashAttribute("error", "Cannot impersonate yourself");
            return "redirect:/dashboard";
        }

        AppUser actor = userRepository.findByEmailIgnoreCaseGlobal(currentAuth.getName())
                .orElseThrow(() -> new RuntimeException("Actor not found"));

        // Super-admin detection via AUTHORITY (reliable), not just entity flag
        boolean isSuperAdmin = currentAuth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()))
                || actor.isSuperAdmin();

        if (!isSuperAdmin && !targetUser.getTenantId().equals(actor.getTenantId())) {
            log.warn("Tenant admin tried to impersonate cross-tenant: {} -> {}",
                    actor.getEmail(), targetUser.getEmail());
            throw new org.springframework.security.access.AccessDeniedException(
                    "You cannot impersonate users outside your tenant");
        }

        UserDetails userDetails = User.withUsername(targetUser.getEmail())
                .password(targetUser.getPasswordHash() != null ? targetUser.getPasswordHash() : "")
                .authorities(targetUser.getRoles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
                        .collect(Collectors.toList()))
                .build();

        UsernamePasswordAuthenticationToken newAuth =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        newAuth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        HttpSession session = request.getSession();
        session.setAttribute("ORIGINAL_AUTH", currentAuth);

        SecurityContext newContext = SecurityContextHolder.createEmptyContext();
        newContext.setAuthentication(newAuth);
        SecurityContextHolder.setContext(newContext);
        securityContextRepository.saveContext(newContext, request, response);

        // ✅ Audit trail
        try {
            auditService.recordEvent(
                    "IMPERSONATION_STARTED",
                    actor.getId(),
                    Map.of(
                            "actorEmail", actor.getEmail(),
                            "targetEmail", targetUser.getEmail(),
                            "targetTenant", targetUser.getTenantId()
                    ),
                    "User", targetUser.getId(), null, null);
        } catch (Exception e) {
            log.warn("Failed to audit impersonation start: {}", e.getMessage());
        }

        log.info("Impersonation started: {} -> {}", actor.getEmail(), targetUser.getEmail());
        return "redirect:/dashboard";
    }

    @PostMapping("/exit-impersonation")
    public String exitImpersonation(HttpServletRequest request,
                                    HttpServletResponse response,
                                    RedirectAttributes redirect) {
        HttpSession session = request.getSession(false);
        if (session == null) return "redirect:/login";

        Authentication originalAuth = (Authentication) session.getAttribute("ORIGINAL_AUTH");
        if (originalAuth == null) {
            redirect.addFlashAttribute("error", "Not currently impersonating");
            return "redirect:/dashboard";
        }

        Authentication impersonated = SecurityContextHolder.getContext().getAuthentication();

        SecurityContext restored = SecurityContextHolder.createEmptyContext();
        restored.setAuthentication(originalAuth);
        SecurityContextHolder.setContext(restored);
        securityContextRepository.saveContext(restored, request, response);

        session.removeAttribute("ORIGINAL_AUTH");

        try {
            auditService.recordEvent(
                    "IMPERSONATION_ENDED",
                    null,
                    Map.of(
                            "restoredTo", originalAuth.getName(),
                            "wasImpersonating", impersonated != null ? impersonated.getName() : "unknown"
                    ),
                    null, null, null, null);
        } catch (Exception e) {
            log.warn("Failed to audit impersonation end: {}", e.getMessage());
        }

        log.info("Impersonation ended, restored {}", originalAuth.getName());
        return "redirect:/dashboard";
    }
}