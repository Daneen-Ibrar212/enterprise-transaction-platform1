package com.enterprise.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WebhookRepository extends JpaRepository<WebhookConfig, Long> {

    List<WebhookConfig> findByEventTypeAndActiveTrue(String eventType);
    List<WebhookConfig> findByActiveTrue();

    boolean existsByTenantIdAndEventTypeAndUrl(Long tenantId, String eventType, String url);

    boolean existsByTenantIdAndEventTypeAndUrlAndIdNot(
            Long tenantId, String eventType, String url, Long id);

    @Query(value = "SELECT * FROM webhook_config", nativeQuery = true)
    List<WebhookConfig> findAllWithoutTenantFilter();

    @Query("SELECT w FROM WebhookConfig w WHERE w.tenantId = :tenantId")
    List<WebhookConfig> findAllByTenantId(@Param("tenantId") Long tenantId);
}