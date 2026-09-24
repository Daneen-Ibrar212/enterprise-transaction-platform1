package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.reporting.ExportRequest;
import com.enterprise.reporting.ExportService;
import com.enterprise.reporting.ReportingService;
import com.enterprise.reporting.PdfExportService;
import com.enterprise.reporting.ExcelExportService;
import com.enterprise.transaction.Transaction;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/reports")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MERCHANT_ADMIN')")
public class ReportingController {

    private final ReportingService reportingService;
    private final PdfExportService pdfExportService;
    private final ExcelExportService excelExportService;
    private final ExportService exportService;
    private final UserRepository userRepository;

    public ReportingController(ReportingService reportingService,
                               PdfExportService pdfExportService,
                               ExcelExportService excelExportService,
                               ExportService exportService,
                               UserRepository userRepository) {
        this.reportingService = reportingService;
        this.pdfExportService = pdfExportService;
        this.excelExportService = excelExportService;
        this.exportService = exportService;
        this.userRepository = userRepository;
    }

    private AppUser getCurrentAdmin(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping
public String dashboard(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        Model model,
        Authentication authentication) {

    AppUser admin = getCurrentAdmin(authentication);
    boolean isSuperAdmin = admin.isSuperAdmin();

    if (startDate == null) startDate = LocalDate.now().minusDays(30);
    if (endDate == null) endDate = LocalDate.now();
    if (startDate.isAfter(endDate)) {
        LocalDate temp = startDate;
        startDate = endDate;
        endDate = temp;
    }

    Long tenantId = isSuperAdmin ? null : admin.getTenantId();

    // Existing chart data
    var volume = reportingService.getDailyVolume(startDate, endDate, tenantId);
    var avgAmount = reportingService.getDailyAverage(startDate, endDate, tenantId);
    var summary = reportingService.getSummaryMetrics(startDate, endDate, tenantId);
    var statusDist = reportingService.getStatusDistribution(startDate, endDate, tenantId);
    var topMerchants = reportingService.getTopMerchants(startDate, endDate, 5, tenantId);
    var successRate = reportingService.getDailySuccessRate(startDate, endDate, tenantId);

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    List<String> labels = volume.keySet().stream().sorted().map(d -> d.format(formatter)).collect(Collectors.toList());
    List<Long> volumeData = labels.stream().map(label -> volume.getOrDefault(LocalDate.parse(label, formatter), 0L)).collect(Collectors.toList());
    List<Double> avgData = labels.stream()
            .map(label -> avgAmount.getOrDefault(LocalDate.parse(label, formatter), BigDecimal.ZERO).doubleValue())
            .collect(Collectors.toList());

    List<String> statusLabels = new ArrayList<>(statusDist.keySet());
    List<Long> statusValues = statusLabels.stream().map(statusDist::get).collect(Collectors.toList());

    List<String> merchantLabels = topMerchants.stream()
            .map(m -> "Merchant " + m.get("merchantId"))
            .collect(Collectors.toList());
    List<Double> merchantValues = topMerchants.stream()
            .map(m -> ((BigDecimal) m.get("totalAmount")).doubleValue())
            .collect(Collectors.toList());

    List<Double> successRateValues = labels.stream()
            .map(label -> successRate.getOrDefault(LocalDate.parse(label, formatter), 0.0))
            .collect(Collectors.toList());

    model.addAttribute("labels", labels);
    model.addAttribute("volumeData", volumeData);
    model.addAttribute("avgData", avgData);
    model.addAttribute("summary", summary);
    model.addAttribute("statusLabels", statusLabels);
    model.addAttribute("statusValues", statusValues);
    model.addAttribute("topMerchants", topMerchants);
    model.addAttribute("merchantLabels", merchantLabels);
    model.addAttribute("merchantValues", merchantValues);
    model.addAttribute("successRateData", successRateValues);
    model.addAttribute("startDate", startDate);
    model.addAttribute("endDate", endDate);
    model.addAttribute("isSuperAdmin", isSuperAdmin);

    // ✅ EXECUTIVE ANALYTICS DATA
    Map<String, Object> executiveData = reportingService.getExecutiveData(tenantId);
    Map<String, Object> revenueBreakdown = reportingService.getRevenueBreakdown(tenantId);
    List<Map<String, Object>> revenueTrend = reportingService.getRevenueTrend(tenantId);
    Map<String, Object> customerAnalytics = reportingService.getCustomerAnalytics(tenantId);

    model.addAttribute("data", executiveData);
    model.addAttribute("revenueBreakdown", revenueBreakdown);
    model.addAttribute("revenueData", revenueTrend);
    model.addAttribute("customerAnalytics", customerAnalytics);

    return "admin/reports/dashboard";
}

    // ===== CSV EXPORT =====
    @GetMapping("/data/transactions/csv")
    @ResponseBody
    public ResponseEntity<String> exportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication authentication) {

        AppUser admin = getCurrentAdmin(authentication);
        boolean isSuperAdmin = admin.isSuperAdmin();
        Long tenantId = isSuperAdmin ? null : admin.getTenantId();

        if (startDate == null) startDate = LocalDate.now().minusDays(30);
        if (endDate == null) endDate = LocalDate.now();
        if (startDate.isAfter(endDate)) {
            LocalDate temp = startDate;
            startDate = endDate;
            endDate = temp;
        }

        List<Transaction> transactions = reportingService.getTransactionsBetween(startDate, endDate, tenantId);
        StringBuilder csv = new StringBuilder();
        csv.append("ID,Invoice,Customer,Merchant,Amount,Currency,Status,Created\n");
        for (Transaction tx : transactions) {
            csv.append(tx.getId()).append(",")
               .append(tx.getInvoiceId()).append(",")
               .append(tx.getCustomerId()).append(",")
               .append(tx.getMerchantId()).append(",")
               .append(tx.getAmount()).append(",")
               .append(tx.getCurrency()).append(",")
               .append(tx.getStatus().name()).append(",")
               .append(tx.getCreatedAt()).append("\n");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=transactions_" + startDate + "_to_" + endDate + ".csv")
                .contentType(MediaType.TEXT_PLAIN)
                .body(csv.toString());
    }

    // ===== PDF EXPORT =====
    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication authentication) {

        AppUser admin = getCurrentAdmin(authentication);
        boolean isSuperAdmin = admin.isSuperAdmin();
        Long tenantId = isSuperAdmin ? null : admin.getTenantId();

        if (startDate == null) startDate = LocalDate.now().minusDays(30);
        if (endDate == null) endDate = LocalDate.now();
        if (startDate.isAfter(endDate)) {
            LocalDate temp = startDate;
            startDate = endDate;
            endDate = temp;
        }

        ByteArrayOutputStream pdfStream = pdfExportService.generateReport(startDate, endDate, tenantId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report_" + startDate + "_to_" + endDate + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfStream.toByteArray());
    }

    // ===== EXCEL EXPORT =====
    @GetMapping("/export/excel")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication authentication) {

        AppUser admin = getCurrentAdmin(authentication);
        boolean isSuperAdmin = admin.isSuperAdmin();
        Long tenantId = isSuperAdmin ? null : admin.getTenantId();

        if (startDate == null) startDate = LocalDate.now().minusDays(30);
        if (endDate == null) endDate = LocalDate.now();
        if (startDate.isAfter(endDate)) {
            LocalDate temp = startDate;
            startDate = endDate;
            endDate = temp;
        }

        ByteArrayOutputStream excelStream = excelExportService.generateReport(startDate, endDate, tenantId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report_" + startDate + "_to_" + endDate + ".xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(excelStream.toByteArray());
    }

    // ===== ADVANCED EXPORT =====
    @PostMapping("/export/advanced")
    public ResponseEntity<byte[]> exportAdvanced(@RequestBody ExportRequest request,
                                                 Authentication authentication) {
        AppUser admin = getCurrentAdmin(authentication);
        boolean isSuperAdmin = admin.isSuperAdmin();
        Long tenantId = isSuperAdmin ? null : admin.getTenantId();

        LocalDate startDate = LocalDate.parse(request.getStartDate());
        LocalDate endDate = LocalDate.parse(request.getEndDate());

        List<Transaction> transactions = reportingService.getTransactionsBetween(startDate, endDate, tenantId);

        try {
            byte[] data = exportService.export(transactions, request.getFormat(), request.getFields());
            String contentType = switch (request.getFormat().toLowerCase()) {
                case "json" -> "application/json";
                case "xml" -> "application/xml";
                case "csv" -> "text/csv";
                default -> "application/octet-stream";
            };
            String filename = "transactions." + request.getFormat().toLowerCase();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(data);
        } catch (Exception e) {
            throw new RuntimeException("Export failed", e);
        }
    }
}