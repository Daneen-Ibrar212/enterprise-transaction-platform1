package com.enterprise.invoice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface RecurringScheduleRepository extends JpaRepository<RecurringSchedule, Long> {

    @Query("SELECT s FROM RecurringSchedule s WHERE s.active = true AND s.nextRunDate <= :date AND s.tenantId = :tenantId")
    List<RecurringSchedule> findActiveSchedulesDueForDate(@Param("date") LocalDate date,
                                                          @Param("tenantId") Long tenantId);

    List<RecurringSchedule> findByMerchantIdOrderByNextRunDateAsc(Long merchantId);

    @Modifying
    @Transactional
    @Query("UPDATE RecurringSchedule s SET s.nextRunDate = :newDate WHERE s.id = :id")
    void updateNextRunDate(@Param("id") Long id, @Param("newDate") LocalDate newDate);

    // ✅ ADD THIS METHOD - Find all schedules for a tenant
    @Query("SELECT s FROM RecurringSchedule s WHERE s.tenantId = :tenantId")
    List<RecurringSchedule> findAllByTenantId(@Param("tenantId") Long tenantId);

    // ✅ ADD THIS METHOD - Count active schedules for a tenant
    @Query("SELECT COUNT(s) FROM RecurringSchedule s WHERE s.tenantId = :tenantId AND s.active = true")
    long countByTenantIdAndActiveTrue(@Param("tenantId") Long tenantId);

    // ✅ ADD THIS METHOD - Find all active schedules
    List<RecurringSchedule> findByActiveTrue();
}