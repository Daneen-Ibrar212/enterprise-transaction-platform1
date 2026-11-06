package com.enterprise.scheduler;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.notification.NotificationService;
import com.enterprise.reporting.PdfExportService;
import com.enterprise.reporting.ReportHistoryService;
import com.enterprise.tenant.Tenant;
import com.enterprise.tenant.TenantContext;
import com.enterprise.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class ReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduler.class);

    private final PdfExportService pdfExportService;
    private final ReportHistoryService reportHistoryService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;

    public ReportScheduler(PdfExportService pdfExportService,
                           ReportHistoryService reportHistoryService,
                           NotificationService notificationService,
                           UserRepository userRepository,
                           TenantRepository tenantRepository) {
        this.pdfExportService = pdfExportService;
        this.reportHistoryService = reportHistoryService;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
    }

    @Scheduled(cron = "0 0 9 * * MON")
    public void sendWeeklyReport() {
        runForAllTenants("WEEKLY", 7);
    }

    @Scheduled(cron = "0 0 18 * * *")
    public void sendDailySummary() {
        runForAllTenants("DAILY", 1);
    }

    /**
     * ✅ Iterates ALL tenants. Previous version generated reports for tenant 1
     *    only — every other tenant silently received nothing.
     */
    private void runForAllTenants(String reportType, int daysBack) {
        for (Tenant tenant : tenantRepository.findAll()) {
            try {
                TenantContext.setTenantId(tenant.getId());
                generateAndStoreReport(reportType, daysBack, tenant.getId());
            } catch (Exception e) {
                log.error("Report {} failed for tenant {}: {}",
                        reportType, tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void generateAndStoreReport(String reportType, int daysBack, Long tenantId) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(daysBack);
        String dateRange = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                + " to " + endDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        try {
            byte[] pdfBytes = pdfExportService.generateReport(startDate, endDate, tenantId).toByteArray();
            String filename = reportType.toLowerCase() + "_report_" + startDate + ".pdf";
            reportHistoryService.saveReport(filename, reportType, startDate, endDate,
                    pdfBytes, "application/pdf");

            List<AppUser> admins = userRepository.findByTenantIdAndRoleName(tenantId, "ADMIN");
            String title = reportType + " Report Ready";
            String message = "Your " + reportType.toLowerCase() + " report (" + dateRange + ") is ready.";
            for (AppUser admin : admins) {
                notificationService.createNotification(admin.getId(), "REPORT_READY",
                        title, message, "/admin/reports/history");
            }
        } catch (Exception e) {
            log.error("Failed to generate {} report for tenant {}: {}",
                    reportType, tenantId, e.getMessage(), e);
        }
    }
}