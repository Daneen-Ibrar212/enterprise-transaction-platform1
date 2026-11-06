package com.enterprise.currency;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

@Service
public class CurrencyService {

    private final ExchangeRateService exchangeRateService;

    public CurrencyService(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    public String format(BigDecimal amount, String currencyCode) {
        // ✅ Null-safe — previously NPE'd if currencyCode was null
        if (amount == null) return "";
        if (currencyCode == null || currencyCode.isBlank()) return amount.toPlainString();

        Locale locale = switch (currencyCode.toUpperCase()) {
            case "USD" -> Locale.US;
            case "EUR" -> Locale.GERMANY;
            case "GBP" -> Locale.UK;
            default -> Locale.UK;
        };
        try {
            NumberFormat format = NumberFormat.getCurrencyInstance(locale);
            java.util.Currency currency = java.util.Currency.getInstance(currencyCode.toUpperCase());
            format.setCurrency(currency);
            return format.format(amount);
        } catch (IllegalArgumentException e) {
            // Unknown currency — fall back to a plain numeric format
            return amount.toPlainString() + " " + currencyCode.toUpperCase();
        }
    }

    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        return exchangeRateService.convert(amount, fromCurrency, toCurrency);
    }

    public String getSymbol(String currencyCode) {
        return exchangeRateService.getSymbol(currencyCode);
    }
}