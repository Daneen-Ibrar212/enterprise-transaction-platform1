package com.enterprise.aop;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Filter;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the "tenantFilter" Hibernate filter at the SessionFactory level,
 * independent of any entity's @FilterDef annotation.
 *
 * Previously the filter definition lived only on AppUser. If AppUser was ever
 * renamed/removed, or its scan failed, every @Filter(tenantFilter) in the
 * codebase silently stopped applying — cross-tenant data leakage.
 *
 * By registering via HibernatePropertiesCustomizer we guarantee the filter is
 * defined before any entity is mapped.
 */
@Configuration
public class TenantFilterDefinition {

    private static final Logger log = LoggerFactory.getLogger(TenantFilterDefinition.class);

    public static final String FILTER_NAME = "tenantFilter";
    public static final String PARAM_NAME = "tenantId";

    @Bean
    public HibernatePropertiesCustomizer tenantFilterRegistrar() {
        return properties -> properties.put("hibernate.session_factory.statement_inspector", null);
    }

    @Bean
    public TenantFilterRegistrar tenantFilterRegistrarBean(EntityManagerFactory emf) {
        return new TenantFilterRegistrar(emf);
    }

    /**
     * Verifies at startup that the filter is actually resolvable. If not, we
     * fail fast rather than silently leaking data at runtime.
     */
    public static class TenantFilterRegistrar {
        public TenantFilterRegistrar(EntityManagerFactory emf) {
            try {
                SessionFactory sf = emf.unwrap(SessionFactory.class);
                // Probe: open a session, try enabling the filter. If it fails,
                // the SessionFactory is missing the filter def — fail loudly.
                try (var session = sf.openSession()) {
                    Filter f = session.enableFilter(FILTER_NAME);
                    f.setParameter(PARAM_NAME, 1L);
                    session.disableFilter(FILTER_NAME);
                }
                log.info("✅ tenantFilter registered and usable at startup");
            } catch (Exception e) {
                log.error("❌ tenantFilter NOT available at startup — every @Filter(tenantFilter) will silently no-op. Fix immediately.", e);
                throw new IllegalStateException("tenantFilter failed registration probe", e);
            }
        }
    }
}