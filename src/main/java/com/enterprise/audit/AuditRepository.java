package com.enterprise.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface AuditRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    List<AuditEvent> findByUserIdOrderByCreatedAtAsc(Long userId);
    Optional<AuditEvent> findFirstByOrderByCreatedAtDesc();
    Optional<AuditEvent> findFirstByTenantIdOrderByCreatedAtDesc(Long tenantId);

    @Query("SELECT DISTINCT a.eventType FROM AuditEvent a WHERE a.tenantId = :tenantId ORDER BY a.eventType")
    List<String> findDistinctEventTypesByTenantId(@Param("tenantId") Long tenantId);

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByCreatedAtAsc(String entityType, Long entityId);
    Page<AuditEvent> findByTenantIdOrderByCreatedAtAsc(Long tenantId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuditEvent> findFirstByEntityTypeAndEntityIdOrderByIdDesc(String entityType, Long entityId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "SELECT * FROM audit_event WHERE entity_type = :entityType AND entity_id = :entityId ORDER BY id DESC LIMIT 1", nativeQuery = true)
    Optional<AuditEvent> findLastEventWithLock(@Param("entityType") String entityType,
                                                @Param("entityId") Long entityId);

    @Query("SELECT a FROM AuditEvent a WHERE a.tenantId = :tenantId")
    List<AuditEvent> findAllByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(a) FROM AuditEvent a WHERE a.tenantId = :tenantId")
    long countByTenantId(@Param("tenantId") Long tenantId);

    // ✅ Bounded verification: last N events per tenant
    @Query("SELECT a FROM AuditEvent a WHERE a.tenantId = :tenantId ORDER BY a.id DESC")
    List<AuditEvent> findRecentByTenantId(@Param("tenantId") Long tenantId, Pageable pageable);
}