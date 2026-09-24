package com.enterprise.identity;

import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "app_user")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_role",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "secret_key")
    private String secretKey;

    @Column(name = "two_factor_enabled")
    private boolean twoFactorEnabled = false;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "super_admin", nullable = false)
    private boolean superAdmin = false;

    @Transient
    private String tenantName;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "account_locked", nullable = false)
    private boolean accountLocked = false;

    @Column(name = "lock_expiry")
    private LocalDateTime lockExpiry;

    // ============================================================
    // 2FA ADMIN OVERRIDE FIELDS
    // ============================================================
    @Column(name = "two_factor_disabled_by_admin", nullable = false)
    private boolean twoFactorDisabledByAdmin = false;

    @Column(name = "two_factor_disabled_at")
    private LocalDateTime twoFactorDisabledAt;

    @Column(name = "two_factor_disabled_by")
    private Long twoFactorDisabledBy;

    @Column(name = "two_factor_disable_expires_at")
    private LocalDateTime twoFactorDisableExpiresAt;

    @Column(name = "two_factor_disable_reason", columnDefinition = "TEXT")
    private String twoFactorDisableReason;

    // ----- Getters and Setters -----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public boolean isTwoFactorEnabled() { return twoFactorEnabled; }
    public void setTwoFactorEnabled(boolean twoFactorEnabled) { this.twoFactorEnabled = twoFactorEnabled; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public boolean isSuperAdmin() { return superAdmin; }
    public void setSuperAdmin(boolean superAdmin) { this.superAdmin = superAdmin; }
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public void setFailedLoginAttempts(int failedLoginAttempts) { this.failedLoginAttempts = failedLoginAttempts; }
    public boolean isAccountLocked() { return accountLocked; }
    public void setAccountLocked(boolean accountLocked) { this.accountLocked = accountLocked; }
    public LocalDateTime getLockExpiry() { return lockExpiry; }
    public void setLockExpiry(LocalDateTime lockExpiry) { this.lockExpiry = lockExpiry; }

    public boolean isTwoFactorDisabledByAdmin() { return twoFactorDisabledByAdmin; }
    public void setTwoFactorDisabledByAdmin(boolean twoFactorDisabledByAdmin) { this.twoFactorDisabledByAdmin = twoFactorDisabledByAdmin; }

    public LocalDateTime getTwoFactorDisabledAt() { return twoFactorDisabledAt; }
    public void setTwoFactorDisabledAt(LocalDateTime twoFactorDisabledAt) { this.twoFactorDisabledAt = twoFactorDisabledAt; }

    public Long getTwoFactorDisabledBy() { return twoFactorDisabledBy; }
    public void setTwoFactorDisabledBy(Long twoFactorDisabledBy) { this.twoFactorDisabledBy = twoFactorDisabledBy; }

    public LocalDateTime getTwoFactorDisableExpiresAt() { return twoFactorDisableExpiresAt; }
    public void setTwoFactorDisableExpiresAt(LocalDateTime twoFactorDisableExpiresAt) { this.twoFactorDisableExpiresAt = twoFactorDisableExpiresAt; }

    public String getTwoFactorDisableReason() { return twoFactorDisableReason; }
    public void setTwoFactorDisableReason(String twoFactorDisableReason) { this.twoFactorDisableReason = twoFactorDisableReason; }

    /**
     * Returns true if 2FA is effectively disabled (either not enabled OR admin override is active)
     */
    public boolean isTwoFactorEffectivelyDisabled() {
        if (!twoFactorEnabled) return true;
        if (twoFactorDisabledByAdmin) {
            if (twoFactorDisableExpiresAt == null) return true;
            if (twoFactorDisableExpiresAt.isAfter(LocalDateTime.now())) return true;
            // Expired — treat as disabled (scheduler will clean up)
        }
        return false;
    }
}