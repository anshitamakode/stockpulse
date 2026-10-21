package com.stockpulse.engine;

import com.stockpulse.domain.RecommendationSource;

/** {@code quantity == 0} means "no reorder needed"; the suggestion is then recorded as SKIPPED. */
public record ReorderRecommendation(
        int quantity,
        double confidence,
        String reasoning,
        RecommendationSource source,
        String fallbackReason) {

    public ReorderRecommendation withFallback(String reason) {
        return new ReorderRecommendation(quantity, confidence, reasoning,
                RecommendationSource.AI_FALLBACK, reason);
    }
}
