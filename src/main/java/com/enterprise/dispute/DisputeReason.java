package com.enterprise.dispute;

public enum DisputeReason {
    FRAUDULENT,
    PRODUCT_NOT_RECEIVED,
    PRODUCT_NOT_AS_DESCRIBED,
    DUPLICATE_CHARGE,
    SUBSCRIPTION_CANCELLED,
    CREDIT_NOT_PROCESSED,
    OTHER;

    public String getDisplayName() {
        return switch (this) {
            case FRAUDULENT -> "Fraudulent Charge";
            case PRODUCT_NOT_RECEIVED -> "Product Not Received";
            case PRODUCT_NOT_AS_DESCRIBED -> "Product Not as Described";
            case DUPLICATE_CHARGE -> "Duplicate Charge";
            case SUBSCRIPTION_CANCELLED -> "Subscription Cancelled";
            case CREDIT_NOT_PROCESSED -> "Credit Not Processed";
            case OTHER -> "Other";
        };
    }
}