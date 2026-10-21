package com.stockpulse.domain;

/**
 * Product lifecycle. ACTIVE / PRICE_REVIEW_PENDING / OUT_OF_STOCK are derived from facts
 * (stock level, open pricing suggestions) by {@link #derive}; DISCONTINUED is set by a human
 * and is sticky — the derivation never overrides it.
 */
public enum ProductStatus {
    ACTIVE,
    PRICE_REVIEW_PENDING,
    OUT_OF_STOCK,
    DISCONTINUED;

    public static ProductStatus derive(ProductStatus current, int stockLevel, boolean hasOpenPricingSuggestion) {
        if (current == DISCONTINUED) return DISCONTINUED;
        if (stockLevel <= 0) return OUT_OF_STOCK;
        if (hasOpenPricingSuggestion) return PRICE_REVIEW_PENDING;
        return ACTIVE;
    }
}
