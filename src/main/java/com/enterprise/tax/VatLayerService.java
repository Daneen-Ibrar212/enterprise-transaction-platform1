package com.enterprise.tax;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Local VAT rate table.
 * <p>
 * Rates are hardcoded and updated manually when countries change their VAT.
 * No external API dependency — instant startup, no network failures.
 * <p>
 * To update a rate, change the value in {@link #buildDefaultRates()}.
 */
@Service
public class VatLayerService {

    private static final Logger log = LoggerFactory.getLogger(VatLayerService.class);

    private Map<String, BigDecimal> countryTaxRates = new HashMap<>();

    @PostConstruct
    public void init() {
        log.info("📊 Loading VAT rate table...");
        countryTaxRates = buildDefaultRates();
        log.info("✅ Loaded {} VAT rates from local table", countryTaxRates.size());
        log.info("   Countries covered: {}", countryTaxRates.keySet());
    }

    /**
     * Get standard VAT rate for a country.
     * Returns ZERO if the country is unknown.
     */
    public BigDecimal getVatRate(String countryCode) {
        if (countryCode == null || countryCode.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = countryTaxRates.get(countryCode.toUpperCase());
        return rate != null ? rate : BigDecimal.ZERO;
    }

    /**
     * Get country name from code (fallback: return the code itself).
     */
    public String getCountryName(String countryCode) {
        if (countryCode == null) return "Unknown";
        return switch (countryCode.toUpperCase()) {
            case "GB" -> "United Kingdom";
            case "DE" -> "Germany";
            case "FR" -> "France";
            case "IT" -> "Italy";
            case "ES" -> "Spain";
            case "NL" -> "Netherlands";
            case "BE" -> "Belgium";
            case "PL" -> "Poland";
            case "PT" -> "Portugal";
            case "IE" -> "Ireland";
            case "AT" -> "Austria";
            case "SE" -> "Sweden";
            case "FI" -> "Finland";
            case "DK" -> "Denmark";
            case "NO" -> "Norway";
            case "CH" -> "Switzerland";
            case "US" -> "United States";
            case "AU" -> "Australia";
            case "NZ" -> "New Zealand";
            case "CA" -> "Canada";
            default -> countryCode.toUpperCase();
        };
    }

    // ============================================================
    // RATE TABLE — update manually when VAT rates change
    // ============================================================

    private Map<String, BigDecimal> buildDefaultRates() {
        Map<String, BigDecimal> rates = new HashMap<>();

        // European Union (standard VAT)
        rates.put("GB", BigDecimal.valueOf(20.0));   // United Kingdom
        rates.put("DE", BigDecimal.valueOf(19.0));   // Germany
        rates.put("FR", BigDecimal.valueOf(20.0));   // France
        rates.put("IT", BigDecimal.valueOf(22.0));   // Italy
        rates.put("ES", BigDecimal.valueOf(21.0));   // Spain
        rates.put("NL", BigDecimal.valueOf(21.0));   // Netherlands
        rates.put("BE", BigDecimal.valueOf(21.0));   // Belgium
        rates.put("PL", BigDecimal.valueOf(23.0));   // Poland
        rates.put("PT", BigDecimal.valueOf(23.0));   // Portugal
        rates.put("IE", BigDecimal.valueOf(23.0));   // Ireland
        rates.put("AT", BigDecimal.valueOf(20.0));   // Austria
        rates.put("SE", BigDecimal.valueOf(25.0));   // Sweden
        rates.put("FI", BigDecimal.valueOf(24.0));   // Finland
        rates.put("DK", BigDecimal.valueOf(25.0));   // Denmark
        rates.put("GR", BigDecimal.valueOf(24.0));   // Greece
        rates.put("CZ", BigDecimal.valueOf(21.0));   // Czech Republic
        rates.put("HU", BigDecimal.valueOf(27.0));   // Hungary
        rates.put("RO", BigDecimal.valueOf(19.0));   // Romania
        rates.put("BG", BigDecimal.valueOf(20.0));   // Bulgaria
        rates.put("HR", BigDecimal.valueOf(25.0));   // Croatia
        rates.put("SK", BigDecimal.valueOf(20.0));   // Slovakia
        rates.put("SI", BigDecimal.valueOf(22.0));   // Slovenia
        rates.put("LT", BigDecimal.valueOf(21.0));   // Lithuania
        rates.put("LV", BigDecimal.valueOf(21.0));   // Latvia
        rates.put("EE", BigDecimal.valueOf(22.0));   // Estonia
        rates.put("LU", BigDecimal.valueOf(17.0));   // Luxembourg
        rates.put("MT", BigDecimal.valueOf(18.0));   // Malta
        rates.put("CY", BigDecimal.valueOf(19.0));   // Cyprus

        // Non-EU Europe
        rates.put("NO", BigDecimal.valueOf(25.0));   // Norway
        rates.put("CH", BigDecimal.valueOf(7.7));    // Switzerland

        // Americas
        rates.put("US", BigDecimal.ZERO);            // USA (state-level, not federal)
        rates.put("CA", BigDecimal.valueOf(13.0));   // Canada (HST)

        // Oceania
        rates.put("AU", BigDecimal.valueOf(10.0));   // Australia (GST)
        rates.put("NZ", BigDecimal.valueOf(15.0));   // New Zealand (GST)

        return rates;
    }
}