package com.enterprise.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final String HASH_ALGORITHM = "SHA-256";

    private final AuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditRepository auditRepository, ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
    }

    @Retryable(
        value = {OptimisticLockingFailureException.class, DataIntegrityViolationException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public AuditEvent recordEvent(String eventType, Long userId, Object details,
                                  String entityType, Long entityId,
                                  Object previousState, Object currentState) {
        try {
            Long tenantId = TenantContext.getRequiredTenantId();
            Long effectiveUserId = (userId != null) ? userId : 0L;
            String detailsJson = objectMapper.writeValueAsString(details);
            String previousJson = previousState != null ? objectMapper.writeValueAsString(previousState) : null;
            String currentJson = currentState != null ? objectMapper.writeValueAsString(currentState) : null;

            AuditEvent lastEvent;
            if (entityType != null && entityId != null) {
                lastEvent = auditRepository
                        .findFirstByEntityTypeAndEntityIdOrderByIdDesc(entityType, entityId)
                        .orElse(null);
            } else {
                lastEvent = auditRepository
                        .findFirstByTenantIdOrderByCreatedAtDesc(tenantId)
                        .orElse(null);
            }

            String previousHash = lastEvent != null ? lastEvent.getCurrentHash() : "";
            String currentHash = hash(previousHash + detailsJson + eventType + effectiveUserId);

            AuditEvent event = new AuditEvent(eventType, effectiveUserId, detailsJson,
                    previousHash, currentHash);
            event.setEntityType(entityType);
            event.setEntityId(entityId);
            event.setPreviousState(previousJson);
            event.setCurrentState(currentJson);
            event.setTenantId(tenantId);
            return auditRepository.save(event);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            log.error("Failed to record audit event", e);
            throw new RuntimeException("Audit recording failed", e);
        }
    }

    @Transactional
    public AuditEvent recordEvent(String eventType, Long userId, Object details) {
        return recordEvent(eventType, userId, details, null, null, null, null);
    }

    @Transactional
    public AuditEvent recordEvent(String eventType, Long userId, Map<String, Object> details,
                                  String entityType, Long entityId) {
        return recordEvent(eventType, userId, details, entityType, entityId, null, null);
    }

    private String hash(String input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
        return HexFormat.of().formatHex(md.digest(input.getBytes()));
    }

    // ============================================================
    // FULL VERIFICATION — expensive, only on explicit /verify endpoints
    // ============================================================
    public boolean verifyChain() {
        Long tenantId = TenantContext.getRequiredTenantId();
        return verifyChainForTenant(tenantId);
    }

    public boolean verifyChainForTenant(Long tenantId) {
        List<AuditEvent> allEvents = auditRepository.findAllByTenantId(tenantId);
        return verifyAll(allEvents, "tenant " + tenantId);
    }

    // ============================================================
    // BOUNDED VERIFICATION — for dashboard list views (last N events only)
    // ============================================================
    public boolean verifyRecentChain(int lookback) {
        Long tenantId = TenantContext.getRequiredTenantId();
        List<AuditEvent> recent = auditRepository.findRecentByTenantId(
                tenantId, PageRequest.of(0, lookback));
        if (recent.isEmpty()) return true;
        // Reverse to chronological order
        java.util.Collections.reverse(recent);
        return verifyAll(recent, "recent (tenant " + tenantId + ", N=" + lookback + ")");
    }

    private boolean verifyAll(List<AuditEvent> events, String label) {
        var byEntity = events.stream()
                .filter(e -> e.getEntityType() != null && e.getEntityId() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        e -> e.getEntityType() + ":" + e.getEntityId()));
        for (var entry : byEntity.entrySet()) {
            List<AuditEvent> chain = entry.getValue();
            chain.sort((a, b) -> a.getId().compareTo(b.getId()));
            if (!verifySingleChain(chain, label + " / entity " + entry.getKey())) return false;
        }
        var global = events.stream()
                .filter(e -> e.getEntityType() == null || e.getEntityId() == null)
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .toList();
        return verifySingleChain(global, label + " / global");
    }

    private boolean verifySingleChain(List<AuditEvent> chain, String label) {
        String expectedPrev = "";
        for (int i = 0; i < chain.size(); i++) {
            AuditEvent e = chain.get(i);
            if (i == 0 && !e.getPreviousHash().isEmpty()) {
                log.warn("Chain {} first event has non-empty previous hash", label);
                return false;
            }
            if (i > 0 && !e.getPreviousHash().equals(expectedPrev)) {
                log.warn("Chain {} broken at event {}", label, e.getId());
                return false;
            }
            try {
                String recomputed = hash(e.getPreviousHash() + e.getDetails()
                        + e.getEventType() + e.getUserId());
                if (!recomputed.equals(e.getCurrentHash())) {
                    log.warn("Chain {} hash mismatch at event {}", label, e.getId());
                    return false;
                }
            } catch (NoSuchAlgorithmException ex) {
                return false;
            }
            expectedPrev = e.getCurrentHash();
        }
        return true;
    }

    public List<AuditEvent> getEventsForUser(Long userId) {
        return auditRepository.findByUserIdOrderByCreatedAtAsc(userId);
    }

    public List<AuditEvent> getEventsForEntity(String entityType, Long entityId) {
        return auditRepository.findByEntityTypeAndEntityIdOrderByCreatedAtAsc(entityType, entityId);
    }

    public List<AuditEvent> getAllEvents() {
        return auditRepository.findAll();
    }

    public Optional<AuditEvent> getEventById(Long id) {
        return auditRepository.findById(id);
    }
}