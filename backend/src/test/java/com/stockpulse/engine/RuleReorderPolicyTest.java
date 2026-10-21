package com.stockpulse.engine;

import com.stockpulse.domain.TriggerReason;
import org.junit.jupiter.api.Test;

import static com.stockpulse.engine.Fixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;

class RuleReorderPolicyTest {

    private final RuleReorderPolicy policy = Fixtures.reorder();

    @Test
    void noReorderWhenStockIsHealthy() {
        ReorderRecommendation r = policy.recommend(ctx("79.99", 45, 20, 3, 3, 0, TriggerReason.MANUAL));
        assertThat(r.quantity()).isZero();
        assertThat(r.reasoning()).startsWith("No reorder needed");
    }

    @Test
    void sizesOrderForLeadTimePlusCoverPlusSafetyStock() {
        // 7 on hand, 13/day: ceil(13 × 21) + 15 − 7 = 281 → rounded to case pack 5 = 285
        ReorderRecommendation r = policy.recommend(ctx("24.99", 7, 15, 13, 12, 1, TriggerReason.LOW_STOCK));
        assertThat(r.quantity()).isEqualTo(285);
        assertThat(r.quantity() % 5).isZero();
    }

    @Test
    void spikeOnlyCountsHalfTheExcessDemand() {
        // baseline 15, spike 21 → plan on 18/day: ceil(18 × 21) + 12 − 5 = 385
        ReorderRecommendation r = policy.recommend(ctx("54.99", 5, 12, 21, 15, 6, TriggerReason.DEMAND_SPIKE));
        assertThat(r.quantity()).isEqualTo(385);
        assertThat(r.reasoning()).contains("half of the spike");
    }

    @Test
    void noDemandHistoryOrdersTwiceThreshold() {
        ReorderRecommendation r = policy.recommend(ctx("59.99", 0, 15, 0, 0, 0, TriggerReason.LOW_STOCK));
        assertThat(r.quantity()).isEqualTo(30);
        assertThat(r.confidence()).isLessThan(0.6);
    }

    @Test
    void quantityIsCapped() {
        ReorderRecommendation r = policy.recommend(ctx("5.00", 0, 10, 500, 500, 0, TriggerReason.LOW_STOCK));
        assertThat(r.quantity()).isEqualTo(2000);
    }
}
