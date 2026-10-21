package com.stockpulse.engine;

import com.stockpulse.domain.PricingAction;
import com.stockpulse.domain.RecommendationSource;
import com.stockpulse.domain.TriggerReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.stockpulse.engine.Fixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;

class RulePricingPolicyTest {

    private final RulePricingPolicy policy = Fixtures.pricing();

    @Test
    void outOfStockHoldsPrice() {
        PricingRecommendation r = policy.recommend(ctx("59.99", 0, 15, 0, 0, 0, TriggerReason.LOW_STOCK));
        assertThat(r.action()).isEqualTo(PricingAction.HOLD);
        assertThat(r.recommendedPrice()).isEqualByComparingTo("59.99");
        assertThat(r.source()).isEqualTo(RecommendationSource.RULE_BASED);
    }

    @Test
    void underThreeDaysOfCoverRaisesAboutTenPercent() {
        // T-shirt after one order: 7 left at 13/day → 0.5 days of cover
        PricingRecommendation r = policy.recommend(ctx("24.99", 7, 15, 13, 12, 1, TriggerReason.LOW_STOCK));
        assertThat(r.action()).isEqualTo(PricingAction.RAISE);
        assertThat(r.recommendedPrice()).isGreaterThan(new BigDecimal("24.99"));
        assertThat(r.recommendedPrice()).isLessThanOrEqualTo(new BigDecimal("27.49"));
        assertThat(r.reasoning()).contains("7 units left");
        // reasoning states the price actually applied after .99 snapping, not the 10% target
        assertThat(r.reasoning()).contains("to $" + r.recommendedPrice().toPlainString()).contains("+8.0%");
    }

    @Test
    void demandSpikeRaisesAndCitesVelocity() {
        // Hoodie burst: 6 units in 10 min, 21/day vs 15 baseline
        PricingRecommendation r = policy.recommend(ctx("54.99", 5, 12, 21, 15, 6, TriggerReason.DEMAND_SPIKE));
        assertThat(r.action()).isEqualTo(PricingAction.RAISE);
        assertThat(r.reasoning()).startsWith("Demand spike").contains("21/day").contains("15 baseline");
    }

    @Test
    void spikeDetectedFromNumbersEvenOnManualTrigger() {
        assertThat(policy.isSpike(ctx("54.99", 30, 12, 21, 15, 6, TriggerReason.MANUAL))).isTrue();
        assertThat(policy.isSpike(ctx("54.99", 30, 12, 19, 15, 4, TriggerReason.MANUAL))).isFalse();
    }

    @Test
    void slowMoverWithLotsOfStockIsLowered() {
        // USB-C hub: 120 in stock at 1/day
        PricingRecommendation r = policy.recommend(ctx("34.99", 120, 30, 1, 1, 0, TriggerReason.MANUAL));
        assertThat(r.action()).isEqualTo(PricingAction.LOWER);
        assertThat(r.recommendedPrice()).isLessThan(new BigDecimal("34.99"));
    }

    @Test
    void balancedProductHolds() {
        PricingRecommendation r = policy.recommend(ctx("79.99", 45, 20, 3, 3, 0, TriggerReason.MANUAL));
        assertThat(r.action()).isEqualTo(PricingAction.HOLD);
    }

    @Test
    void neverExceedsGuardrailBand() {
        PricingRecommendation r = policy.recommend(ctx("10.00", 1, 15, 100, 5, 50, TriggerReason.DEMAND_SPIKE));
        assertThat(r.recommendedPrice()).isLessThanOrEqualTo(new BigDecimal("12.00"));
    }
}
