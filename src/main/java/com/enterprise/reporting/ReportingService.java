package com.enterprise.reporting;

import com.enterprise.currency.ExchangeRateService;
import com.enterprise.identity.UserRepository;
import com.enterprise.invoice.RecurringScheduleRepository;
import com.enterprise.tenant.Tenant;
import com.enterprise.tenant.TenantRepository;
import com.enterprise.transaction.Transaction;
import com.enterprise.transaction.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportingService {

    private final TransactionRepository transactionRepository;
    private final TenantRepository tenantRepository;
    private final ExchangeRateService exchangeRateService;
    private final UserRepository userRepository;
    private final RecurringScheduleRepository recurringScheduleRepository;

    public ReportingService(TransactionRepository transactionRepository,
                            TenantRepository tenantRepository,
                            ExchangeRateService exchangeRateService,
                            UserRepository userRepository,
                            RecurringScheduleRepository recurringScheduleRepository) {
        this.transactionRepository = transactionRepository;
        this.tenantRepository = tenantRepository;
        this.exchangeRateService = exchangeRateService;
        this.userRepository = userRepository;
        this.recurringScheduleRepository = recurringScheduleRepository;
    }

    // ============================================================
    // CORE METHODS
    // ============================================================

    public List<Transaction> getTransactionsBetween(LocalDate startDate, LocalDate endDate, Long tenantId) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(23, 59, 59);
        return transactionRepository.findAll().stream()
                .filter(tx -> tx.getCreatedAt().isAfter(start) && tx.getCreatedAt().isBefore(end))
                .filter(tx -> tenantId == null || tx.getTenantId().equals(tenantId))
                .collect(Collectors.toList());
    }

    private String getBaseCurrency(Long tenantId) {
        if (tenantId == null) {
            return "GBP";
        }
        return tenantRepository.findById(tenantId)
                .map(Tenant::getBaseCurrency)
                .orElse("GBP");
    }

    // ============================================================
    // DAILY VOLUME
    // ============================================================
    public Map<LocalDate, Long> getDailyVolume(LocalDate startDate, LocalDate endDate, Long tenantId) {
        List<Transaction> transactions = getTransactionsBetween(startDate, endDate, tenantId);
        return transactions.stream()
                .collect(Collectors.groupingBy(
                        tx -> tx.getCreatedAt().toLocalDate(),
                        Collectors.counting()
                ));
    }

    // ============================================================
    // DAILY AVERAGE
    // ============================================================
    public Map<LocalDate, BigDecimal> getDailyAverage(LocalDate startDate, LocalDate endDate, Long tenantId) {
        List<Transaction> transactions = getTransactionsBetween(startDate, endDate, tenantId);
        String baseCurrency = getBaseCurrency(tenantId);
        return transactions.stream()
                .collect(Collectors.groupingBy(
                        tx -> tx.getCreatedAt().toLocalDate(),
                        Collectors.averagingDouble(tx -> exchangeRateService.convert(tx.getAmount(), tx.getCurrency(), baseCurrency).doubleValue())
                ))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> BigDecimal.valueOf(e.getValue()).setScale(2, RoundingMode.HALF_UP)
                ));
    }

    // ============================================================
    // SUMMARY METRICS
    // ============================================================
    public Map<String, Object> getSummaryMetrics(LocalDate startDate, LocalDate endDate, Long tenantId) {
        List<Transaction> transactions = getTransactionsBetween(startDate, endDate, tenantId);
        String baseCurrency = getBaseCurrency(tenantId);
        long total = transactions.size();
        if (total == 0) {
            return Map.of(
                    "totalTransactions", 0L,
                    "totalVolume", BigDecimal.ZERO,
                    "average", BigDecimal.ZERO,
                    "successRate", 0.0,
                    "settledCount", 0L,
                    "pendingCount", 0L,
                    "refundedCount", 0L,
                    "failedCount", 0L
            );
        }
        BigDecimal totalVolume = transactions.stream()
                .map(tx -> exchangeRateService.convert(tx.getAmount(), tx.getCurrency(), baseCurrency))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = totalVolume.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        long settled = transactions.stream().filter(tx -> "SETTLED".equals(tx.getStatus().name())).count();
        long pending = transactions.stream().filter(tx -> "PENDING".equals(tx.getStatus().name())).count();
        long refunded = transactions.stream().filter(tx -> "REFUNDED".equals(tx.getStatus().name())).count();
        long failed = transactions.stream().filter(tx -> "FAILED".equals(tx.getStatus().name())).count();
        double successRate = (double) settled / total * 100;
        return Map.of(
                "totalTransactions", total,
                "totalVolume", totalVolume,
                "average", average,
                "successRate", successRate,
                "settledCount", settled,
                "pendingCount", pending,
                "refundedCount", refunded,
                "failedCount", failed
        );
    }

    // ============================================================
    // STATUS DISTRIBUTION
    // ============================================================
    public Map<String, Long> getStatusDistribution(LocalDate startDate, LocalDate endDate, Long tenantId) {
        List<Transaction> transactions = getTransactionsBetween(startDate, endDate, tenantId);
        return transactions.stream()
                .collect(Collectors.groupingBy(
                        tx -> tx.getStatus().name(),
                        Collectors.counting()
                ));
    }

    // ============================================================
    // TOP MERCHANTS
    // ============================================================
    public List<Map<String, Object>> getTopMerchants(LocalDate startDate, LocalDate endDate, int limit, Long tenantId) {
        List<Transaction> transactions = getTransactionsBetween(startDate, endDate, tenantId);
        String baseCurrency = getBaseCurrency(tenantId);
        return transactions.stream()
                .collect(Collectors.groupingBy(
                        Transaction::getMerchantId,
                        Collectors.summingDouble(tx -> exchangeRateService.convert(tx.getAmount(), tx.getCurrency(), baseCurrency).doubleValue())
                ))
                .entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(limit)
                .map(e -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("merchantId", e.getKey());
                    map.put("totalAmount", BigDecimal.valueOf(e.getValue()).setScale(2, RoundingMode.HALF_UP));
                    return map;
                })
                .collect(Collectors.toList());
    }

    // ============================================================
    // DAILY SUCCESS RATE
    // ============================================================
    public Map<LocalDate, Double> getDailySuccessRate(LocalDate startDate, LocalDate endDate, Long tenantId) {
        List<Transaction> transactions = getTransactionsBetween(startDate, endDate, tenantId);
        Map<LocalDate, List<Transaction>> byDay = transactions.stream()
                .collect(Collectors.groupingBy(tx -> tx.getCreatedAt().toLocalDate()));
        Map<LocalDate, Double> result = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, List<Transaction>> entry : byDay.entrySet()) {
            long total = entry.getValue().size();
            long settled = entry.getValue().stream()
                    .filter(tx -> "SETTLED".equals(tx.getStatus().name()))
                    .count();
            double rate = total == 0 ? 0 : (double) settled / total * 100;
            result.put(entry.getKey(), rate);
        }
        return result;
    }

    // ============================================================
    // MONTHLY COMPARISON
    // ============================================================
    public Map<String, Object> getMonthlyComparison(Long tenantId) {
        Map<String, Object> result = new HashMap<>();
        
        LocalDate thisMonthStart = LocalDate.now().withDayOfMonth(1);
        LocalDate lastMonthStart = thisMonthStart.minusMonths(1);
        LocalDate lastMonthEnd = thisMonthStart.minusDays(1);
        
        Map<String, Object> thisMonth = getSummaryMetrics(thisMonthStart, LocalDate.now(), tenantId);
        Map<String, Object> lastMonth = getSummaryMetrics(lastMonthStart, lastMonthEnd, tenantId);
        
        BigDecimal thisRevenue = (BigDecimal) thisMonth.getOrDefault("totalVolume", BigDecimal.ZERO);
        BigDecimal lastRevenue = (BigDecimal) lastMonth.getOrDefault("totalVolume", BigDecimal.ZERO);
        
        double growthPercent = 0.0;
        if (lastRevenue.compareTo(BigDecimal.ZERO) > 0) {
            growthPercent = thisRevenue.subtract(lastRevenue)
                .divide(lastRevenue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
        }
        
        result.put("thisMonthRevenue", thisRevenue);
        result.put("lastMonthRevenue", lastRevenue);
        result.put("growthPercent", growthPercent);
        result.put("thisMonthTransactions", thisMonth.get("totalTransactions"));
        result.put("lastMonthTransactions", lastMonth.get("totalTransactions"));
        
        return result;
    }

    // ============================================================
    // TOP CUSTOMERS
    // ============================================================
    public List<Map<String, Object>> getTopCustomers(LocalDate startDate, LocalDate endDate, int limit, Long tenantId) {
        List<Transaction> transactions = getTransactionsBetween(startDate, endDate, tenantId);
        String baseCurrency = getBaseCurrency(tenantId);
        return transactions.stream()
                .collect(Collectors.groupingBy(
                        Transaction::getCustomerId,
                        Collectors.summingDouble(tx -> exchangeRateService.convert(tx.getAmount(), tx.getCurrency(), baseCurrency).doubleValue())
                ))
                .entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(limit)
                .map(e -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("customerId", e.getKey());
                    map.put("totalAmount", BigDecimal.valueOf(e.getValue()).setScale(2, RoundingMode.HALF_UP));
                    return map;
                })
                .collect(Collectors.toList());
    }

    // ============================================================
    // EXECUTIVE DATA
    // ============================================================
    public Map<String, Object> getExecutiveData(Long tenantId) {
        Map<String, Object> result = new HashMap<>();

        LocalDate now = LocalDate.now();
        LocalDate thisMonthStart = now.withDayOfMonth(1);
        LocalDate lastMonthStart = thisMonthStart.minusMonths(1);
        LocalDate lastMonthEnd = thisMonthStart.minusDays(1);

        Map<String, Object> thisMonth = getSummaryMetrics(thisMonthStart, now, tenantId);
        Map<String, Object> lastMonth = getSummaryMetrics(lastMonthStart, lastMonthEnd, tenantId);

        BigDecimal thisRevenue = (BigDecimal) thisMonth.getOrDefault("totalVolume", BigDecimal.ZERO);
        BigDecimal lastRevenue = (BigDecimal) lastMonth.getOrDefault("totalVolume", BigDecimal.ZERO);
        long thisTxCount = (long) thisMonth.getOrDefault("totalTransactions", 0L);
        long lastTxCount = (long) lastMonth.getOrDefault("totalTransactions", 0L);

        double revenueGrowth = 0.0;
        if (lastRevenue.compareTo(BigDecimal.ZERO) > 0) {
            revenueGrowth = thisRevenue.subtract(lastRevenue)
                .divide(lastRevenue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
        }

        double transactionGrowth = 0.0;
        if (lastTxCount > 0) {
            transactionGrowth = ((double) (thisTxCount - lastTxCount) / lastTxCount) * 100;
        }

        result.put("currentMonthRevenue", thisRevenue);
        result.put("currentMonthTransactions", thisTxCount);
        result.put("revenueGrowth", revenueGrowth);
        result.put("transactionGrowth", transactionGrowth);
        result.put("activeCustomers", getActiveCustomerCount(tenantId));
        result.put("newCustomersThisMonth", getNewCustomerCount(thisMonthStart, now, tenantId));
        result.put("activeSubscriptions", getActiveSubscriptionCount(tenantId));
        result.put("newSubscriptionsThisMonth", getNewSubscriptionCount(thisMonthStart, now, tenantId));

        return result;
    }

    // ============================================================
    // REVENUE BREAKDOWN
    // ============================================================
    public Map<String, Object> getRevenueBreakdown(Long tenantId) {
        Map<String, Object> result = new HashMap<>();
        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);

        List<Transaction> transactions = getTransactionsBetween(monthStart, now, tenantId);
        String baseCurrency = getBaseCurrency(tenantId);

        BigDecimal oneTimeRevenue = BigDecimal.ZERO;
        BigDecimal subscriptionRevenue = BigDecimal.ZERO;

        for (Transaction tx : transactions) {
            BigDecimal converted = exchangeRateService.convert(tx.getAmount(), tx.getCurrency(), baseCurrency);
            oneTimeRevenue = oneTimeRevenue.add(converted);
        }

        result.put("oneTimeRevenue", oneTimeRevenue);
        result.put("subscriptionRevenue", subscriptionRevenue);
        return result;
    }

    // ============================================================
    // REVENUE TREND
    // ============================================================
    public List<Map<String, Object>> getRevenueTrend(Long tenantId) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate now = LocalDate.now();

        for (int i = 5; i >= 0; i--) {
            LocalDate monthStart = now.minusMonths(i).withDayOfMonth(1);
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
            if (monthEnd.isAfter(now)) monthEnd = now;

            Map<String, Object> summary = getSummaryMetrics(monthStart, monthEnd, tenantId);
            BigDecimal revenue = (BigDecimal) summary.getOrDefault("totalVolume", BigDecimal.ZERO);

            Map<String, Object> entry = new HashMap<>();
            entry.put("period", monthStart.getMonth().toString().substring(0, 3) + " " + monthStart.getYear());
            entry.put("revenue", revenue);
            result.add(entry);
        }
        return result;
    }

    // ============================================================
    // CUSTOMER ANALYTICS
    // ============================================================
    public Map<String, Object> getCustomerAnalytics(Long tenantId) {
        Map<String, Object> result = new HashMap<>();
        long totalCustomers = getActiveCustomerCount(tenantId);
        long newCustomers = getNewCustomerCount(
            LocalDate.now().withDayOfMonth(1), LocalDate.now(), tenantId);
        
        result.put("totalCustomers", totalCustomers);
        result.put("newCustomersThisMonth", newCustomers);
        result.put("activeCustomers", totalCustomers);
        result.put("churnRate", 0.0);
        result.put("customerLTV", BigDecimal.ZERO);
        return result;
    }

    // ============================================================
    // TAX SUMMARY
    // ============================================================
    public Map<String, Object> getTaxSummary(LocalDate startDate, LocalDate endDate, Long tenantId) {
        Map<String, Object> result = new HashMap<>();
        result.put("totalTaxCollected", BigDecimal.ZERO);
        result.put("taxByCountry", new HashMap<>());
        return result;
    }

    // ============================================================
    // HEALTH METRICS
    // ============================================================
    public Map<String, Object> getHealthMetrics(LocalDate startDate, LocalDate endDate, Long tenantId) {
        Map<String, Object> result = new HashMap<>();
        List<Transaction> transactions = getTransactionsBetween(startDate, endDate, tenantId);

        long total = transactions.size();
        if (total == 0) {
            result.put("successRate", 0.0);
            result.put("failureRate", 0.0);
            result.put("refundRate", 0.0);
            return result;
        }

        long settled = transactions.stream().filter(t -> "SETTLED".equals(t.getStatus().name())).count();
        long failed = transactions.stream().filter(t -> "FAILED".equals(t.getStatus().name())).count();
        long refunded = transactions.stream().filter(t -> "REFUNDED".equals(t.getStatus().name())).count();

        result.put("successRate", (double) settled / total * 100);
        result.put("failureRate", (double) failed / total * 100);
        result.put("refundRate", (double) refunded / total * 100);
        return result;
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private long getActiveCustomerCount(Long tenantId) {
        try {
            if (tenantId != null) {
                return userRepository.findByTenantIdAndRoleName(tenantId, "CUSTOMER").stream()
                        .filter(u -> u.isActive()).count();
            } else {
                return userRepository.findAll().stream()
                        .filter(u -> u.isActive() && u.getRoles().stream().anyMatch(r -> r.getName().equals("CUSTOMER")))
                        .count();
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    private long getNewCustomerCount(LocalDate start, LocalDate end, Long tenantId) {
        try {
            LocalDateTime startDT = start.atStartOfDay();
            LocalDateTime endDT = end.atTime(23, 59, 59);

            if (tenantId != null) {
                return userRepository.findByTenantIdAndRoleName(tenantId, "CUSTOMER").stream()
                        .filter(u -> u.getCreatedAt().isAfter(startDT) && u.getCreatedAt().isBefore(endDT))
                        .count();
            } else {
                return userRepository.findAll().stream()
                        .filter(u -> u.getRoles().stream().anyMatch(r -> r.getName().equals("CUSTOMER")))
                        .filter(u -> u.getCreatedAt().isAfter(startDT) && u.getCreatedAt().isBefore(endDT))
                        .count();
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    private long getActiveSubscriptionCount(Long tenantId) {
        try {
            if (tenantId != null) {
                return recurringScheduleRepository.findAll().stream()
                        .filter(s -> s.getTenantId().equals(tenantId) && s.isActive()).count();
            } else {
                return recurringScheduleRepository.findAll().stream()
                        .filter(s -> s.isActive()).count();
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    private long getNewSubscriptionCount(LocalDate start, LocalDate end, Long tenantId) {
        try {
            LocalDateTime startDT = start.atStartOfDay();
            LocalDateTime endDT = end.atTime(23, 59, 59);

            if (tenantId != null) {
                return recurringScheduleRepository.findAll().stream()
                        .filter(s -> s.getTenantId().equals(tenantId))
                        .filter(s -> s.getCreatedAt() != null && 
                                s.getCreatedAt().isAfter(startDT) && s.getCreatedAt().isBefore(endDT))
                        .count();
            } else {
                return recurringScheduleRepository.findAll().stream()
                        .filter(s -> s.getCreatedAt() != null && 
                                s.getCreatedAt().isAfter(startDT) && s.getCreatedAt().isBefore(endDT))
                        .count();
            }
        } catch (Exception e) {
            return 0L;
        }
    }
}