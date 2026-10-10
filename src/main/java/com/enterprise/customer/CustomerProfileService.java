package com.enterprise.customer;

import com.enterprise.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class CustomerProfileService {

    private static final Logger log = LoggerFactory.getLogger(CustomerProfileService.class);

    private final CustomerProfileRepository repository;

    public CustomerProfileService(CustomerProfileRepository repository) {
        this.repository = repository;
    }

    public CustomerProfile getOrCreate(Long userId) {
        Long tenantId = TenantContext.getRequiredTenantId();
        return repository.findByUserIdAndTenantId(userId, tenantId)
                .orElseGet(() -> {
                    CustomerProfile profile = new CustomerProfile();
                    profile.setUserId(userId);
                    profile.setTenantId(tenantId);
                    return repository.save(profile);
                });
    }

    @Transactional
    public CustomerProfile save(Long userId, CustomerProfile incoming) {
        Long tenantId = TenantContext.getRequiredTenantId();
        CustomerProfile profile = repository.findByUserIdAndTenantId(userId, tenantId)
                .orElseGet(() -> {
                    CustomerProfile p = new CustomerProfile();
                    p.setUserId(userId);
                    p.setTenantId(tenantId);
                    return p;
                });

        profile.setAddressLine1(incoming.getAddressLine1());
        profile.setAddressLine2(incoming.getAddressLine2());
        profile.setCity(incoming.getCity());
        profile.setRegion(incoming.getRegion());
        profile.setPostalCode(incoming.getPostalCode());
        profile.setCountry(incoming.getCountry());
        profile.setCompanyName(incoming.getCompanyName());
        profile.setVatNumber(incoming.getVatNumber());
        profile.setUpdatedAt(LocalDateTime.now());

        CustomerProfile saved = repository.save(profile);
        log.info("✅ Billing profile saved for user {} (tenant {})", userId, tenantId);
        return saved;
    }
}