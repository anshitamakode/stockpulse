package com.stockpulse.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockpulse.ai.AiResponseParser;
import com.stockpulse.ai.CommercePromptBuilder;
import com.stockpulse.ai.LLMGateway.LLMException;
import com.stockpulse.ai.LlmClient;
import com.stockpulse.ai.LlmResponse;
import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.PricingAction;
import com.stockpulse.domain.RecommendationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * AI-powered strategy: the LLM recommends, the code decides whether to believe it.
 * <ol>
 *   <li>Compute the rule answer first. It is NOT shown to the model (so the model reasons on its
 *       own); it is the fallback and is stored next to the AI answer for comparison.</li>
 *   <li>Call the model once for both pricing and reorder (the gateway retries and tries backup models).</li>
 *   <li>Parse defensively, then validate each half independently against hard limits.</li>
 *   <li>Any failure (transport, JSON, validation) replaces only the affected half with the rule
 *       answer, marked {@code AI_FALLBACK} with the reason recorded for the merchandiser.</li>
 * </ol>
 * This method never throws for model misbehaviour.
 */
@Component
public class AiCommerceStrategy implements CommerceStrategy {

    public static final String NAME = "ai";
    private static final Logger log = LoggerFactory.getLogger(AiCommerceStrategy.class);
    private static final int MAX_REASONING = 600;

    private final LlmClient llm;
    private final CommercePromptBuilder prompts;
    private final AiResponseParser parser;
    private final PriceGuardrails guardrails;
    private final RuleBasedCommerceStrategy rules;
    private final int maxQuantity;

    public AiCommerceStrategy(LlmClient llm, CommercePromptBuilder prompts, AiResponseParser parser,
                              PriceGuardrails guardrails, RuleBasedCommerceStrategy rules,
                              StockPulseProperties props) {
        this.llm = llm;
        this.prompts = prompts;
        this.parser = parser;
        this.guardrails = guardrails;
        this.rules = rules;
        this.maxQuantity = props.reorder().maxQuantity();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CommerceRecommendation recommend(ProductContext context) {
        CommerceRecommendation baseline = rules.recommend(context);

        JsonNode root;
        LlmResponse response;
        try {
            response = llm.complete(prompts.systemPrompt(), prompts.userPrompt(context));
            root = parser.parse(response.text());
        } catch (LLMException e) {
            return fallbackBoth(context, baseline, "LLM_ERROR: " + e.getMessage());
        } catch (AiResponseParser.InvalidAiResponseException e) {
            return fallbackBoth(context, baseline, "INVALID_JSON: " + e.getMessage());
        } catch (RuntimeException e) {
            return fallbackBoth(context, baseline, "UNEXPECTED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        PricingRecommendation pricing;
        try {
            pricing = validatePricing(context, root.path("pricing"));
        } catch (ValidationException e) {
            log.warn("AI pricing for {} rejected: {}", context.productId(), e.getMessage());
            pricing = baseline.pricing().withFallback("VALIDATION: " + e.getMessage());
        }

        ReorderRecommendation reorder;
        try {
            reorder = validateReorder(root.path("reorder"));
        } catch (ValidationException e) {
            log.warn("AI reorder for {} rejected: {}", context.productId(), e.getMessage());
            reorder = baseline.reorder().withFallback("VALIDATION: " + e.getMessage());
        }
        return new CommerceRecommendation(pricing, reorder, response.model(), baseline);
    }

    PricingRecommendation validatePricing(ProductContext c, JsonNode node) {
        if (!node.isObject()) throw new ValidationException("pricing object missing");

        PricingAction action;
        try {
            action = PricingAction.valueOf(node.path("action").asText("").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("unknown action '" + node.path("action").asText() + "'");
        }

        JsonNode priceNode = node.path("recommendedPrice");
        if (!priceNode.isNumber() && !(priceNode.isTextual() && priceNode.asText().matches("\\d+(\\.\\d+)?"))) {
            throw new ValidationException("recommendedPrice is not a number");
        }
        BigDecimal price = new BigDecimal(priceNode.asText()).setScale(2, RoundingMode.HALF_UP);
        String violation = guardrails.violation(c.currentPrice(), action, price);
        if (violation != null) throw new ValidationException(violation);
        if (action == PricingAction.HOLD) price = c.currentPrice();

        return new PricingRecommendation(action, price, confidence(node), reasoning(node),
                RecommendationSource.AI, null);
    }

    ReorderRecommendation validateReorder(JsonNode node) {
        if (!node.isObject()) throw new ValidationException("reorder object missing");
        JsonNode q = node.path("quantity");
        if (!q.isNumber() || q.asDouble() != Math.floor(q.asDouble())) {
            throw new ValidationException("quantity is not a whole number");
        }
        long qty = q.asLong();
        if (qty < 0 || qty > maxQuantity) {
            throw new ValidationException("quantity " + qty + " outside 0–" + maxQuantity);
        }
        return new ReorderRecommendation((int) qty, confidence(node), reasoning(node), RecommendationSource.AI, null);
    }

    private static double confidence(JsonNode node) {
        JsonNode c = node.path("confidence");
        if (!c.isNumber()) throw new ValidationException("confidence is not a number");
        double v = c.asDouble();
        if (!Double.isFinite(v) || v < 0 || v > 1) throw new ValidationException("confidence " + v + " outside 0–1");
        return v;
    }

    private static String reasoning(JsonNode node) {
        String r = node.path("reasoning").asText("").trim();
        if (r.isEmpty()) throw new ValidationException("reasoning is blank");
        return r.length() > MAX_REASONING ? r.substring(0, MAX_REASONING - 1) + "…" : r;
    }

    private CommerceRecommendation fallbackBoth(ProductContext c, CommerceRecommendation baseline, String reason) {
        String trimmed = reason.length() > 450 ? reason.substring(0, 449) + "…" : reason;
        log.warn("AI strategy fell back to rules for {}: {}", c.productId(), trimmed);
        return new CommerceRecommendation(baseline.pricing().withFallback(trimmed), baseline.reorder().withFallback(trimmed));
    }

    static class ValidationException extends RuntimeException {
        ValidationException(String message) { super(message); }
    }
}
