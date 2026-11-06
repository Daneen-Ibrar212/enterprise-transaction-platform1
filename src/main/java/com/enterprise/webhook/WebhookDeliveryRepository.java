package com.enterprise.webhook;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    Page<WebhookDelivery> findByWebhookConfigIdOrderByCreatedAtDesc(Long configId, Pageable pageable);
    List<WebhookDelivery> findByWebhookConfigIdOrderByCreatedAtDesc(Long configId);
    List<WebhookDelivery> findBySuccessFalseAndAttemptNumberLessThan(int maxAttempts);
    Optional<WebhookDelivery> findFirstByWebhookConfigIdOrderByCreatedAtDesc(Long configId);
    List<WebhookDelivery> findByReplayOfId(Long originalDeliveryId);
    List<WebhookDelivery> findByIsReplayTrueOrderByCreatedAtDesc();
    Page<WebhookDelivery> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // ✅ Tenant-scoped queries
    Page<WebhookDelivery> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    // ✅ Ownership check — used by controllers before acting on a delivery
    boolean existsByIdAndTenantId(Long id, Long tenantId);

    @Query("SELECT COUNT(d) FROM WebhookDelivery d WHERE d.webhookConfigId = :configId AND d.success = true")
    long countSuccessByConfigId(@Param("configId") Long configId);

    @Query("SELECT COUNT(d) FROM WebhookDelivery d WHERE d.webhookConfigId = :configId AND d.success = false")
    long countFailureByConfigId(@Param("configId") Long configId);

    @Query("SELECT COUNT(d) FROM WebhookDelivery d WHERE d.webhookConfigId = :configId")
    long countAllByConfigId(@Param("configId") Long configId);

    @Query("SELECT d FROM WebhookDelivery d WHERE d.success = false AND d.attemptNumber < :maxAttempts AND d.createdAt > :since ORDER BY d.createdAt DESC")
    List<WebhookDelivery> findRecentFailures(@Param("maxAttempts") int maxAttempts,
                                              @Param("since") LocalDateTime since);
}