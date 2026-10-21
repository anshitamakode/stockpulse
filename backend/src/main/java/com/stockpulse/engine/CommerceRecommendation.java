package com.stockpulse.engine;

/**
 * @param aiModel      model that produced the AI parts; null for pure rule answers
 * @param ruleBaseline what the rules alone would have said, kept so merchandisers (and the ADR)
 *                     can compare AI against rules; null when the rules are the answer
 */
public record CommerceRecommendation(PricingRecommendation pricing, ReorderRecommendation reorder,
                                     String aiModel, CommerceRecommendation ruleBaseline) {

    public CommerceRecommendation(PricingRecommendation pricing, ReorderRecommendation reorder) {
        this(pricing, reorder, null, null);
    }
}
