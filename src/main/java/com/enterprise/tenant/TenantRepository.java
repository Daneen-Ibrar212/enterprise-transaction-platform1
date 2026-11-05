package com.enterprise.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    // ===== Existing =====
    Optional<Tenant> findByName(String name);

    // ===== New: name uniqueness check used by SignupService dedup =====

    /**
     * Spring Data derived query — compiles to:
     *   SELECT EXISTS(SELECT 1 FROM tenant WHERE name = ?)
     * Used in SignupService to append a numeric suffix when a proposed
     * tenant name already exists, avoiding the "tenant_name_key" violation.
     */
    boolean existsByName(String name);

    // Optional case-insensitive variants — use these instead if you want
    // "Acme Ltd" and "acme ltd" to be treated as the same name.

    boolean existsByNameIgnoreCase(String name);
    Optional<Tenant> findByNameIgnoreCase(String name);
}