package com.enterprise.reminder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ReminderScheduleRepository extends JpaRepository<ReminderSchedule, Long> {

    Optional<ReminderSchedule> findByInvoiceIdAndReminderType(Long invoiceId, ReminderType type);

    List<ReminderSchedule> findByStatusOrderByScheduledForAsc(String status);

    @Query("SELECT r FROM ReminderSchedule r WHERE r.tenantId = :tenantId AND r.status = :status ORDER BY r.scheduledFor ASC")
    List<ReminderSchedule> findByTenantIdAndStatus(@Param("tenantId") Long tenantId,
                                                   @Param("status") String status);

    @Query("SELECT r FROM ReminderSchedule r WHERE r.tenantId = :tenantId ORDER BY r.createdAt DESC")
    List<ReminderSchedule> findAllByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT r FROM ReminderSchedule r WHERE r.status = 'PENDING' AND r.scheduledFor <= :today")
    List<ReminderSchedule> findDueForSending(@Param("today") LocalDate today);
}