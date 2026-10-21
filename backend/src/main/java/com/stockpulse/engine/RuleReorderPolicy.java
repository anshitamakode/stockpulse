package com.stockpulse.engine;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.RecommendationSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Lightweight reorder baseline:
 * <pre>
 *   needed  = stock &lt; threshold  OR  daysOfCover &lt; leadTime
 *   planned = velocity used for planning (during a spike only half the excess counts,
 *             so a viral hour doesn't become a month of overstock)
 *   target  = ceil(planned × (leadTime + coverDays)) + threshold   // threshold = safety stock
 *   qty     = roundUp(target − stock, casePack), capped at maxQuantity
 * </pre>
 * With no demand history at all (velocity 0, stock 0) it orders 2× threshold at low confidence.
 */
@Component
public class RuleReorderPolicy {

    private final StockPulseProperties.Reorder cfg;
    private final RulePricingPolicy pricing;

    public RuleReorderPolicy(StockPulseProperties props, RulePricingPolicy pricing) {
        this.cfg = props.reorder();
        this.pricing = pricing;
    }

    public ReorderRecommendation recommend(ProductContext c) {
        boolean needed = c.isBelowThreshold() || c.daysOfCover() < cfg.leadTimeDays();
        if (!needed) {
            return result(0, 0.8, String.format(Locale.ROOT,
                    "No reorder needed: %d in stock (threshold %d) covers the %d-day lead time.",
                    c.stockLevel(), c.reorderThreshold(), cfg.leadTimeDays()));
        }

        if (c.demandVelocity() <= 0) {
            int qty = roundUp(Math.max(2 * c.reorderThreshold() - c.stockLevel(), cfg.casePack()));
            return result(qty, 0.5, String.format(Locale.ROOT,
                    "No recent sales to size the order from. Restock %d units (2× the threshold of %d) and watch demand.",
                    qty, c.reorderThreshold()));
        }

        boolean spike = pricing.isSpike(c);
        double planned = spike
                ? c.baselineVelocity() + 0.5 * (c.demandVelocity() - c.baselineVelocity())
                : c.demandVelocity();
        int horizon = cfg.leadTimeDays() + cfg.coverDays();
        long target = (long) Math.ceil(planned * horizon) + c.reorderThreshold();
        int qty = roundUp((int) Math.max(target - c.stockLevel(), 0));

        String velocityText = spike
                ? String.format(Locale.ROOT, "planning on %.1f/day (half of the spike above the %d/day baseline)", planned, c.baselineVelocity())
                : String.format(Locale.ROOT, "at %d/day", c.demandVelocity());
        return result(qty, spike ? 0.65 : 0.75, String.format(Locale.ROOT,
                "Order %d units: %d days of demand %s plus %d safety stock, minus %d on hand, rounded to packs of %d.",
                qty, horizon, velocityText, c.reorderThreshold(), c.stockLevel(), cfg.casePack()));
    }

    private int roundUp(int qty) {
        if (qty <= 0) return 0;
        int pack = Math.max(cfg.casePack(), 1);
        int rounded = ((qty + pack - 1) / pack) * pack;
        return Math.min(rounded, cfg.maxQuantity());
    }

    private static ReorderRecommendation result(int qty, double confidence, String reasoning) {
        return new ReorderRecommendation(qty, confidence, reasoning, RecommendationSource.RULE_BASED, null);
    }
}
