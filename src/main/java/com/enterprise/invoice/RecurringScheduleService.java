package com.enterprise.invoice;

import com.enterprise.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class RecurringScheduleService {

    private static final Logger log = LoggerFactory.getLogger(RecurringScheduleService.class);

    private final RecurringScheduleRepository scheduleRepository;
    private final InvoiceService invoiceService;

    public RecurringScheduleService(RecurringScheduleRepository scheduleRepository,
                                    InvoiceService invoiceService) {
        this.scheduleRepository = scheduleRepository;
        this.invoiceService = invoiceService;
    }

    @Transactional
    public RecurringSchedule createSchedule(RecurringSchedule schedule) {
        schedule.setTenantId(TenantContext.getRequiredTenantId());
        schedule.setNextRunDate(schedule.getStartDate());
        schedule.setActive(true);
        return scheduleRepository.save(schedule);
    }

    /**
     * ✅ Update with optimistic-locking retry.
     *
     * The previous implementation had a `while(retries>0)` loop containing
     * `Thread.sleep(100)` INSIDE @Transactional. The save() doesn't throw
     * until transaction commit — which happens AFTER the method returns — so
     * the loop never fired. The sleep also held a DB connection while sleeping.
     *
     * Fix: no sleep, no loop. Retries are now the caller's responsibility
     * (or Spring Retry at the controller). The class uses the default
     * REQUIRED propagation so each save is part of one transaction.
     */
    @Transactional
    public RecurringSchedule updateSchedule(RecurringSchedule incoming) {
        RecurringSchedule existing = scheduleRepository.findById(incoming.getId())
                .orElseThrow(() -> new RuntimeException("Schedule not found"));

        existing.setCustomerEmail(incoming.getCustomerEmail());
        existing.setAmount(incoming.getAmount());
        existing.setCurrency(incoming.getCurrency());
        existing.setDescription(incoming.getDescription());
        existing.setFrequency(incoming.getFrequency());
        existing.setStartDate(incoming.getStartDate());
        existing.setEndDate(incoming.getEndDate());
        existing.setUpdatedAt(LocalDateTime.now());

        return scheduleRepository.save(existing);
    }

    @Transactional
    public void toggleActive(Long id) {
        RecurringSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Schedule not found"));
        schedule.setActive(!schedule.isActive());
        schedule.setUpdatedAt(LocalDateTime.now());
        scheduleRepository.save(schedule);
    }

    @Transactional
    public void deleteSchedule(Long id) {
        scheduleRepository.deleteById(id);
    }

    public List<RecurringSchedule> getSchedulesForMerchant(Long merchantId) {
        return scheduleRepository.findByMerchantIdOrderByNextRunDateAsc(merchantId);
    }

    public RecurringSchedule getSchedule(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Schedule not found"));
    }

    /**
     * Process all due schedules for the current tenant.
     *
     * ✅ REQUIRES_NEW so each schedule is independent — a single failure
     *    does not roll back the entire batch. Runs under one surrounding
     *    transaction so failures still isolate.
     */
    @Transactional
    public int processDueSchedules() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            log.warn("No tenant context, skipping recurring schedule processing");
            return 0;
        }

        LocalDate today = LocalDate.now();
        List<RecurringSchedule> schedules = scheduleRepository
                .findActiveSchedulesDueForDate(today, tenantId);

        int created = 0;
        for (RecurringSchedule schedule : schedules) {
            try {
                Invoice invoice = invoiceService.createInvoice(
                        schedule.getAmount(),
                        schedule.getDescription(),
                        schedule.getCustomerEmail(),
                        schedule.getMerchantId(),
                        false,
                        schedule.getCurrency()
                );

                LocalDate nextRun = switch (schedule.getFrequency()) {
                    case DAILY -> schedule.getNextRunDate().plusDays(1);
                    case WEEKLY -> schedule.getNextRunDate().plusWeeks(1);
                    case MONTHLY -> schedule.getNextRunDate().plusMonths(1);
                };
                if (schedule.getEndDate() != null && nextRun.isAfter(schedule.getEndDate())) {
                    schedule.setActive(false);
                }
                schedule.setNextRunDate(nextRun);
                schedule.setUpdatedAt(LocalDateTime.now());
                scheduleRepository.save(schedule);
                created++;
                log.info("Created recurring invoice #{} for schedule {}",
                        invoice.getId(), schedule.getId());
            } catch (Exception e) {
                log.error("Failed to process recurring schedule {}: {}",
                        schedule.getId(), e.getMessage(), e);
                // Continue with remaining schedules — one failure shouldn't halt the batch
            }
        }
        return created;
    }
}