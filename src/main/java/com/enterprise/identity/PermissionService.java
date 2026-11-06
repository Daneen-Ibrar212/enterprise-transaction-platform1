package com.enterprise.identity;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves `resource:action` permission tokens for the current user.
 *
 * Note: the primary permission check path is CustomPermissionEvaluator
 * (used via `@PreAuthorize("hasPermission(...)")`). This service is the
 * programmatic equivalent for cases where you need to check inside a
 * service method.
 */
@Service
public class PermissionService {

    private final UserRepository userRepository;

    public PermissionService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean hasPermission(String resource, String action) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return false;
        }
        return userRepository.findByEmail(auth.getName())
                .map(user -> user.getRoles().stream()
                        .flatMap(role -> role.getPermissions().stream())
                        .anyMatch(p -> p.getResource().equals(resource) && p.getAction().equals(action)))
                .orElse(false);
    }

    /** All `resource:action` tokens held by the current user. */
    public Set<String> getPermissionTokens() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Set.of();
        }
        return userRepository.findByEmail(auth.getName())
                .map(user -> user.getRoles().stream()
                        .flatMap(role -> role.getPermissions().stream())
                        .map(p -> p.getResource() + ":" + p.getAction())
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }
}