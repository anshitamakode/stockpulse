package com.stockpulse.engine;

import org.springframework.stereotype.Component;

@Component
public class RuleBasedCommerceStrategy implements CommerceStrategy {

    public static final String NAME = "ruleBased";

    private final RulePricingPolicy pricing;
    private final RuleReorderPolicy reorder;

    public RuleBasedCommerceStrategy(RulePricingPolicy pricing, RuleReorderPolicy reorder) {
        this.pricing = pricing;
        this.reorder = reorder;
    }

    @Override
    public CommerceRecommendation recommend(ProductContext context) {
        return new CommerceRecommendation(pricing.recommend(context), reorder.recommend(context));
    }

    @Override
    public String name() {
        return NAME;
    }
}
