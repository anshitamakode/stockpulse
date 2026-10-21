package com.stockpulse.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateMachineTest {

    @Test
    void suggestionHappyPath() {
        PricingSuggestion s = new PricingSuggestion();
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PROCESSING);
        s.transitionTo(SuggestionStatus.PENDING, Instant.now());
        assertThat(s.getDecidedAt()).isNull();
        s.transitionTo(SuggestionStatus.ACCEPTED, Instant.now());
        assertThat(s.getDecidedAt()).isNotNull();
    }

    @Test
    void terminalStatesCannotMove() {
        for (SuggestionStatus terminal : new SuggestionStatus[]{
                SuggestionStatus.ACCEPTED, SuggestionStatus.REJECTED, SuggestionStatus.FAILED,
                SuggestionStatus.SKIPPED, SuggestionStatus.SUPERSEDED}) {
            for (SuggestionStatus next : SuggestionStatus.values()) {
                assertThat(terminal.canTransitionTo(next)).as(terminal + "->" + next).isFalse();
            }
        }
    }

    @Test
    void cannotDecideWhileProcessing() {
        ReorderSuggestion s = new ReorderSuggestion();
        assertThatThrownBy(() -> s.transitionTo(SuggestionStatus.ACCEPTED, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productStatusIsDerivedFromFacts() {
        assertThat(ProductStatus.derive(ProductStatus.ACTIVE, 0, true)).isEqualTo(ProductStatus.OUT_OF_STOCK);
        assertThat(ProductStatus.derive(ProductStatus.ACTIVE, 5, true)).isEqualTo(ProductStatus.PRICE_REVIEW_PENDING);
        assertThat(ProductStatus.derive(ProductStatus.PRICE_REVIEW_PENDING, 5, false)).isEqualTo(ProductStatus.ACTIVE);
        assertThat(ProductStatus.derive(ProductStatus.DISCONTINUED, 0, true)).isEqualTo(ProductStatus.DISCONTINUED);
    }

    @Test
    void spikeOutranksLowStockWhichOutranksManual() {
        assertThat(TriggerReason.DEMAND_SPIKE.outranks(TriggerReason.LOW_STOCK)).isTrue();
        assertThat(TriggerReason.LOW_STOCK.outranks(TriggerReason.MANUAL)).isTrue();
        assertThat(TriggerReason.LOW_STOCK.outranks(TriggerReason.DEMAND_SPIKE)).isFalse();
    }
}
