package com.enterprise.feature;

import com.enterprise.events.SuspicionEnabledEvent;
import com.enterprise.tenant.TenantContext;
import com.enterprise.xero.service.XeroOAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final FeatureFlagRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final XeroOAuthService xeroOAuthService;
    private final CacheManager cacheManager;

    public FeatureFlagService(FeatureFlagRepository repository,
                              ApplicationEventPublisher eventPublisher,
                              XeroOAuthService xeroOAuthService,
                              CacheManager cacheManager) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.xeroOAuthService = xeroOAuthService;
        this.cacheManager = cacheManager;
    }

    @Cacheable(value = "featureFlags", key = "#name + '_' + T(com.enterprise.tenant.TenantContext).getTenantId()")
    public boolean isEnabled(String name) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            log.warn("No tenant context – defaulting to false for flag: {}", name);
            return false;
        }
        if ("XERO_AUTO_SYNC".equals(name)) {
            try {
                if (!xeroOAuthService.isConnected(tenantId)) return false;
            } catch (Exception e) {
                log.warn("Could not check Xero connection for tenant {}: {}", tenantId, e.getMessage());
                return false;
            }
        }
        return repository.findByNameAndTenantId(name, tenantId)
                .map(FeatureFlag::isEnabled)
                .orElse(false);
    }

    /**
     * Evicts the flag AFTER the transaction commits — prevents the
     * stale-repopulation race where another thread reads the cache before
     * we commit and puts the OLD value back.
     */
    private void evictAfterCommit(String name, Long tenantId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Cache cache = cacheManager.getCache("featureFlags");
                    if (cache != null) {
                        cache.evict(name + "_" + tenantId);
                    }
                }
            });
        } else {
            Cache cache = cacheManager.getCache("featureFlags");
            if (cache != null) cache.evict(name + "_" + tenantId);
        }
    }

    private void evictAllAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Cache cache = cacheManager.getCache("featureFlags");
                    if (cache != null) cache.clear();
                }
            });
        } else {
            Cache cache = cacheManager.getCache("featureFlags");
            if (cache != null) cache.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void setEnabled(String name, boolean enabled) {
        Long tenantId = TenantContext.getRequiredTenantId();
        FeatureFlag flag = repository.findByNameAndTenantId(name, tenantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Feature flag not found: " + name + " for tenant " + tenantId));
        flag.setEnabled(enabled);
        flag.setUpdatedAt(LocalDateTime.now());
        repository.save(flag);
        log.info("Feature flag {} set to {} for tenant {}", name, enabled, tenantId);

        evictAfterCommit(name, tenantId);

        if ("SUSPICION_DETECTION".equals(name) && enabled) {
            eventPublisher.publishEvent(new SuspicionEnabledEvent());
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void setEnabledForTenant(String name, boolean enabled, Long tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        repository.findByNameAndTenantId(name, tenantId).ifPresentOrElse(
                flag -> {
                    flag.setEnabled(enabled);
                    flag.setUpdatedAt(LocalDateTime.now());
                    repository.save(flag);
                    log.info("Feature flag {} set to {} for tenant {}", name, enabled, tenantId);
                    evictAfterCommit(name, tenantId);
                    if ("SUSPICION_DETECTION".equals(name) && enabled) {
                        eventPublisher.publishEvent(new SuspicionEnabledEvent());
                    }
                },
                () -> log.debug("Feature flag {} not found for tenant {}", name, tenantId));
    }

    @Transactional
    public FeatureFlag createFlag(String name, String description, boolean enabled) {
        Long tenantId = TenantContext.getRequiredTenantId();
        FeatureFlag flag = new FeatureFlag();
        flag.setName(name);
        flag.setDescription(description);
        flag.setEnabled(enabled);
        flag.setUpdatedAt(LocalDateTime.now());
        flag.setTenantId(tenantId);
        evictAllAfterCommit();
        return repository.save(flag);
    }

    @Transactional
    public void deleteFlag(Long id) {
        repository.deleteById(id);
        evictAllAfterCommit();
    }

    /**
     * Provision defaults in the caller's transaction (REQUIRED) — required so
     * that when called from SignupService immediately after creating a Tenant,
     * the FK check on feature_flag.tenant_id can see the uncommitted tenant row.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void provisionDefaultsForTenant(Long tenantId) {
        log.info("🔧 Provisioning default feature flags for tenant {}", tenantId);
        int created = 0;
        for (Map.Entry<String, Boolean> entry : FeatureFlagDefaults.DEFAULTS.entrySet()) {
            String name = entry.getKey();
            Boolean enabled = entry.getValue();
            if (repository.findByNameAndTenantId(name, tenantId).isPresent()) continue;
            FeatureFlag flag = new FeatureFlag();
            flag.setName(name);
            flag.setDescription(FeatureFlagDefaults.DESCRIPTIONS.get(name));
            flag.setEnabled(enabled);
            flag.setTenantId(tenantId);
            flag.setUpdatedAt(LocalDateTime.now());
            repository.save(flag);
            created++;
        }
        evictAllAfterCommit();
        log.info("✅ Provisioned {} default feature flags for tenant {}", created, tenantId);
    }

    public List<FeatureFlag> findAll() {
        return repository.findAllByTenantId(TenantContext.getRequiredTenantId());
    }
}