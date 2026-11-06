package com.enterprise.feature;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {

    Optional<FeatureFlag> findByName(String name);

    @Query("SELECT f FROM FeatureFlag f WHERE f.tenantId = :tenantId")
    List<FeatureFlag> findAllByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT f FROM FeatureFlag f WHERE f.name = :name AND f.tenantId = :tenantId")
    Optional<FeatureFlag> findByNameAndTenantId(@Param("name") String name,
                                                @Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(f) FROM FeatureFlag f WHERE f.tenantId = :tenantId")
    long countByTenantId(@Param("tenantId") Long tenantId);
}