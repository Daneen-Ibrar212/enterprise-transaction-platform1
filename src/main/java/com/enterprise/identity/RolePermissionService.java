package com.enterprise.identity;

import com.enterprise.audit.RuleAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RolePermissionService {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionService.class);

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final RuleAuditService ruleAuditService;

    public RolePermissionService(RoleRepository roleRepository,
                                 PermissionRepository permissionRepository,
                                 RolePermissionRepository rolePermissionRepository,
                                 RuleAuditService ruleAuditService) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.ruleAuditService = ruleAuditService;
    }

    /**
     * Return a Set of permission IDs each role currently holds.
     * Map: roleId -> Set<permissionId>
     */
    public Map<Long, Set<Long>> getMatrix() {
        List<RolePermission> all = rolePermissionRepository.findAll();
        Map<Long, Set<Long>> matrix = new HashMap<>();
        for (Role role : roleRepository.findAll()) {
            matrix.put(role.getId(), new HashSet<>());
        }
        for (RolePermission rp : all) {
            matrix.computeIfAbsent(rp.getRoleId(), k -> new HashSet<>())
                  .add(rp.getPermissionId());
        }
        return matrix;
    }

    /**
     * Replace all permissions for a role with the given set.
     * Writes one rule_audit entry per added / removed pair.
     */
    @Transactional
    public void updateRolePermissions(Long roleId, Set<Long> permissionIds, Long adminId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RuntimeException("Role not found: " + roleId));

        // Safety: SUPER_ADMIN must keep everything
        if ("SUPER_ADMIN".equals(role.getName())) {
            throw new IllegalStateException(
                "SUPER_ADMIN permissions cannot be modified (lockout protection).");
        }

        // Safety: ADMIN must keep invoice:approve
        if ("ADMIN".equals(role.getName())) {
            Permission approve = permissionRepository
                    .findByResourceAndAction("invoice", "approve")
                    .orElse(null);
            if (approve != null && !permissionIds.contains(approve.getId())) {
                throw new IllegalStateException(
                    "ADMIN role must retain 'invoice:approve' permission.");
            }
        }

        Set<Long> current = rolePermissionRepository.findByRoleId(roleId)
                .stream().map(RolePermission::getPermissionId)
                .collect(Collectors.toSet());

        Set<Long> toAdd = new HashSet<>(permissionIds);
        toAdd.removeAll(current);

        Set<Long> toRemove = new HashSet<>(current);
        toRemove.removeAll(permissionIds);

        // Apply additions
        for (Long pid : toAdd) {
            RolePermission rp = new RolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(pid);
            rp.setGrantedBy(adminId);
            rolePermissionRepository.save(rp);

            Permission p = permissionRepository.findById(pid).orElse(null);
            if (p != null) {
                ruleAuditService.logChange(
                    "PERMISSION",
                    pid,
                    "GRANT",
                    null,
                    Map.of("role", role.getName(), "permission", p.getResource() + ":" + p.getAction()),
                    adminId
                );
            }
        }

        // Apply removals
        for (Long pid : toRemove) {
            RolePermission rp = rolePermissionRepository
                    .findById(new RolePermissionId(roleId, pid)).orElse(null);

            rolePermissionRepository.deleteByRoleIdAndPermissionId(roleId, pid);

            Permission p = permissionRepository.findById(pid).orElse(null);
            if (p != null) {
                ruleAuditService.logChange(
                    "PERMISSION",
                    pid,
                    "REVOKE",
                    Map.of("role", role.getName(), "permission", p.getResource() + ":" + p.getAction()),
                    null,
                    adminId
                );
            }
        }

        log.info("✅ Updated permissions for role {} ({}): +{} / -{}",
                role.getName(), roleId, toAdd.size(), toRemove.size());
    }

    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }

    public List<Permission> getAllPermissions() {
        return permissionRepository.findAll();
    }
}