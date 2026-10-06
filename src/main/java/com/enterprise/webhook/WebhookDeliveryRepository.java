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

    // ============================================================
    // ✅ Paginated by config
    // ============================================================
    Page<WebhookDelivery> findByWebhookConfigIdOrderByCreatedAtDesc(Long configId, Pageable pageable);

    // ============================================================
    // ✅ Non-paginated by config
    // ============================================================
    List<WebhookDelivery> findByWebhookConfigIdOrderByCreatedAtDesc(Long configId);

    // ============================================================
    // ✅ Scheduled retry of failed deliveries
    // ============================================================
    List<WebhookDelivery> findBySuccessFalseAndAttemptNumberLessThan(int maxAttempts);

    // ============================================================
    // ✅ NEW: Find most recent delivery for a config (for retry/replay)
    // ============================================================
    Optional<WebhookDelivery> findFirstByWebhookConfigIdOrderByCreatedAtDesc(Long configId);

    // ============================================================
    // ✅ NEW: Replay-specific queries
    // ============================================================
    List<WebhookDelivery> findByReplayOfId(Long originalDeliveryId);

    List<WebhookDelivery> findByIsReplayTrueOrderByCreatedAtDesc();

    // ============================================================
    // ✅ NEW: Find all deliveries (paginated, tenant aware via filter)
    // ============================================================
    Page<WebhookDelivery> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // ============================================================
    // ✅ NEW: Analytics
    // ============================================================
    @Query("SELECT COUNT(d) FROM WebhookDelivery d WHERE d.webhookConfigId = :configId AND d.success = true")
    long countSuccessByConfigId(@Param("configId") Long configId);

    @Query("SELECT COUNT(d) FROM WebhookDelivery d WHERE d.webhookConfigId = :configId AND d.success = false")
    long countFailureByConfigId(@Param("configId") Long configId);

    @Query("SELECT COUNT(d) FROM WebhookDelivery d WHERE d.webhookConfigId = :configId")
    long countAllByConfigId(@Param("configId") Long configId);

    // ============================================================
    // ✅ NEW: Find recent failures for retry scheduler
    // ============================================================
    @Query("SELECT d FROM WebhookDelivery d WHERE d.success = false AND d.attemptNumber < :maxAttempts AND d.createdAt > :since ORDER BY d.createdAt DESC")
    List<WebhookDelivery> findRecentFailures(@Param("maxAttempts") int maxAttempts,
                                             @Param("since") LocalDateTime since);
}