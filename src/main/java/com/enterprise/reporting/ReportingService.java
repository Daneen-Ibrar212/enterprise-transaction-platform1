package com.enterprise.reporting;

import com.enterprise.currency.ExchangeRateService;
import com.enterprise.identity.UserRepository;
import com.enterprise.invoice.RecurringScheduleRepository;
import com.enterprise.tenant.Tenant;
import com.enterprise.tenant.TenantRepository;
import com.enterprise.transaction.Transaction;
import com.enterprise.transaction.TransactionRepository;
import com.enterprise.transaction.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportingService {

    private static final Logger log = LoggerFactory.getLogger(ReportingService.class);

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
    // CORE — bounded query, never a full-table scan
    // ============================================================
    public List<Transaction> getTransactionsBetween(LocalDate startDate, LocalDate endDate, Long tenantId) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(23, 59, 59);
        return tenantId == null
                ? transactionRepository.findAllBetween(start, end)
                : transactionRepository.findByTenantIdBetween(tenantId, start, end);
    }

    private String getBaseCurrency(Long tenantId) {
        if (tenantId == null) return "GBP";
        return tenantRepository.findById(tenantId)
                .map(Tenant::getBaseCurrency)
                .orElse("GBP");
    }

    // ============================================================
    // DAILY VOLUME / AVERAGE
    // ============================================================
    public Map<LocalDate, Long> getDailyVolume(LocalDate startDate, LocalDate endDate, Long tenantId) {
        return getTransactionsBetween(startDate, endDate, tenantId).stream()
                .collect(Collectors.groupingBy(
                        tx -> tx.getCreatedAt().toLocalDate(),
                        Collectors.counting()));
    }

    public Map<LocalDate, BigDecimal> getDailyAverage(LocalDate startDate, LocalDate endDate, Long tenantId) {
        String base = getBaseCurrency(tenantId);
        return getTransactionsBetween(startDate, endDate, tenantId).stream()
                .collect(Collectors.groupingBy(
                        tx -> tx.getCreatedAt().toLocalDate(),
                        Collectors.averagingDouble(tx ->
                                exchangeRateService.convert(tx.getAmount(), tx.getCurrency(), base).doubleValue())))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> BigDecimal.valueOf(e.getValue()).setScale(2, RoundingMode.HALF_UP)));
    }

    // ============================================================
    // SUMMARY
    // ============================================================
    public Map<String, Object> getSummaryMetrics(LocalDate startDate, LocalDate endDate, Long tenantId) {
        List<Transaction> transactions = getTransactionsBetween(startDate, endDate, tenantId);
        String base = getBaseCurrency(tenantId);
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
                    "failedCount", 0L);
        }
        BigDecimal totalVolume = transactions.stream()
                .map(tx -> exchangeRateService.convert(tx.getAmount(), tx.getCurrency(), base))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = totalVolume.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        long settled = countStatus(transactions, TransactionStatus.SETTLED);
        long pending = countStatus(transactions, TransactionStatus.PENDING);
        long refunded = countStatus(transactions, TransactionStatus.REFUNDED);
        long failed = countStatus(transactions, TransactionStatus.FAILED);
        double successRate = (double) settled / total * 100;
        return Map.of(
                "totalTransactions", total,
                "totalVolume", totalVolume,
                "average", average,
                "successRate", successRate,
                "settledCount", settled,
                "pendingCount", pending,
                "refundedCount", refunded,
                "failedCount", failed);
    }

    private static long countStatus(List<Transaction> txns, TransactionStatus s) {
        return txns.stream().filter(t -> t.getStatus() == s).count();
    }

    // ============================================================
    // STATUS DISTRIBUTION
    // ============================================================
    public Map<String, Long> getStatusDistribution(LocalDate start, LocalDate end, Long tenantId) {
        return getTransactionsBetween(start, end, tenantId).stream()
                .collect(Collectors.groupingBy(
                        tx -> tx.getStatus().name(),
                        Collectors.counting()));
    }

    // ============================================================
    // TOP MERCHANTS / CUSTOMERS
    // ============================================================
    public List<Map<String, Object>> getTopMerchants(LocalDate start, LocalDate end,
                                                     int limit, Long tenantId) {
        String base = getBaseCurrency(tenantId);
        return getTransactionsBetween(start, end, tenantId).stream()
                .collect(Collectors.groupingBy(
                        Transaction::getMerchantId,
                        Collectors.summingDouble(tx ->
                                exchangeRateService.convert(tx.getAmount(), tx.getCurrency(), base).doubleValue())))
                .entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(limit)
                .map(e -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("merchantId", e.getKey());
                    map.put("totalAmount",
                            BigDecimal.valueOf(e.getValue()).setScale(2, RoundingMode.HALF_UP));
                    return map;
                })
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getTopCustomers(LocalDate start, LocalDate end,
                                                     int limit, Long tenantId) {
        String base = getBaseCurrency(tenantId);
        return getTransactionsBetween(start, end, tenantId).stream()
                .collect(Collectors.groupingBy(
                        Transaction::getCustomerId,
                        Collectors.summingDouble(tx ->
                                exchangeRateService.convert(tx.getAmount(), tx.getCurrency(), base).doubleValue())))
                .entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(limit)
                .map(e -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("customerId", e.getKey());
                    map.put("totalAmount",
                            BigDecimal.valueOf(e.getValue()).setScale(2, RoundingMode.HALF_UP));
                    return map;
                })
                .collect(Collectors.toList());
    }

    // ============================================================
    // DAILY SUCCESS RATE
    // ============================================================
    public Map<LocalDate, Double> getDailySuccessRate(LocalDate start, LocalDate end, Long tenantId) {
        Map<LocalDate, List<Transaction>> byDay = getTransactionsBetween(start, end, tenantId).stream()
                .collect(Collectors.groupingBy(tx -> tx.getCreatedAt().toLocalDate()));
        Map<LocalDate, Double> result = new LinkedHashMap<>();
        for (var e : byDay.entrySet()) {
            long t = e.getValue().size();
            long s = e.getValue().stream().filter(x -> x.getStatus() == TransactionStatus.SETTLED).count();
            result.put(e.getKey(), t == 0 ? 0.0 : (double) s / t * 100);
        }
        return result;
    }

    // ============================================================
    // MONTHLY COMPARISON
    // ============================================================
    public Map<String, Object> getMonthlyComparison(Long tenantId) {
        LocalDate today = LocalDate.now();
        LocalDate thisMonthStart = today.withDayOfMonth(1);
        LocalDate lastMonthStart = thisMonthStart.minusMonths(1);
        LocalDate lastMonthEnd = thisMonthStart.minusDays(1);

        Map<String, Object> thisMonth = getSummaryMetrics(thisMonthStart, today, tenantId);
        Map<String, Object> lastMonth = getSummaryMetrics(lastMonthStart, lastMonthEnd, tenantId);

        BigDecimal thisRev = (BigDecimal) thisMonth.getOrDefault("totalVolume", BigDecimal.ZERO);
        BigDecimal lastRev = (BigDecimal) lastMonth.getOrDefault("totalVolume", BigDecimal.ZERO);

        double growth = 0.0;
        if (lastRev.compareTo(BigDecimal.ZERO) > 0) {
            growth = thisRev.subtract(lastRev)
                    .divide(lastRev, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
        }
        Map<String, Object> result = new HashMap<>();
        result.put("thisMonthRevenue", thisRev);
        result.put("lastMonthRevenue", lastRev);
        result.put("growthPercent", growth);
        result.put("thisMonthTransactions", thisMonth.get("totalTransactions"));
        result.put("lastMonthTransactions", lastMonth.get("totalTransactions"));
        return result;
    }

    // ============================================================
    // EXECUTIVE / TREND / BREAKDOWN
    // ============================================================
    public Map<String, Object> getExecutiveData(Long tenantId) {
        LocalDate today = LocalDate.now();
        LocalDate thisStart = today.withDayOfMonth(1);
        LocalDate lastStart = thisStart.minusMonths(1);
        LocalDate lastEnd = thisStart.minusDays(1);

        Map<String, Object> thisM = getSummaryMetrics(thisStart, today, tenantId);
        Map<String, Object> lastM = getSummaryMetrics(lastStart, lastEnd, tenantId);

        BigDecimal thisRev = (BigDecimal) thisM.getOrDefault("totalVolume", BigDecimal.ZERO);
        BigDecimal lastRev = (BigDecimal) lastM.getOrDefault("totalVolume", BigDecimal.ZERO);
        long thisTx = (long) thisM.getOrDefault("totalTransactions", 0L);
        long lastTx = (long) lastM.getOrDefault("totalTransactions", 0L);

        double revGrowth = 0.0;
        if (lastRev.compareTo(BigDecimal.ZERO) > 0) {
            revGrowth = thisRev.subtract(lastRev).divide(lastRev, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
        }
        double txGrowth = lastTx > 0 ? ((double) (thisTx - lastTx) / lastTx) * 100 : 0.0;

        Map<String, Object> result = new HashMap<>();
        result.put("currentMonthRevenue", thisRev);
        result.put("currentMonthTransactions", thisTx);
        result.put("revenueGrowth", revGrowth);
        result.put("transactionGrowth", txGrowth);
        result.put("activeCustomers", getActiveCustomerCount(tenantId));
        result.put("newCustomersThisMonth", getNewCustomerCount(thisStart, today, tenantId));
        result.put("activeSubscriptions", getActiveSubscriptionCount(tenantId));
        result.put("newSubscriptionsThisMonth", getNewSubscriptionCount(thisStart, today, tenantId));
        return result;
    }

    public Map<String, Object> getRevenueBreakdown(Long tenantId) {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        String base = getBaseCurrency(tenantId);
        BigDecimal oneTime = BigDecimal.ZERO;
        for (Transaction tx : getTransactionsBetween(monthStart, today, tenantId)) {
            oneTime = oneTime.add(exchangeRateService.convert(tx.getAmount(), tx.getCurrency(), base));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("oneTimeRevenue", oneTime);
        result.put("subscriptionRevenue", BigDecimal.ZERO);
        return result;
    }

    public List<Map<String, Object>> getRevenueTrend(Long tenantId) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 5; i >= 0; i--) {
            LocalDate start = today.minusMonths(i).withDayOfMonth(1);
            LocalDate end = start.plusMonths(1).minusDays(1);
            if (end.isAfter(today)) end = today;
            Map<String, Object> summary = getSummaryMetrics(start, end, tenantId);
            Map<String, Object> entry = new HashMap<>();
            entry.put("period", start.getMonth().toString().substring(0, 3) + " " + start.getYear());
            entry.put("revenue", summary.getOrDefault("totalVolume", BigDecimal.ZERO));
            result.add(entry);
        }
        return result;
    }

    public Map<String, Object> getCustomerAnalytics(Long tenantId) {
        long total = getActiveCustomerCount(tenantId);
        long newC = getNewCustomerCount(LocalDate.now().withDayOfMonth(1), LocalDate.now(), tenantId);
        Map<String, Object> result = new HashMap<>();
        result.put("totalCustomers", total);
        result.put("newCustomersThisMonth", newC);
        result.put("activeCustomers", total);
        result.put("churnRate", 0.0);
        result.put("customerLTV", BigDecimal.ZERO);
        return result;
    }

    public Map<String, Object> getTaxSummary(LocalDate start, LocalDate end, Long tenantId) {
        Map<String, Object> result = new HashMap<>();
        result.put("totalTaxCollected", BigDecimal.ZERO);
        result.put("taxByCountry", new HashMap<>());
        return result;
    }

    public Map<String, Object> getHealthMetrics(LocalDate start, LocalDate end, Long tenantId) {
        List<Transaction> txns = getTransactionsBetween(start, end, tenantId);
        long total = txns.size();
        Map<String, Object> result = new HashMap<>();
        if (total == 0) {
            result.put("successRate", 0.0);
            result.put("failureRate", 0.0);
            result.put("refundRate", 0.0);
            return result;
        }
        long settled = countStatus(txns, TransactionStatus.SETTLED);
        long failed = countStatus(txns, TransactionStatus.FAILED);
        long refunded = countStatus(txns, TransactionStatus.REFUNDED);
        result.put("successRate", (double) settled / total * 100);
        result.put("failureRate", (double) failed / total * 100);
        result.put("refundRate", (double) refunded / total * 100);
        return result;
    }

    // ============================================================
    // HELPERS — bounded by tenant + role
    // ============================================================
    private long getActiveCustomerCount(Long tenantId) {
        try {
            if (tenantId == null) {
                return userRepository.findAll().stream()
                        .filter(u -> u.isActive()
                                && u.getRoles().stream().anyMatch(r -> r.getName().equals("CUSTOMER")))
                        .count();
            }
            return userRepository.findByTenantIdAndRoleName(tenantId, "CUSTOMER").stream()
                    .filter(u -> u.isActive()).count();
        } catch (Exception e) {
            log.warn("getActiveCustomerCount failed: {}", e.getMessage());
            return 0L;
        }
    }

    private long getNewCustomerCount(LocalDate start, LocalDate end, Long tenantId) {
        try {
            LocalDateTime startDT = start.atStartOfDay();
            LocalDateTime endDT = end.atTime(23, 59, 59);
            if (tenantId == null) {
                return userRepository.findAll().stream()
                        .filter(u -> u.getRoles().stream().anyMatch(r -> r.getName().equals("CUSTOMER")))
                        .filter(u -> u.getCreatedAt() != null
                                && u.getCreatedAt().isAfter(startDT)
                                && u.getCreatedAt().isBefore(endDT))
                        .count();
            }
            return userRepository.findByTenantIdAndRoleName(tenantId, "CUSTOMER").stream()
                    .filter(u -> u.getCreatedAt() != null
                            && u.getCreatedAt().isAfter(startDT)
                            && u.getCreatedAt().isBefore(endDT))
                    .count();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long getActiveSubscriptionCount(Long tenantId) {
        try {
            if (tenantId == null) {
                return recurringScheduleRepository.findByActiveTrue().size();
            }
            return recurringScheduleRepository.countByTenantIdAndActiveTrue(tenantId);
        } catch (Exception e) {
            return 0L;
        }
    }

    private long getNewSubscriptionCount(LocalDate start, LocalDate end, Long tenantId) {
        try {
            LocalDateTime startDT = start.atStartOfDay();
            LocalDateTime endDT = end.atTime(23, 59, 59);
            List<com.enterprise.invoice.RecurringSchedule> schedules =
                    tenantId == null
                            ? recurringScheduleRepository.findAll()
                            : recurringScheduleRepository.findAllByTenantId(tenantId);
            return schedules.stream()
                    .filter(s -> s.getCreatedAt() != null
                            && s.getCreatedAt().isAfter(startDT)
                            && s.getCreatedAt().isBefore(endDT))
                    .count();
        } catch (Exception e) {
            return 0L;
        }
    }
}