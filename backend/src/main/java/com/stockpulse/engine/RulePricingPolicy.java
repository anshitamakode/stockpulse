package com.stockpulse.engine;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.PricingAction;
import com.stockpulse.domain.RecommendationSource;
import com.stockpulse.domain.TriggerReason;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Deterministic pricing rules, evaluated top to bottom; the first match wins.
 * <ol>
 *   <li>Out of stock → HOLD (a price change sells nothing; reorder instead).</li>
 *   <li>Demand spike → RAISE 5–15% (target; the reasoning states the final snapped price), scaled by how far velocity is above baseline
 *       (only 5% when there is over a month of cover anyway).</li>
 *   <li>Under 3 days of cover → RAISE 10% to slow the sell-through until stock arrives.</li>
 *   <li>Under 7 days of cover → RAISE 6%.</li>
 *   <li>Below reorder threshold but cover is fine → RAISE 3%.</li>
 *   <li>Over 90 days of cover and stock above 3× threshold → LOWER 10% (slow mover).</li>
 *   <li>Otherwise → HOLD.</li>
 * </ol>
 * Every price goes through {@link PriceGuardrails}.
 */
@Component
public class RulePricingPolicy {

    private final PriceGuardrails guardrails;
    private final StockPulseProperties.Demand demand;

    public RulePricingPolicy(PriceGuardrails guardrails, StockPulseProperties props) {
        this.guardrails = guardrails;
        this.demand = props.demand();
    }

    public PricingRecommendation recommend(ProductContext c) {
        BigDecimal price = c.currentPrice();
        double cover = c.daysOfCover();
        String coverText = Double.isInfinite(cover) ? "no recent demand" : String.format(Locale.ROOT, "%.1f days of cover", cover);

        if (c.stockLevel() <= 0) {
            return hold(price, 0.9, "Out of stock, so a price change would not sell anything. Hold the price and restock first.");
        }

        if (isSpike(c)) {
            double ratio = c.velocityRatio();
            double pct = cover > 30 ? 5 : clamp(Math.round((ratio - 1) * 20), 5, 15);
            return change(price, pct, 0.8, String.format(Locale.ROOT,
                    "Demand spike: %d units sold in the last %d min, velocity %d/day vs %d baseline (%.1f×), %s. "
                            + "Raise {CHANGE} to capture margin and slow the sell-through.",
                    c.unitsSoldInWindow(), c.windowMinutes(), c.demandVelocity(), c.baselineVelocity(), ratio, coverText));
        }

        if (cover < 3) {
            return change(price, 10, 0.8, String.format(Locale.ROOT,
                    "Only %d units left (%s at %d/day). Raise {CHANGE} to stretch stock until a reorder arrives.",
                    c.stockLevel(), coverText, c.demandVelocity()));
        }
        if (cover < 7) {
            return change(price, 6, 0.7, String.format(Locale.ROOT,
                    "Stock of %d covers under a week (%s). Raise {CHANGE} to protect remaining inventory.",
                    c.stockLevel(), coverText));
        }
        if (c.isBelowThreshold()) {
            return change(price, 3, 0.6, String.format(Locale.ROOT,
                    "Stock %d is below the reorder threshold of %d, but demand is modest (%s). A small raise {CHANGE} is enough.",
                    c.stockLevel(), c.reorderThreshold(), coverText));
        }
        if (cover > 90 && c.stockLevel() > 3 * c.reorderThreshold()) {
            return change(price, -10, 0.65, String.format(Locale.ROOT,
                    "Slow mover: %d units in stock and %s. Lower {CHANGE} to free up inventory.",
                    c.stockLevel(), coverText));
        }
        return hold(price, 0.7, String.format(Locale.ROOT,
                "Stock (%d) and demand (%d/day, %s) are balanced. Keep the current price.",
                c.stockLevel(), c.demandVelocity(), coverText));
    }

    /** A spike is either what triggered us, or what the numbers show right now. */
    boolean isSpike(ProductContext c) {
        if (c.trigger() == TriggerReason.DEMAND_SPIKE) return true;
        return c.unitsSoldInWindow() >= demand.spikeMinUnits()
                && c.demandVelocity() >= c.baselineVelocity() * demand.spikeRatio();
    }

    private PricingRecommendation change(BigDecimal current, double pct, double confidence, String reasoning) {
        BigDecimal next = guardrails.apply(current, pct);
        PricingAction action = next.compareTo(current) > 0 ? PricingAction.RAISE
                : next.compareTo(current) < 0 ? PricingAction.LOWER : PricingAction.HOLD;
        // Describe the change actually applied (after .99 snapping and guardrails), not the target %.
        double actualPct = next.subtract(current).multiply(BigDecimal.valueOf(100))
                .divide(current, 1, java.math.RoundingMode.HALF_UP).doubleValue();
        String change = String.format(Locale.ROOT, "to $%s (%+.1f%%)", next.toPlainString(), actualPct);
        return new PricingRecommendation(action, next, confidence, reasoning.replace("{CHANGE}", change),
                RecommendationSource.RULE_BASED, null);
    }

    private PricingRecommendation hold(BigDecimal current, double confidence, String reasoning) {
        return new PricingRecommendation(PricingAction.HOLD, current, confidence, reasoning, RecommendationSource.RULE_BASED, null);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
