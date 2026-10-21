package com.stockpulse.engine;

import com.stockpulse.ai.AiResponseParser;
import com.stockpulse.ai.CommercePromptBuilder;
import com.stockpulse.ai.LLMGateway.LLMException;
import com.stockpulse.ai.LlmClient;
import com.stockpulse.ai.LlmResponse;
import com.stockpulse.domain.PricingAction;
import com.stockpulse.domain.RecommendationSource;
import com.stockpulse.domain.TriggerReason;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static com.stockpulse.engine.Fixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;

class AiCommerceStrategyTest {

    // T-shirt: 7 left, guardrail band 19.99–29.98
    private final ProductContext context = ctx("24.99", 7, 15, 13, 12, 1, TriggerReason.LOW_STOCK);
    private final AtomicReference<String> lastUserPrompt = new AtomicReference<>();

    private AiCommerceStrategy strategyReturning(String reply) {
        return strategy((system, user) -> {
            lastUserPrompt.set(user);
            return new LlmResponse(reply, "test-model", 5, 1);
        });
    }

    private AiCommerceStrategy strategy(LlmClient client) {
        var mapper = Fixtures.mapper();
        return new AiCommerceStrategy(client,
                new CommercePromptBuilder(mapper, Fixtures.guardrails(), Fixtures.props()),
                new AiResponseParser(mapper), Fixtures.guardrails(), Fixtures.rules(), Fixtures.props());
    }

    private static String reply(String action, String price, String priceConf, String qty, String qtyConf) {
        return """
                {"pricing":{"action":"%s","recommendedPrice":%s,"confidence":%s,"reasoning":"Only 7 left at 13/day."},
                 "reorder":{"quantity":%s,"confidence":%s,"reasoning":"Cover the 7-day lead time."}}
                """.formatted(action, price, priceConf, qty, qtyConf);
    }

    @Test
    void validReplyIsUsedAsIs() {
        CommerceRecommendation r = strategyReturning(reply("RAISE", "27.99", "0.82", "250", "0.7")).recommend(context);

        assertThat(r.pricing().source()).isEqualTo(RecommendationSource.AI);
        assertThat(r.pricing().action()).isEqualTo(PricingAction.RAISE);
        assertThat(r.pricing().recommendedPrice()).isEqualByComparingTo("27.99");
        assertThat(r.pricing().confidence()).isEqualTo(0.82);
        assertThat(r.reorder().source()).isEqualTo(RecommendationSource.AI);
        assertThat(r.reorder().quantity()).isEqualTo(250);
    }

    @Test
    void promptCarriesFactsPolicyAndGuardrailsButNotTheRuleAnswer() {
        strategyReturning(reply("HOLD", "24.99", "0.5", "0", "0.5")).recommend(context);
        String prompt = lastUserPrompt.get();
        assertThat(prompt)
                .contains("\"stockLevel\" : 7")
                .contains("\"maxPrice\" : 29.98")
                .contains("supplierLeadTimeDays")
                .contains("LOW_STOCK");
        // the rule engine's answer is withheld so the model reasons for itself
        assertThat(prompt.contains("ruleEngineBaseline")).isFalse();
        assertThat(prompt.contains("285")).isFalse();
    }

    @Test
    void aiAnswerKeepsModelAndRuleAnswerForComparison() {
        CommerceRecommendation r = strategyReturning(reply("RAISE", "27.99", "0.82", "250", "0.7")).recommend(context);
        assertThat(r.aiModel()).isEqualTo("test-model");
        assertThat(r.ruleBaseline().reorder().quantity()).isEqualTo(285);
        assertThat(r.ruleBaseline().pricing().recommendedPrice()).isEqualByComparingTo("26.99");
    }

    @Test
    void markdownFencesAndThinkBlocksAreStripped() {
        String wrapped = "<think>let me reason</think>Sure!\n```json\n" + reply("RAISE", "26.99", "0.8", "200", "0.6") + "\n```";
        CommerceRecommendation r = strategyReturning(wrapped).recommend(context);
        assertThat(r.pricing().source()).isEqualTo(RecommendationSource.AI);
        assertThat(r.reorder().source()).isEqualTo(RecommendationSource.AI);
    }

    @Test
    void invalidJsonFallsBackToRulesForBoth() {
        CommerceRecommendation r = strategyReturning("I think you should raise the price a bit.").recommend(context);
        assertThat(r.pricing().source()).isEqualTo(RecommendationSource.AI_FALLBACK);
        assertThat(r.reorder().source()).isEqualTo(RecommendationSource.AI_FALLBACK);
        assertThat(r.pricing().fallbackReason()).startsWith("INVALID_JSON");
        assertThat(r.reorder().quantity()).isEqualTo(285); // rule answer
    }

    @Test
    void priceOutsideGuardrailsFallsBackOnlyForPricing() {
        CommerceRecommendation r = strategyReturning(reply("RAISE", "39.99", "0.9", "250", "0.7")).recommend(context);
        assertThat(r.pricing().source()).isEqualTo(RecommendationSource.AI_FALLBACK);
        assertThat(r.pricing().fallbackReason()).contains("outside guardrail");
        assertThat(r.reorder().source()).isEqualTo(RecommendationSource.AI);
    }

    @Test
    void actionContradictingPriceIsRejected() {
        CommerceRecommendation r = strategyReturning(reply("LOWER", "26.99", "0.9", "250", "0.7")).recommend(context);
        assertThat(r.pricing().source()).isEqualTo(RecommendationSource.AI_FALLBACK);
        assertThat(r.pricing().fallbackReason()).contains("not below");
    }

    @Test
    void confidenceAboveOneIsRejected() {
        CommerceRecommendation r = strategyReturning(reply("RAISE", "26.99", "1.5", "250", "0.7")).recommend(context);
        assertThat(r.pricing().source()).isEqualTo(RecommendationSource.AI_FALLBACK);
        assertThat(r.pricing().fallbackReason()).contains("confidence");
    }

    @Test
    void negativeOrFractionalQuantityIsRejected() {
        assertThat(strategyReturning(reply("RAISE", "26.99", "0.8", "-5", "0.7")).recommend(context).reorder().source())
                .isEqualTo(RecommendationSource.AI_FALLBACK);
        assertThat(strategyReturning(reply("RAISE", "26.99", "0.8", "12.5", "0.7")).recommend(context).reorder().source())
                .isEqualTo(RecommendationSource.AI_FALLBACK);
    }

    @Test
    void transportFailureOrTimeoutFallsBack() {
        CommerceRecommendation r = strategy((s, u) -> { throw new LLMException("groq request failed: Read timed out"); })
                .recommend(context);
        assertThat(r.pricing().source()).isEqualTo(RecommendationSource.AI_FALLBACK);
        assertThat(r.pricing().fallbackReason()).startsWith("LLM_ERROR").contains("timed out");
        assertThat(r.reorder().source()).isEqualTo(RecommendationSource.AI_FALLBACK);
    }

    @Test
    void holdAlwaysKeepsExactCurrentPrice() {
        CommerceRecommendation r = strategyReturning(reply("hold", "24.99", "0.6", "0", "0.6")).recommend(context);
        assertThat(r.pricing().action()).isEqualTo(PricingAction.HOLD);
        assertThat(r.pricing().recommendedPrice()).isEqualByComparingTo("24.99");
    }
}
