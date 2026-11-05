package com.enterprise.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionEventRepository extends JpaRepository<SubscriptionEvent, Long> {

    List<SubscriptionEvent> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<SubscriptionEvent> findTop20ByTenantIdOrderByCreatedAtDesc(Long tenantId);
}