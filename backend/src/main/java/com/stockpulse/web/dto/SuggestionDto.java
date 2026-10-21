package com.stockpulse.web.dto;

import com.stockpulse.domain.PricingAction;
import com.stockpulse.domain.PricingSuggestion;
import com.stockpulse.domain.RecommendationSource;
import com.stockpulse.domain.ReorderSuggestion;
import com.stockpulse.domain.Suggestion;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * One shape for both suggestion kinds so the console can render a single queue.
 * Pricing-only and reorder-only fields are null for the other kind.
 */
public record SuggestionDto(
        UUID id,
        SuggestionKind kind,
        String productId,
        String productName,
        SuggestionStatus status,
        TriggerReason triggerReason,
        boolean autoTriggered,
        RecommendationSource source,
        double confidence,
        String reasoning,
        String fallbackReason,
        String aiModel,
        String ruleBaseline,
        Instant createdAt,
        Instant decidedAt,
        // pricing
        PricingAction action,
        BigDecimal basePrice,
        BigDecimal recommendedPrice,
        BigDecimal priceChangePct,
        // reorder
        Integer stockAtRequest,
        Integer recommendedQuantity) {

    public static SuggestionDto from(PricingSuggestion s) {
        BigDecimal pct = null;
        if (s.getRecommendedPrice() != null && s.getBasePrice().signum() > 0) {
            pct = s.getRecommendedPrice().subtract(s.getBasePrice())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(s.getBasePrice(), 1, RoundingMode.HALF_UP);
        }
        return base(s, SuggestionKind.PRICING, s.getAction(), s.getBasePrice(), s.getRecommendedPrice(), pct, null, null);
    }

    public static SuggestionDto from(ReorderSuggestion s) {
        return base(s, SuggestionKind.REORDER, null, null, null, null, s.getStockAtRequest(), s.getRecommendedQuantity());
    }

    private static SuggestionDto base(Suggestion s, SuggestionKind kind, PricingAction action, BigDecimal basePrice,
                                      BigDecimal recommendedPrice, BigDecimal pct, Integer stockAtRequest, Integer qty) {
        return new SuggestionDto(s.getId(), kind, s.getProduct().getId(), s.getProduct().getName(), s.getStatus(),
                s.getTriggerReason(), s.getTriggerReason().isAutomatic(), s.getSource(), s.getConfidence(),
                s.getReasoning(), s.getFallbackReason(), s.getAiModel(), s.getRuleBaseline(),
                s.getCreatedAt(), s.getDecidedAt(),
                action, basePrice, recommendedPrice, pct, stockAtRequest, qty);
    }
}
