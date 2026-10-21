package com.stockpulse.engine;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.PricingAction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Hard limits every price recommendation must respect, whoever produced it. Rules use
 * {@link #apply} to build a compliant price; the AI path uses {@link #violation} to reject
 * output that breaks them (and then falls back to rules).
 */
@Component
public class PriceGuardrails {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TOLERANCE = new BigDecimal("0.005");

    private final BigDecimal maxChangePct;
    private final BigDecimal minPrice;

    public PriceGuardrails(StockPulseProperties props) {
        this.maxChangePct = BigDecimal.valueOf(props.pricing().maxChangePct());
        this.minPrice = BigDecimal.valueOf(props.pricing().minPrice()).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal lowerBound(BigDecimal current) {
        BigDecimal lower = current.multiply(BigDecimal.ONE.subtract(maxChangePct.divide(HUNDRED)))
                .setScale(2, RoundingMode.CEILING);
        return lower.max(minPrice);
    }

    public BigDecimal upperBound(BigDecimal current) {
        return current.multiply(BigDecimal.ONE.add(maxChangePct.divide(HUNDRED)))
                .setScale(2, RoundingMode.FLOOR);
    }

    public BigDecimal maxChangePct() {
        return maxChangePct;
    }

    /**
     * Applies a percentage change, snaps to a retail .99 ending when that keeps the direction,
     * and clamps into the allowed band.
     */
    public BigDecimal apply(BigDecimal current, double changePct) {
        if (changePct == 0) return current.setScale(2, RoundingMode.HALF_UP);
        BigDecimal raw = current.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(changePct).divide(HUNDRED)));
        BigDecimal snapped = snapToNinetyNine(raw);
        boolean directionKept = changePct > 0 ? snapped.compareTo(current) > 0 : snapped.compareTo(current) < 0;
        BigDecimal candidate = directionKept ? snapped : raw.setScale(2, RoundingMode.HALF_UP);
        return clamp(current, candidate);
    }

    public BigDecimal clamp(BigDecimal current, BigDecimal price) {
        BigDecimal lo = lowerBound(current);
        BigDecimal hi = upperBound(current);
        if (price.compareTo(lo) < 0) return lo;
        if (price.compareTo(hi) > 0) return hi;
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    /** @return a human-readable violation, or {@code null} when the price is acceptable. */
    public String violation(BigDecimal current, PricingAction action, BigDecimal price) {
        if (price == null) return "recommendedPrice missing";
        if (price.signum() <= 0) return "recommendedPrice must be positive";
        if (price.compareTo(lowerBound(current)) < 0 || price.compareTo(upperBound(current)) > 0) {
            return "recommendedPrice " + price + " outside guardrail band "
                    + lowerBound(current) + "–" + upperBound(current);
        }
        int cmp = price.compareTo(current);
        boolean same = price.subtract(current).abs().compareTo(TOLERANCE) <= 0;
        return switch (action) {
            case HOLD -> same ? null : "action HOLD but price changed to " + price;
            case RAISE -> (!same && cmp > 0) ? null : "action RAISE but price " + price + " is not above " + current;
            case LOWER -> (!same && cmp < 0) ? null : "action LOWER but price " + price + " is not below " + current;
        };
    }

    static BigDecimal snapToNinetyNine(BigDecimal price) {
        BigDecimal floor = price.setScale(0, RoundingMode.FLOOR);
        BigDecimal up = floor.add(new BigDecimal("0.99"));
        BigDecimal down = floor.subtract(BigDecimal.ONE).add(new BigDecimal("0.99"));
        if (down.signum() <= 0) return up;
        return price.subtract(down).abs().compareTo(up.subtract(price).abs()) <= 0 ? down : up;
    }
}
