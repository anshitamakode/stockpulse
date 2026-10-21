package com.stockpulse.engine;

import com.stockpulse.domain.PricingAction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PriceGuardrailsTest {

    private final PriceGuardrails g = Fixtures.guardrails();
    private final BigDecimal price = new BigDecimal("50.00");

    @Test
    void bandIsTwentyPercentEachWay() {
        assertThat(g.lowerBound(price)).isEqualByComparingTo("40.00");
        assertThat(g.upperBound(price)).isEqualByComparingTo("60.00");
    }

    @Test
    void applySnapsToNinetyNineWhenDirectionIsKept() {
        assertThat(g.apply(new BigDecimal("24.99"), 10)).isEqualByComparingTo("26.99");
        assertThat(g.apply(new BigDecimal("34.99"), -10)).isEqualByComparingTo("31.99");
    }

    @Test
    void applyKeepsDirectionWhenSnappingWouldUndoIt() {
        // +1% of 24.99 = 25.24 → nearest .99 is 24.99 (no change), so keep the raw raise
        assertThat(g.apply(new BigDecimal("24.99"), 1)).isEqualByComparingTo("25.24");
    }

    @Test
    void violationsDescribeTheProblem() {
        assertThat(g.violation(price, PricingAction.RAISE, new BigDecimal("55.00"))).isNull();
        assertThat(g.violation(price, PricingAction.RAISE, new BigDecimal("75.00"))).contains("outside guardrail");
        assertThat(g.violation(price, PricingAction.RAISE, new BigDecimal("45.00"))).contains("not above");
        assertThat(g.violation(price, PricingAction.LOWER, new BigDecimal("55.00"))).contains("not below");
        assertThat(g.violation(price, PricingAction.HOLD, new BigDecimal("50.00"))).isNull();
        assertThat(g.violation(price, PricingAction.HOLD, new BigDecimal("51.00"))).contains("HOLD");
        assertThat(g.violation(price, PricingAction.RAISE, null)).contains("missing");
    }
}
