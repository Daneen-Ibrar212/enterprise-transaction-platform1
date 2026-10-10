package com.enterprise.identity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Explicit join entity for role ↔ permission.
 * Backs the existing `role_permission` table (no schema change needed —
 * the columns already exist; we just promote the @ManyToMany to a real entity).
 */
@Entity
@Table(name = "role_permission")
@IdClass(RolePermissionId.class)
public class RolePermission {

    @Id
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Id
    @Column(name = "permission_id", nullable = false)
    private Long permissionId;

    @Column(name = "granted_at")
    private LocalDateTime grantedAt = LocalDateTime.now();

    @Column(name = "granted_by")
    private Long grantedBy;

    // Getters and setters
    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
    public Long getPermissionId() { return permissionId; }
    public void setPermissionId(Long permissionId) { this.permissionId = permissionId; }
    public LocalDateTime getGrantedAt() { return grantedAt; }
    public void setGrantedAt(LocalDateTime grantedAt) { this.grantedAt = grantedAt; }
    public Long getGrantedBy() { return grantedBy; }
    public void setGrantedBy(Long grantedBy) { this.grantedBy = grantedBy; }
}