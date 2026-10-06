package com.enterprise.tenant;

import com.enterprise.feature.FeatureFlag;
import com.enterprise.feature.FeatureFlagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TenantSeeder {

    private static final Logger log = LoggerFactory.getLogger(TenantSeeder.class);

    private final FeatureFlagRepository featureFlagRepository;

    public TenantSeeder(FeatureFlagRepository featureFlagRepository) {
        this.featureFlagRepository = featureFlagRepository;
    }

    /**
     * Seed default feature flags for a newly created tenant.
     */
    @Transactional
    public void seedDefaultFeatureFlags(Long tenantId) {
        List<DefaultFlag> defaults = List.of(
            new DefaultFlag("WEBHOOKS",              true,  "Enable webhook delivery for transactions"),
            new DefaultFlag("REFUNDS",               true,  "Enable refund processing"),
            new DefaultFlag("TWO_FACTOR_AUTH",       false, "Enable two-factor authentication"),
            new DefaultFlag("SUSPICION_DETECTION",   false, "Enable suspicion detection rules"),
            new DefaultFlag("XERO_AUTO_SYNC",        false, "Auto-sync invoices to Xero when approved")
        );

        int created = 0;
        for (DefaultFlag flag : defaults) {
            if (featureFlagRepository.findByNameAndTenantId(flag.name, tenantId).isEmpty()) {
                FeatureFlag f = new FeatureFlag();
                f.setName(flag.name);
                f.setEnabled(flag.enabled);
                f.setDescription(flag.description);
                f.setTenantId(tenantId);
                f.setUpdatedAt(LocalDateTime.now());
                featureFlagRepository.save(f);
                created++;
            }
        }
        log.info("✅ Seeded {} feature flags for tenant {}", created, tenantId);
    }

    private record DefaultFlag(String name, boolean enabled, String description) {}
}