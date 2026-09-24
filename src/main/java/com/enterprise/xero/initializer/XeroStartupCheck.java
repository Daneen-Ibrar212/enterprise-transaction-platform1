package com.enterprise.xero.initializer;

import com.enterprise.feature.FeatureFlagService;
import com.enterprise.tenant.Tenant;
import com.enterprise.tenant.TenantRepository;
import com.enterprise.xero.service.XeroOAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class XeroStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(XeroStartupCheck.class);

    private final XeroOAuthService xeroOAuthService;
    private final FeatureFlagService featureFlagService;
    private final TenantRepository tenantRepository;

    public XeroStartupCheck(XeroOAuthService xeroOAuthService,
                            FeatureFlagService featureFlagService,
                            TenantRepository tenantRepository) {
        this.xeroOAuthService = xeroOAuthService;
        this.featureFlagService = featureFlagService;
        this.tenantRepository = tenantRepository;
    }

    @Override
    @Transactional(noRollbackFor = Exception.class)  // ✅ CRITICAL FIX
    public void run(ApplicationArguments args) {
        try {
            log.info("🔍 Starting Xero connection check...");

            List<Tenant> tenants = tenantRepository.findAll();

            boolean anyConnected = false;

            for (Tenant tenant : tenants) {
                try {
                    boolean isConnected = xeroOAuthService.isConnected(tenant.getId());

                    if (isConnected) {
                        log.info("✅ Tenant {} is connected to Xero", tenant.getId());
                        anyConnected = true;
                    } else {
                        log.info("🔌 Tenant {} is NOT connected to Xero - disabling auto-sync", tenant.getId());
                        try {
                            featureFlagService.setEnabled("XERO_AUTO_SYNC", false);
                            log.info("✅ XERO_AUTO_SYNC disabled for tenant {}", tenant.getId());
                        } catch (Exception e) {
                            log.warn("⚠️ Could not disable XERO_AUTO_SYNC for tenant {}: {}", tenant.getId(), e.getMessage());
                            // ✅ Don't rethrow - just log and continue
                        }
                    }
                } catch (Exception e) {
                    log.warn("⚠️ Error checking Xero connection for tenant {}: {}", tenant.getId(), e.getMessage());
                    // ✅ Don't rethrow - just log and continue
                }
            }

            if (anyConnected) {
                log.info("✅ Xero is connected for at least one tenant - Xero features available");
            } else {
                log.info("ℹ️ Xero is NOT connected for any tenant - Xero auto-sync disabled globally");
            }

        } catch (Exception e) {
            log.error("❌ Xero startup check failed: {}", e.getMessage(), e);
            // ✅ Don't rethrow - prevent app from failing to start
        }
    }
}