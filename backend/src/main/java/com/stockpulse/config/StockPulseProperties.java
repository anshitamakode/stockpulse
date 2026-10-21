package com.stockpulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every business threshold lives here so the rules are tunable without code changes and the
 * ADR can point at one place. Bound from {@code stockpulse.*} in application.properties.
 */
@ConfigurationProperties(prefix = "stockpulse")
public record StockPulseProperties(
        @DefaultValue("ai") String strategy,
        @DefaultValue Demand demand,
        @DefaultValue Pricing pricing,
        @DefaultValue Reorder reorder,
        @DefaultValue Recovery recovery) {

    /**
     * @param windowMinutes rolling window for "recent" sales
     * @param spikeMinUnits units sold inside the window before a spike can fire at all
     * @param spikeRatio    current velocity must be at least baseline × ratio
     */
    public record Demand(
            @DefaultValue("10") int windowMinutes,
            @DefaultValue("5") int spikeMinUnits,
            @DefaultValue("1.3") double spikeRatio) {}

    /**
     * @param maxChangePct hard guardrail: no suggestion (AI or rules) may move price more than this
     * @param minPrice     floor for any recommended price
     */
    public record Pricing(
            @DefaultValue("20") double maxChangePct,
            @DefaultValue("1.00") double minPrice) {}

    /**
     * @param leadTimeDays supplier lead time assumed until sprint 2 adds per-supplier values
     * @param coverDays    days of demand the order should cover after it lands
     * @param casePack     quantities are rounded up to this multiple
     * @param maxQuantity  sanity ceiling for any recommended quantity
     */
    public record Reorder(
            @DefaultValue("7") int leadTimeDays,
            @DefaultValue("14") int coverDays,
            @DefaultValue("5") int casePack,
            @DefaultValue("2000") int maxQuantity) {}

    /**
     * Recovery of suggestions stuck in PROCESSING (app crash, lost async task, hung call).
     *
     * @param staleAfterSeconds  a PROCESSING row older than this is re-dispatched; must exceed the
     *                           worst-case LLM time (models × attempts × timeout) to avoid needless repeats
     * @param giveUpAfterSeconds older than this it is marked FAILED instead
     */
    public record Recovery(
            @DefaultValue("120") int staleAfterSeconds,
            @DefaultValue("600") int giveUpAfterSeconds) {}
}
