package com.enterprise.feature;

import com.enterprise.events.SuspicionEnabledEvent;
import com.enterprise.tenant.TenantContext;
import com.enterprise.xero.service.XeroOAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);
    private final FeatureFlagRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final XeroOAuthService xeroOAuthService;

    public FeatureFlagService(FeatureFlagRepository repository,
                              ApplicationEventPublisher eventPublisher,
                              XeroOAuthService xeroOAuthService) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.xeroOAuthService = xeroOAuthService;
    }

    @Cacheable(value = "featureFlags", key = "#name + '_' + #tenantId")
    public boolean isEnabled(String name) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            log.warn("No tenant context – defaulting to false for flag: {}", name);
            return false;
        }

        if ("XERO_AUTO_SYNC".equals(name)) {
            try {
                boolean isConnected = xeroOAuthService.isConnected(tenantId);
                if (!isConnected) {
                    log.debug("🔌 Xero not connected for tenant {}, returning false for XERO_AUTO_SYNC", tenantId);
                    return false;
                }
                return repository.findByNameAndTenantId(name, tenantId)
                        .map(FeatureFlag::isEnabled)
                        .orElse(false);
            } catch (Exception e) {
                log.warn("⚠️ Could not check Xero connection for tenant {}: {}", tenantId, e.getMessage());
                return false;
            }
        }

        log.debug("Checking feature flag: {} for tenant {}", name, tenantId);
        return repository.findByNameAndTenantId(name, tenantId)
                .map(FeatureFlag::isEnabled)
                .orElse(false);
    }

    /**
     * Toggle a flag for the current request's tenant.
     * REQUIRES_NEW so a failure here can't mark the caller's transaction
     * as rollback-only.
     */
    @CacheEvict(value = "featureFlags", key = "#name + '_' + #tenantId")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setEnabled(String name, boolean enabled) {
        Long tenantId = TenantContext.getRequiredTenantId();
        FeatureFlag flag = repository.findByNameAndTenantId(name, tenantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Feature flag not found: " + name + " for tenant " + tenantId));
        flag.setEnabled(enabled);
        flag.setUpdatedAt(LocalDateTime.now());
        repository.save(flag);
        log.info("Feature flag {} set to {} for tenant {}", name, enabled, tenantId);

        if ("SUSPICION_DETECTION".equals(name) && enabled) {
            log.info("Suspicion detection enabled for tenant {} – publishing re-evaluation event", tenantId);
            eventPublisher.publishEvent(new SuspicionEnabledEvent());
        }
    }

    /**
     * Set a feature flag for an explicit tenant.
     * Used during signup when no HTTP context / TenantContext is available.
     *
     * REQUIRES_NEW: runs in its own transaction so that if the flag doesn't
     * exist (or any other error occurs), the outer signup transaction is
     * NOT marked rollback-only.
     *
     * Silent no-op if the flag doesn't exist — this is a best-effort
     * side-effect during signup, not a critical operation.
     */
    @CacheEvict(value = "featureFlags", allEntries = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setEnabledForTenant(String name, boolean enabled, Long tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }

        repository.findByNameAndTenantId(name, tenantId).ifPresentOrElse(
            flag -> {
                flag.setEnabled(enabled);
                flag.setUpdatedAt(LocalDateTime.now());
                repository.save(flag);
                log.info("Feature flag {} set to {} for tenant {}", name, enabled, tenantId);

                if ("SUSPICION_DETECTION".equals(name) && enabled) {
                    log.info("Suspicion detection enabled for tenant {} – publishing re-evaluation event", tenantId);
                    eventPublisher.publishEvent(new SuspicionEnabledEvent());
                }
            },
            () -> log.debug("Feature flag {} not found for tenant {} — skipping (best-effort)",
                    name, tenantId)
        );
    }

    @CacheEvict(value = "featureFlags", key = "#name + '_' + #tenantId")
    @Transactional
    public FeatureFlag createFlag(String name, String description, boolean enabled) {
        Long tenantId = TenantContext.getRequiredTenantId();
        FeatureFlag flag = new FeatureFlag();
        flag.setName(name);
        flag.setDescription(description);
        flag.setEnabled(enabled);
        flag.setUpdatedAt(LocalDateTime.now());
        flag.setTenantId(tenantId);
        return repository.save(flag);
    }

    @Transactional
    public void deleteFlag(Long id) {
        repository.deleteById(id);
    }

    /**
     * Provision default feature flags for a new tenant.
     * REQUIRES_NEW so signup isn't poisoned by a partial failure.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provisionDefaultsForTenant(Long tenantId) {
        log.info("🔧 Provisioning default feature flags for tenant {}", tenantId);

        int created = 0;
        for (Map.Entry<String, Boolean> entry : FeatureFlagDefaults.DEFAULTS.entrySet()) {
            String name = entry.getKey();
            Boolean enabled = entry.getValue();

            // Skip if already exists
            if (repository.findByNameAndTenantId(name, tenantId).isPresent()) {
                continue;
            }

            FeatureFlag flag = new FeatureFlag();
            flag.setName(name);
            flag.setDescription(FeatureFlagDefaults.DESCRIPTIONS.get(name));
            flag.setEnabled(enabled);
            flag.setTenantId(tenantId);
            flag.setUpdatedAt(LocalDateTime.now());
            repository.save(flag);
            created++;
        }
        log.info("✅ Provisioned {} default feature flags for tenant {}", created, tenantId);
    }

    public List<FeatureFlag> findAll() {
        Long tenantId = TenantContext.getRequiredTenantId();
        return repository.findAllByTenantId(tenantId);
    }
}