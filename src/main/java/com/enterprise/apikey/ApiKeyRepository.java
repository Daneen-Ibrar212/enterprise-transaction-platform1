package com.enterprise.apikey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    @Query("SELECT k FROM ApiKey k WHERE k.active = true AND (k.expiresAt IS NULL OR k.expiresAt > CURRENT_TIMESTAMP)")
    List<ApiKey> findActiveKeys();

    // ✅ FAST path: single indexed lookup by SHA-256
    Optional<ApiKey> findByKeyHashSha256AndActiveTrue(String keyHashSha256);

    // ✅ Global lookup (bypasses tenant filter) — used by the API filter before
    //    a tenant is set. Scoped to active keys only.
    @Query(value = "SELECT * FROM api_key WHERE key_hash_sha256 = :hash AND active = true", nativeQuery = true)
    Optional<ApiKey> findByKeyHashSha256Global(@Param("hash") String hash);

    List<ApiKey> findByUserId(Long userId);

    Optional<ApiKey> findByKeyHash(String keyHash);

    Optional<ApiKey> findByKeyPrefix(String keyPrefix);

    @Query("SELECT k FROM ApiKey k WHERE k.tenantId = :tenantId")
    List<ApiKey> findAllByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(k) FROM ApiKey k WHERE k.tenantId = :tenantId")
    long countByTenantId(@Param("tenantId") Long tenantId);
}