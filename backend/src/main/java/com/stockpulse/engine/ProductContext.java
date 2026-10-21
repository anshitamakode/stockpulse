package com.stockpulse.engine;

import com.stockpulse.domain.Category;
import com.stockpulse.domain.TriggerReason;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of everything a strategy may look at. Strategies never touch entities or
 * repositories, which keeps them pure and unit-testable.
 *
 * @param unitsSoldInWindow units sold during the last {@code windowMinutes}
 * @param recentHistory     newest first; small, for trend context in the prompt
 */
public record ProductContext(
        String productId,
        String sku,
        String name,
        Category category,
        BigDecimal currentPrice,
        int stockLevel,
        int reorderThreshold,
        int demandVelocity,
        int baselineVelocity,
        long unitsSoldInWindow,
        int windowMinutes,
        TriggerReason trigger,
        Map<String, String> attributes,
        List<HistoryPoint> recentHistory) {

    public ProductContext {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        recentHistory = recentHistory == null ? List.of() : List.copyOf(recentHistory);
    }

    public record HistoryPoint(String reason, int quantityDelta, int stockLevel, String capturedAt) {}

    /** Days until stock runs out at current velocity; {@code Double.POSITIVE_INFINITY} with no demand. */
    public double daysOfCover() {
        if (demandVelocity <= 0) return stockLevel <= 0 ? 0 : Double.POSITIVE_INFINITY;
        return (double) stockLevel / demandVelocity;
    }

    public double velocityRatio() {
        return (double) demandVelocity / Math.max(baselineVelocity, 1);
    }

    public boolean isBelowThreshold() {
        return stockLevel < reorderThreshold;
    }
}
