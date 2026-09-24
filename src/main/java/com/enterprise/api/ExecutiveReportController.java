package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.reporting.ReportingService;
import com.enterprise.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/reports")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class ExecutiveReportController {

    private static final Logger log = LoggerFactory.getLogger(ExecutiveReportController.class);

    private final ReportingService reportingService;
    private final UserRepository userRepository;

    public ExecutiveReportController(ReportingService reportingService,
                                     UserRepository userRepository) {
        this.reportingService = reportingService;
        this.userRepository = userRepository;
    }

    @GetMapping("/executive")
    public String executiveReport(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            Model model,
            Authentication authentication) {

        AppUser admin = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        boolean isSuperAdmin = admin.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));
        Long tenantId = isSuperAdmin ? null : admin.getTenantId();

        // Default: last 30 days
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : end.minusDays(30);

        log.info("📊 Generating executive report for {} to {}", start, end);

        // Fetch all executive data
        Map<String, Object> summary = reportingService.getSummaryMetrics(start, end, tenantId);
        Map<String, Object> monthlyComparison = reportingService.getMonthlyComparison(tenantId);
        List<Map<String, Object>> topCustomers = reportingService.getTopCustomers(start, end, 5, tenantId);
        List<Map<String, Object>> topMerchants = reportingService.getTopMerchants(start, end, 5, tenantId);
        Map<String, Object> taxSummary = reportingService.getTaxSummary(start, end, tenantId);
        Map<String, Object> healthMetrics = reportingService.getHealthMetrics(start, end, tenantId);

        model.addAttribute("summary", summary);
        model.addAttribute("monthlyComparison", monthlyComparison);
        model.addAttribute("topCustomers", topCustomers);
        model.addAttribute("topMerchants", topMerchants);
        model.addAttribute("taxSummary", taxSummary);
        model.addAttribute("healthMetrics", healthMetrics);
        model.addAttribute("startDate", start);
        model.addAttribute("endDate", end);
        model.addAttribute("isSuperAdmin", isSuperAdmin);

        return "admin/reports/executive";
    }
}