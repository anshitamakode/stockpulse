package com.stockpulse.engine;

/**
 * One way of turning a product situation into a pricing and a reorder recommendation.
 * Implementations must not throw for ordinary bad input; the AI strategy falls back to rules
 * internally so callers always get a usable answer.
 */
public interface CommerceStrategy {

    CommerceRecommendation recommend(ProductContext context);

    /** Stable key used by the runtime switch, e.g. {@code "ai"} or {@code "ruleBased"}. */
    String name();
}
