package com.enterprise.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SubscriptionUsageRepository extends JpaRepository<SubscriptionUsage, Long> {

    List<SubscriptionUsage> findBySubscriptionIdOrderByMetricNameAsc(Long subscriptionId);

    Optional<SubscriptionUsage> findBySubscriptionIdAndMetricNameAndPeriodStart(
            Long subscriptionId, String metricName, LocalDate periodStart);

    @Query("SELECT u FROM SubscriptionUsage u WHERE u.subscriptionId = :subId AND :today BETWEEN u.periodStart AND u.periodEnd")
    List<SubscriptionUsage> findCurrentPeriod(@Param("subId") Long subId, @Param("today") LocalDate today);
}