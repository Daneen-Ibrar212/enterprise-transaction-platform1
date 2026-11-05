package com.enterprise.plan;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "platform_plan")
public class PlatformPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String tagline;

    @Column(name = "monthly_price_pence", nullable = false)
    private int monthlyPricePence;

    @Column(nullable = false, length = 3)
    private String currency = "GBP";

    @Column(name = "invoice_limit")
    private Integer invoiceLimit;   // null = unlimited

    @Column(name = "user_limit")
    private Integer userLimit;      // null = unlimited

    @Column(name = "features_json", columnDefinition = "TEXT")
    private String featuresJson;

    @Column(name = "feature_flags_json", columnDefinition = "TEXT")
    private String featureFlagsJson;

    @Column(nullable = false)
    private boolean highlighted = false;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "stripe_price_id")
    private String stripePriceId;

    @Column(name = "annual_price_pence")
    private Integer annualPricePence;
    // ---- Convenience ----
    public String getFormattedMonthlyPrice() {
        return "£" + String.format("%,d", monthlyPricePence / 100);
    }

    public String getInvoiceLimitDisplay() {
        return invoiceLimit == null ? "Unlimited" : "Up to " + invoiceLimit + " / month";
    }

    /**
     * Parsed view of featuresJson. Cached for the lifetime of this instance.
     * Not persisted — safe to compute on demand.
     */
    @Transient
    public List<String> getFeaturesList() {
        if (featuresJson == null || featuresJson.isBlank()) return List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    featuresJson,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTagline() { return tagline; }
    public void setTagline(String tagline) { this.tagline = tagline; }

    public int getMonthlyPricePence() { return monthlyPricePence; }
    public void setMonthlyPricePence(int monthlyPricePence) { this.monthlyPricePence = monthlyPricePence; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Integer getInvoiceLimit() { return invoiceLimit; }
    public void setInvoiceLimit(Integer invoiceLimit) { this.invoiceLimit = invoiceLimit; }

    public Integer getUserLimit() { return userLimit; }
    public void setUserLimit(Integer userLimit) { this.userLimit = userLimit; }

    public String getFeaturesJson() { return featuresJson; }
    public void setFeaturesJson(String featuresJson) { this.featuresJson = featuresJson; }

    public String getFeatureFlagsJson() { return featureFlagsJson; }
    public void setFeatureFlagsJson(String featureFlagsJson) { this.featureFlagsJson = featureFlagsJson; }

    public boolean isHighlighted() { return highlighted; }
    public void setHighlighted(boolean highlighted) { this.highlighted = highlighted; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

        public String getStripePriceId() { return stripePriceId; }
    public void setStripePriceId(String stripePriceId) { this.stripePriceId = stripePriceId; }

    public Integer getAnnualPricePence() { return annualPricePence; }
    public void setAnnualPricePence(Integer annualPricePence) { this.annualPricePence = annualPricePence; }
}