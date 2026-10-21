package com.stockpulse.service;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.TriggerReason;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Deterministic signal detection — no AI here. Decides <em>whether</em> to ask for advice;
 * the strategy decides <em>what</em> to advise.
 * <ul>
 *   <li>DEMAND_SPIKE: at least {@code spikeMinUnits} sold inside the window AND current velocity
 *       ≥ baseline × {@code spikeRatio}. Checked first because it is the stronger signal.</li>
 *   <li>LOW_STOCK: stock is below the reorder threshold.</li>
 * </ul>
 * Firing repeatedly is harmless: {@link SuggestionService#open} de-duplicates against open suggestions.
 */
@Component
public class TriggerDetector {

    private final StockPulseProperties.Demand demand;

    public TriggerDetector(StockPulseProperties props) {
        this.demand = props.demand();
    }

    public Optional<TriggerReason> detect(Product product, long unitsSoldInWindow) {
        boolean spike = unitsSoldInWindow >= demand.spikeMinUnits()
                && product.getDemandVelocity() >= product.getBaselineVelocity() * demand.spikeRatio();
        if (spike) return Optional.of(TriggerReason.DEMAND_SPIKE);
        if (product.isBelowThreshold()) return Optional.of(TriggerReason.LOW_STOCK);
        return Optional.empty();
    }
}
