package com.stockpulse.engine;

import com.stockpulse.domain.PricingAction;
import com.stockpulse.domain.RecommendationSource;

import java.math.BigDecimal;

public record PricingRecommendation(
        PricingAction action,
        BigDecimal recommendedPrice,
        double confidence,
        String reasoning,
        RecommendationSource source,
        String fallbackReason) {

    public PricingRecommendation withFallback(String reason) {
        return new PricingRecommendation(action, recommendedPrice, confidence, reasoning,
                RecommendationSource.AI_FALLBACK, reason);
    }
}
