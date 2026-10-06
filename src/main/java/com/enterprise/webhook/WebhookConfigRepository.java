package com.enterprise.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, Long> {

    // ============================================================
    // ✅ Return a LIST — never fails even if duplicates exist
    // ============================================================
    List<WebhookConfig> findByTenantIdAndEventType(Long tenantId, String eventType);

    // ============================================================
    // ✅ If you need ONE result, use findFirst
    // ============================================================
    Optional<WebhookConfig> findFirstByTenantIdAndEventType(Long tenantId, String eventType);

    // ============================================================
    // ✅ Tenant-aware active lookups
    // ============================================================
    List<WebhookConfig> findByTenantIdAndActiveTrue(Long tenantId);

    @Query("SELECT w FROM WebhookConfig w WHERE w.tenantId = :tenantId AND w.eventType = :eventType AND w.active = true")
    List<WebhookConfig> findActiveByTenantAndEvent(@Param("tenantId") Long tenantId,
                                                    @Param("eventType") String eventType);

    // ============================================================
    // ✅ Find all for a tenant
    // ============================================================
    List<WebhookConfig> findByTenantId(Long tenantId);

    // ============================================================
    // ✅ Find by event type and active
    // ============================================================
    List<WebhookConfig> findByEventTypeAndActiveTrue(String eventType);

    // ============================================================
    // ✅ Find all active
    // ============================================================
    List<WebhookConfig> findByActiveTrue();

    // ============================================================
    // ✅ Check for duplicate before creating
    // ============================================================
    @Query("SELECT COUNT(w) > 0 FROM WebhookConfig w WHERE w.tenantId = :tenantId AND w.eventType = :eventType AND w.id <> :excludeId")
    boolean existsDuplicate(@Param("tenantId") Long tenantId,
                            @Param("eventType") String eventType,
                            @Param("excludeId") Long excludeId);
}