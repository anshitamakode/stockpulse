package com.stockpulse.service;

import com.stockpulse.domain.TriggerReason;
import com.stockpulse.event.SuggestionsOpenedEvent;
import com.stockpulse.service.StaleSuggestionSweeper.Plan;
import com.stockpulse.service.StaleSuggestionSweeper.StaleRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StaleSuggestionSweeperTest {

    private final Instant now = Instant.parse("2026-10-21T12:00:00Z");
    private final Instant giveUpBefore = now.minusSeconds(600);

    @Test
    void pricingAndReorderFromSameSignalAreRetriedTogether() {
        UUID p = UUID.randomUUID(), r = UUID.randomUUID();
        Plan plan = StaleSuggestionSweeper.plan(List.of(
                new StaleRow(p, true, "PRD-003", TriggerReason.LOW_STOCK, now.minusSeconds(180)),
                new StaleRow(r, false, "PRD-003", TriggerReason.LOW_STOCK, now.minusSeconds(180))), giveUpBefore);

        assertThat(plan.giveUp().isEmpty()).isTrue();
        assertThat(plan.retry().size()).isEqualTo(1);
        SuggestionsOpenedEvent e = plan.retry().get(0);
        assertThat(e.pricingSuggestionId()).isEqualTo(p);
        assertThat(e.reorderSuggestionId()).isEqualTo(r);
        assertThat(e.trigger()).isEqualTo(TriggerReason.LOW_STOCK);
    }

    @Test
    void rowsPastTheLimitAreGivenUpNotRetried() {
        UUID old = UUID.randomUUID(), fresh = UUID.randomUUID();
        Plan plan = StaleSuggestionSweeper.plan(List.of(
                new StaleRow(old, true, "PRD-008", TriggerReason.LOW_STOCK, now.minusSeconds(900)),
                new StaleRow(fresh, true, "PRD-001", TriggerReason.MANUAL, now.minusSeconds(150))), giveUpBefore);

        assertThat(plan.giveUp().size()).isEqualTo(1);
        assertThat(plan.giveUp().get(0).pricingSuggestionId()).isEqualTo(old);
        assertThat(plan.retry().size()).isEqualTo(1);
        assertThat(plan.retry().get(0).pricingSuggestionId()).isEqualTo(fresh);
        assertThat(plan.retry().get(0).reorderSuggestionId()).isNull();
    }

    @Test
    void differentProductsAndTriggersStaySeparate() {
        Plan plan = StaleSuggestionSweeper.plan(List.of(
                new StaleRow(UUID.randomUUID(), true, "PRD-003", TriggerReason.LOW_STOCK, now.minusSeconds(200)),
                new StaleRow(UUID.randomUUID(), true, "PRD-008", TriggerReason.LOW_STOCK, now.minusSeconds(200)),
                new StaleRow(UUID.randomUUID(), false, "PRD-008", TriggerReason.DEMAND_SPIKE, now.minusSeconds(200))),
                giveUpBefore);
        assertThat(plan.retry().size()).isEqualTo(3);
    }
}
