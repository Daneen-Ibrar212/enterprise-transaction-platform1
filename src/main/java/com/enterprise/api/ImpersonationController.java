package com.enterprise.api;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/users")
public class ImpersonationController {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationController.class);

    private final UserRepository userRepository;
    private final SecurityContextRepository securityContextRepository;

    public ImpersonationController(UserRepository userRepository,
                                   SecurityContextRepository securityContextRepository) {
        this.userRepository = userRepository;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/impersonate")
    public String impersonatePost(@RequestParam String username,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        return impersonate(username, request, response);
    }

    @GetMapping("/impersonate")
    public String impersonateGet(@RequestParam String username,
                                 HttpServletRequest request,
                                 HttpServletResponse response) {
        return impersonate(username, request, response);
    }

    private String impersonate(String username,
                               HttpServletRequest request,
                               HttpServletResponse response) {

        String trimmedUsername = username.trim();
        log.info("🔍 Impersonation requested for username: '{}' (length: {})",
                trimmedUsername, trimmedUsername.length());

        AppUser targetUser = userRepository.findByEmailIgnoreCaseGlobal(trimmedUsername)
                .orElseThrow(() -> {
                    log.error("❌ User not found for email: '{}'", trimmedUsername);
                    return new RuntimeException("User not found");
                });

        log.info("✅ Target user found: {} (ID: {}, tenant: {}, active: {}, 2FA: {})",
                targetUser.getEmail(), targetUser.getId(), targetUser.getTenantId(),
                targetUser.isActive(), targetUser.isTwoFactorEnabled());

        if (!targetUser.isActive()) {
            log.warn("⛔ Target user is inactive: {}", targetUser.getEmail());
            throw new RuntimeException("User account is disabled");
        }

        // Optional safety: don't allow impersonating yourself (would clobber the original)
        Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
        if (currentAuth != null
                && currentAuth.getName().equalsIgnoreCase(targetUser.getEmail())) {
            log.warn("⚠️ Refusing to impersonate self: {}", targetUser.getEmail());
            return "redirect:/dashboard";
        }

        // Optional: tenant guard for non-super-admins
        AppUser actor = userRepository.findByEmailIgnoreCaseGlobal(currentAuth.getName())
                .orElseThrow(() -> new RuntimeException("Actor not found"));
        boolean isSuperAdmin = actor.isSuperAdmin();
        if (!isSuperAdmin && !targetUser.getTenantId().equals(actor.getTenantId())) {
            log.warn("⛔ Tenant admin tried to impersonate cross-tenant user: {} -> {}",
                    actor.getEmail(), targetUser.getEmail());
            throw new org.springframework.security.access.AccessDeniedException(
                    "You cannot impersonate users outside your tenant");
        }

        // Build UserDetails for the target
        UserDetails userDetails = User.withUsername(targetUser.getEmail())
                .password(targetUser.getPasswordHash() != null ? targetUser.getPasswordHash() : "")
                .authorities(targetUser.getRoles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
                        .collect(Collectors.toList()))
                .build();

        UsernamePasswordAuthenticationToken newAuth = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        newAuth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        // Save the original so exit-impersonation can restore it
        HttpSession session = request.getSession();
        session.setAttribute("ORIGINAL_AUTH", currentAuth);

        // ★ Replace the context object — do not mutate the current one
        SecurityContext newContext = SecurityContextHolder.createEmptyContext();
        newContext.setAuthentication(newAuth);
        SecurityContextHolder.setContext(newContext);

        // ★ CRITICAL: persist to the Spring-Security-managed repository
        securityContextRepository.saveContext(newContext, request, response);

        log.info("🔄 Impersonation successful: {} -> {}",
                currentAuth.getName(), targetUser.getEmail());
        return "redirect:/dashboard";
    }

    @GetMapping("/exit-impersonation")
    public String exitImpersonation(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return "redirect:/login";
        }

        Authentication originalAuth = (Authentication) session.getAttribute("ORIGINAL_AUTH");
        if (originalAuth == null) {
            log.warn("Exit impersonation called but no ORIGINAL_AUTH in session");
            return "redirect:/dashboard";
        }

        SecurityContext restored = SecurityContextHolder.createEmptyContext();
        restored.setAuthentication(originalAuth);
        SecurityContextHolder.setContext(restored);
        securityContextRepository.saveContext(restored, request, response);

        session.removeAttribute("ORIGINAL_AUTH");

        log.info("🔙 Exited impersonation, restored original auth: {}", originalAuth.getName());
        return "redirect:/dashboard";
    }
}