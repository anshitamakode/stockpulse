package com.stockpulse.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.Category;
import com.stockpulse.domain.TriggerReason;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Builders shared by engine tests; values mirror application.properties defaults. */
final class Fixtures {

    private Fixtures() {}

    static StockPulseProperties props() {
        return new StockPulseProperties("ruleBased",
                new StockPulseProperties.Demand(10, 5, 1.3),
                new StockPulseProperties.Pricing(20, 1.0),
                new StockPulseProperties.Reorder(7, 14, 5, 2000),
                new StockPulseProperties.Recovery(120, 600));
    }

    static PriceGuardrails guardrails() {
        return new PriceGuardrails(props());
    }

    static RulePricingPolicy pricing() {
        return new RulePricingPolicy(guardrails(), props());
    }

    static RuleReorderPolicy reorder() {
        return new RuleReorderPolicy(props(), pricing());
    }

    static RuleBasedCommerceStrategy rules() {
        return new RuleBasedCommerceStrategy(pricing(), reorder());
    }

    static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    /** price, stock, threshold, velocity, baseline, units sold in window, trigger */
    static ProductContext ctx(String price, int stock, int threshold, int velocity, int baseline,
                              long unitsInWindow, TriggerReason trigger) {
        return new ProductContext("PRD-T", "SKU-T", "Test Product", Category.APPAREL, new BigDecimal(price),
                stock, threshold, velocity, baseline, unitsInWindow, 10, trigger, Map.of(), List.of());
    }
}
