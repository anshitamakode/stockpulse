package com.stockpulse.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.engine.PriceGuardrails;
import com.stockpulse.engine.ProductContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the system and user prompts. The user prompt is a JSON fact sheet: the numbers, the
 * replenishment policy, and the hard limits. The rule engine's answer is deliberately NOT
 * included — shown a reference answer, models tend to copy it, and then the AI adds nothing.
 * The rules stay as the safety net (validation + fallback) and as a comparison stored alongside.
 */
@Component
public class CommercePromptBuilder {

    static final String SYSTEM_PROMPT = """
            You are a merchandising analyst for an online store. For one product you recommend one price
            action and one reorder quantity. A human approves every recommendation before it applies.

            How to think:
            - Weigh the trade-off explicitly: a higher price protects scarce stock and margin but loses
              sales; a lower price clears slow stock but costs margin.
            - Use daysOfCover and the trend in recentHistory. A short burst of sales is not the same as
              a permanent increase in demand; say which you think this is.
            - Size the reorder so stock lasts through the supplier lead time and the target cover period,
              keeping roughly safetyStockUnits in reserve. Round to the case pack.

            Rules:
            - Use ONLY the facts provided. Do not invent competitor prices, costs, seasons or events.
            - recommendedPrice MUST be within guardrails.minPrice and guardrails.maxPrice (inclusive).
              Answers outside the guardrails are discarded automatically.
            - action HOLD means recommendedPrice equals currentPrice exactly.
              RAISE means strictly higher, LOWER strictly lower.
            - Prefer retail price endings like .99 when they fit inside the guardrails.
            - quantity is a whole number of units, 0 if no reorder is needed, never above guardrails.maxQuantity.
            - confidence is a number from 0 to 1 reflecting how clear-cut the situation is.
            - reasoning: at most 2 short sentences each, plain English a merchandiser can act on,
              citing the numbers that drove the decision.

            Respond with ONLY this JSON object, no markdown, no extra keys:
            {"pricing":{"action":"RAISE|LOWER|HOLD","recommendedPrice":0.00,"confidence":0.0,"reasoning":"..."},
             "reorder":{"quantity":0,"confidence":0.0,"reasoning":"..."}}
            """;

    private final ObjectMapper mapper;
    private final PriceGuardrails guardrails;
    private final StockPulseProperties.Reorder reorder;

    public CommercePromptBuilder(ObjectMapper mapper, PriceGuardrails guardrails, StockPulseProperties props) {
        this.mapper = mapper;
        this.guardrails = guardrails;
        this.reorder = props.reorder();
    }

    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    public String userPrompt(ProductContext c) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("situation", situation(c));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("id", c.productId());
        product.put("name", c.name());
        product.put("category", c.category().name());
        product.put("currentPrice", c.currentPrice());
        product.put("stockLevel", c.stockLevel());
        product.put("reorderThreshold", c.reorderThreshold());
        product.put("demandVelocityPerDay", c.demandVelocity());
        product.put("baselineVelocityPerDay", c.baselineVelocity());
        product.put("velocityVsBaseline", String.format(Locale.ROOT, "%.2fx", c.velocityRatio()));
        product.put("unitsSoldLastWindow", c.unitsSoldInWindow());
        product.put("windowMinutes", c.windowMinutes());
        double cover = c.daysOfCover();
        product.put("daysOfCover", Double.isInfinite(cover) ? "no demand" : String.format(Locale.ROOT, "%.1f", cover));
        if (!c.attributes().isEmpty()) product.put("attributes", c.attributes());
        facts.put("product", product);
        facts.put("recentHistory", c.recentHistory());

        facts.put("replenishmentPolicy", Map.of(
                "supplierLeadTimeDays", reorder.leadTimeDays(),
                "targetCoverDaysAfterArrival", reorder.coverDays(),
                "safetyStockUnits", c.reorderThreshold(),
                "casePack", reorder.casePack()));

        facts.put("guardrails", Map.of(
                "minPrice", guardrails.lowerBound(c.currentPrice()),
                "maxPrice", guardrails.upperBound(c.currentPrice()),
                "maxQuantity", reorder.maxQuantity()));

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise prompt facts", e);
        }
    }

    private static String situation(ProductContext c) {
        return switch (c.trigger()) {
            case LOW_STOCK -> "LOW_STOCK: stock fell below the reorder threshold. Balance protecting remaining "
                    + "units against losing sales, and size a reorder that lands before stock runs out.";
            case DEMAND_SPIKE -> "DEMAND_SPIKE: sales jumped well above baseline. Decide whether a raise captures "
                    + "margin without killing momentum, and size the reorder without treating a short spike as permanent demand.";
            case MANUAL -> "MANUAL: a merchandiser asked for a review. Recommend HOLD and quantity 0 if nothing needs to change.";
        };
    }
}
