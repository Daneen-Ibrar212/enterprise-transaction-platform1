package com.enterprise.currency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);
    private static final int SCALE = 6;

    private final ExchangeRateRepository exchangeRateRepository;

    public ExchangeRateService(ExchangeRateRepository exchangeRateRepository) {
        this.exchangeRateRepository = exchangeRateRepository;
    }

    /**
     * Convert an amount from one currency to another.
     *
     * Key fix vs previous version: we cache the RATE (a per-pair value), not the
     * converted AMOUNT (which depends on the input amount). The previous code
     * cached by "from:to" only — a second call for a different amount returned
     * the first call's converted amount.
     */
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        if (fromCurrency == null || toCurrency == null) {
            throw new IllegalArgumentException("currency codes must not be null");
        }
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return amount;
        }

        BigDecimal rate = getRate(fromCurrency, toCurrency);
        if (rate == null) {
            // ✅ Do NOT silently return the original amount — that masks a data
            //    integrity failure (payment proceeds at the wrong FX rate).
            throw new IllegalArgumentException(
                    "No exchange rate available for " + fromCurrency + " -> " + toCurrency);
        }
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    @Cacheable(value = "exchangeRates", key = "#fromCurrency + ':' + #toCurrency")
    public BigDecimal getRate(String fromCurrency, String toCurrency) {
        Optional<ExchangeRate> direct =
                exchangeRateRepository.findByFromCurrencyAndToCurrency(fromCurrency, toCurrency);
        if (direct.isPresent()) {
            return direct.get().getRate();
        }

        Optional<ExchangeRate> inverse =
                exchangeRateRepository.findByFromCurrencyAndToCurrency(toCurrency, fromCurrency);
        if (inverse.isPresent()) {
            BigDecimal inverseRate = inverse.get().getRate();
            if (inverseRate.compareTo(BigDecimal.ZERO) != 0) {
                return BigDecimal.ONE.divide(inverseRate, SCALE, RoundingMode.HALF_UP);
            }
        }

        log.warn("No exchange rate available for {} -> {}", fromCurrency, toCurrency);
        return null;
    }

    @CacheEvict(value = "exchangeRates", allEntries = true)
    @Transactional
    public void updateRate(String fromCurrency, String toCurrency, BigDecimal newRate) {
        if (fromCurrency == null || toCurrency == null || newRate == null
                || newRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Invalid rate parameters");
        }
        saveRateInternal(fromCurrency, toCurrency, newRate);
        BigDecimal inverseRate = BigDecimal.ONE.divide(newRate, SCALE, RoundingMode.HALF_UP);
        saveRateInternal(toCurrency, fromCurrency, inverseRate);
        log.info("Updated exchange rate {} -> {} = {} (inverse {})",
                fromCurrency, toCurrency, newRate, inverseRate);
    }

    private void saveRateInternal(String fromCurrency, String toCurrency, BigDecimal rate) {
        ExchangeRate exchangeRate = exchangeRateRepository
                .findByFromCurrencyAndToCurrency(fromCurrency, toCurrency)
                .orElse(new ExchangeRate(fromCurrency, toCurrency, rate));
        exchangeRate.setRate(rate);
        exchangeRate.setUpdatedAt(LocalDateTime.now());
        exchangeRateRepository.save(exchangeRate);
    }

    @CacheEvict(value = "exchangeRates", allEntries = true)
    @Transactional
    public void deleteRate(Long id) {
        ExchangeRate rate = exchangeRateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Rate not found"));
        exchangeRateRepository.findByFromCurrencyAndToCurrency(rate.getToCurrency(), rate.getFromCurrency())
                .ifPresent(exchangeRateRepository::delete);
        exchangeRateRepository.delete(rate);
    }

    public List<ExchangeRate> getAllRates() {
        return exchangeRateRepository.findAll();
    }

    public Optional<ExchangeRate> getRate(Long id) {
        return exchangeRateRepository.findById(id);
    }

    public String getSymbol(String currencyCode) {
        return CurrencySymbols.getSymbol(currencyCode);
    }
}