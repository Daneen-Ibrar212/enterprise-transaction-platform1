package com.enterprise.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SubscriptionRequestRepository extends JpaRepository<SubscriptionRequest, Long> {

    @Query("SELECT r FROM SubscriptionRequest r WHERE r.tenantId = :tenantId AND r.status = 'PENDING' ORDER BY r.createdAt ASC")
    List<SubscriptionRequest> findPendingByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT r FROM SubscriptionRequest r WHERE r.tenantId = :tenantId AND r.merchantId = :merchantId AND r.status = 'PENDING' ORDER BY r.createdAt ASC")
    List<SubscriptionRequest> findPendingByMerchantId(@Param("tenantId") Long tenantId,
                                                     @Param("merchantId") Long merchantId);

    @Query("SELECT r FROM SubscriptionRequest r WHERE r.customerId = :customerId ORDER BY r.createdAt DESC")
    List<SubscriptionRequest> findByCustomerId(@Param("customerId") Long customerId);

    @Query("SELECT r FROM SubscriptionRequest r WHERE r.tenantId = :tenantId ORDER BY r.createdAt DESC")
    List<SubscriptionRequest> findAllByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT r FROM SubscriptionRequest r WHERE r.merchantId = :merchantId AND r.status = :status ORDER BY r.createdAt DESC")
    List<SubscriptionRequest> findByMerchantIdAndStatus(@Param("merchantId") Long merchantId,
                                                        @Param("status") SubscriptionRequest.RequestStatus status);

    long countByTenantIdAndStatus(Long tenantId, SubscriptionRequest.RequestStatus status);
    long countByMerchantIdAndStatus(Long merchantId, SubscriptionRequest.RequestStatus status);

    Optional<SubscriptionRequest> findByIdAndTenantId(Long id, Long tenantId);
}