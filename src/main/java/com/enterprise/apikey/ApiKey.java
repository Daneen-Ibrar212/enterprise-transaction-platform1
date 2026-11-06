package com.enterprise.apikey;

import com.enterprise.identity.AppUser;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "api_key", uniqueConstraints = {
    // key_hash_sha256 is the fast lookup index (SHA-256, not BCrypt)
    @UniqueConstraint(columnNames = {"key_hash_sha256"})
}, indexes = {
    @Index(name = "idx_api_key_tenant", columnList = "tenant_id"),
    @Index(name = "idx_api_key_prefix", columnList = "key_prefix")
})
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Display-only masked value. NEVER the full key.
     * Example: "ep_abcd...wxyz"  (first 8 + last 4)
     */
    @Column(name = "key_value", nullable = false)
    private String keyValue;

    /**
     * ✅ FAST lookup index. SHA-256 of the raw key. Used by validateKey()
     * to find the one candidate row instead of scanning every active key.
     */
    @Column(name = "key_hash_sha256", nullable = false, unique = true, length = 64)
    private String keyHashSha256;

    /**
     * BCrypt hash — secondary verification. NEVER indexed; BCrypt is too slow
     * to scan. Only used after SHA-256 has narrowed to one row.
     */
    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    public ApiKey() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKeyValue() { return keyValue; }
    public void setKeyValue(String keyValue) { this.keyValue = keyValue; }
    public String getKeyHashSha256() { return keyHashSha256; }
    public void setKeyHashSha256(String keyHashSha256) { this.keyHashSha256 = keyHashSha256; }
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
}