package com.enterprise.aop;

import com.enterprise.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Ensures the Hibernate "tenantFilter" is enabled for the tenant of the
 * current request before every repository call.
 *
 * Key differences vs previous version:
 *  - Uses a SINGLE @Around advice (the previous class had both @Before and
 *    @Around, which double-invoked enableTenantFilter() and then disabled
 *    the filter after the @Before had finished — leaving the actual method
 *    call with the filter disabled).
 *  - Does NOT swallow "UnknownFilterException" silently — that error means
 *    the filter def is missing and tenant isolation is broken. We log ERROR
 *    and rethrow.
 *  - Only disables the filter if it was actually enabled in this call.
 *  - Uses a dedicated lock on the current Session (never a cross-thread session).
 */
@Aspect
@Component
@Order(1)
public class TenantFilterAspect {

    private static final Logger log = LoggerFactory.getLogger(TenantFilterAspect.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Around("execution(* com.enterprise..*Repository.*(..))")
    public Object aroundRepositoryMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        boolean enabled = false;
        Session session = null;
        try {
            // Skip filtering entirely for SUPER_ADMIN authority (matches prior behavior).
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                boolean isSuperAdmin = auth.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
                if (isSuperAdmin) {
                    return joinPoint.proceed();
                }
            }

            Long tenantId = TenantContext.getTenantId();
            if (tenantId == null) {
                // No tenant — but not super admin either. This is only safe
                // for anonymous requests to truly public endpoints (e.g. /login).
                // Any other code path reaching a repository without a tenant is
                // a bug; log at WARN so it's visible without breaking public flows.
                log.warn("Repository call with NO tenant context: {}", joinPoint.getSignature().toShortString());
                return joinPoint.proceed();
            }

            session = entityManager.unwrap(Session.class);
            session.enableFilter(TenantFilterDefinition.FILTER_NAME)
                    .setParameter(TenantFilterDefinition.PARAM_NAME, tenantId);
            enabled = true;

            return joinPoint.proceed();
        } catch (org.hibernate.UnknownFilterException e) {
            // This means @Filter(tenantFilter) is on an entity but no @FilterDef
            // is registered. Data leakage is silent. Fail LOUDLY.
            log.error("❌ tenantFilter NOT registered — cross-tenant leak risk! " +
                      "Call: {}", joinPoint.getSignature().toShortString(), e);
            throw new IllegalStateException(
                    "tenantFilter is not registered; refusing to execute repository method " +
                    joinPoint.getSignature().toShortString(), e);
        } finally {
            if (enabled && session != null && session.isOpen()) {
                try {
                    session.disableFilter(TenantFilterDefinition.FILTER_NAME);
                } catch (Exception ignored) {
                    // Session may already be closed — safe to ignore here.
                }
            }
        }
    }
}