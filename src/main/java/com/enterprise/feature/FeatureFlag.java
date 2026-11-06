package com.enterprise.feature;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.Filter;

@Entity
@Table(name = "feature_flag", uniqueConstraints = {
    // The ONLY uniqueness constraint is (name, tenant_id). The previous
    // `unique = true` on `name` alone prevented multi-tenancy — only one
    // tenant could ever own a given flag name.
    @UniqueConstraint(columnNames = {"name", "tenant_id"})
})
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class FeatureFlag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean enabled = false;

    private String description;

    @Column(name = "updated_at", updatable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    // Getters and setters (unchanged from before)
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
}