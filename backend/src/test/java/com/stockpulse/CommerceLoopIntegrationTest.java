package com.stockpulse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end over HTTP with the real async loop: order → signal → PROCESSING → PENDING → decision.
 * Runs with the rule strategy (and with AI + no API key to prove the fallback path).
 */
@SpringBootTest(properties = {"stockpulse.strategy=ruleBased", "llm.api-key="})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CommerceLoopIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void lowStockOrderProducesPendingSuggestionsAndAcceptUpdatesPrice() throws Exception {
        JsonNode order = postJson("/products/PRD-003/orders", "{\"quantity\":1}", status().isCreated());
        assertThat(order.path("triggerDetected").asText()).isEqualTo("LOW_STOCK");
        assertThat(order.path("opened")).hasSize(2);
        assertThat(order.path("product").path("status").asText()).isEqualTo("PRICE_REVIEW_PENDING");

        List<JsonNode> pending = awaitSuggestions("PENDING", l -> l.size() == 2);
        JsonNode pricing = byKind(pending, "PRICING");
        JsonNode reorder = byKind(pending, "REORDER");
        assertThat(pricing.path("source").asText()).isEqualTo("RULE_BASED");
        assertThat(pricing.path("autoTriggered").asBoolean()).isTrue();
        assertThat(pricing.path("reasoning").asText()).isNotBlank();
        assertThat(reorder.path("recommendedQuantity").asInt()).isPositive();

        String newPrice = pricing.path("recommendedPrice").asText();
        patchJson("/suggestions/pricing/" + pricing.path("id").asText(), "{\"status\":\"ACCEPTED\"}", status().isOk());

        JsonNode product = getJson("/products/PRD-003");
        assertThat(product.path("currentPrice").decimalValue()).isEqualByComparingTo(newPrice);
        assertThat(product.path("status").asText()).isEqualTo("ACTIVE");

        // deciding twice is a conflict; the state machine says ACCEPTED is terminal
        patchJson("/suggestions/pricing/" + pricing.path("id").asText(), "{\"status\":\"REJECTED\"}", status().isConflict());
    }

    @Test
    void repeatedLowStockSignalsDoNotDuplicateSuggestions() throws Exception {
        postJson("/products/PRD-003/orders", "{\"quantity\":1}", status().isCreated());
        JsonNode second = postJson("/products/PRD-003/orders", "{\"quantity\":1}", status().isCreated());
        assertThat(second.path("opened")).isEmpty();
        awaitSuggestions("PENDING", l -> l.size() == 2);
    }

    @Test
    void demandSpikeSupersedesLowStockSuggestions() throws Exception {
        // Hoodie: 11 in stock, threshold 12 → first order is a low-stock signal
        JsonNode first = postJson("/products/PRD-008/orders", "{\"quantity\":1}", status().isCreated());
        assertThat(first.path("triggerDetected").asText()).isEqualTo("LOW_STOCK");
        awaitSuggestions("PENDING", l -> l.size() == 2);

        JsonNode last = null;
        for (int i = 0; i < 4; i++) {
            last = postJson("/products/PRD-008/orders", "{\"quantity\":1}", status().isCreated());
        }
        assertThat(last.path("triggerDetected").asText()).isEqualTo("DEMAND_SPIKE");
        assertThat(last.path("opened")).hasSize(2);

        List<JsonNode> pending = awaitSuggestions("PENDING",
                l -> l.size() == 2 && l.stream().allMatch(s -> s.path("triggerReason").asText().equals("DEMAND_SPIKE")));
        assertThat(byKind(pending, "PRICING").path("action").asText()).isEqualTo("RAISE");
        assertThat(awaitSuggestions("SUPERSEDED", l -> l.size() == 2)).hasSize(2);
    }

    @Test
    void aiStrategyWithoutKeyFallsBackToRulesAndSaysWhy() throws Exception {
        patchJson("/strategy", "{\"strategy\":\"ai\"}", status().isOk());
        postJson("/products/PRD-003/orders", "{\"quantity\":1}", status().isCreated());

        List<JsonNode> pending = awaitSuggestions("PENDING", l -> l.size() == 2);
        for (JsonNode s : pending) {
            assertThat(s.path("source").asText()).isEqualTo("AI_FALLBACK");
            assertThat(s.path("fallbackReason").asText()).startsWith("LLM_ERROR");
        }
    }

    @Test
    void manualRequestThenConflictWhileOpen() throws Exception {
        // healthy product: pricing HOLD stays PENDING, reorder qty 0 becomes SKIPPED
        JsonNode opened = postJson("/products/PRD-001/suggestions", "", status().isAccepted());
        assertThat(opened).hasSize(2);
        assertThat(opened.get(0).path("status").asText()).isEqualTo("PROCESSING");
        awaitSuggestions("PENDING", l -> l.size() == 1);
        awaitSuggestions("SKIPPED", l -> l.size() == 1);

        // low product: both kinds end PENDING, so asking again has nothing new to open
        postJson("/products/PRD-003/suggestions", "", status().isAccepted());
        awaitSuggestions("PENDING", l -> l.size() == 3);
        postJson("/products/PRD-003/suggestions", "", status().isConflict());
    }

    @Test
    void errorsAreProblemDetails() throws Exception {
        JsonNode notFound = postJson("/products/NOPE/orders", "{\"quantity\":1}", status().isNotFound());
        assertThat(notFound.path("title").asText()).isEqualTo("Not found");

        JsonNode invalid = postJson("/products/PRD-001/orders", "{\"quantity\":0}", status().isBadRequest());
        assertThat(invalid.path("errors").has("quantity")).isTrue();

        postJson("/products/PRD-006/orders", "{\"quantity\":1}", status().isConflict());   // out of stock
        patchJson("/strategy", "{\"strategy\":\"magic\"}", status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private List<JsonNode> awaitSuggestions(String status, Predicate<List<JsonNode>> done) throws Exception {
        List<JsonNode> last = List.of();
        for (int i = 0; i < 50; i++) {
            last = new ArrayList<>();
            getJson("/suggestions?status=" + status).forEach(last::add);
            if (done.test(last)) return last;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + status + " suggestions; last saw " + last);
    }

    private static JsonNode byKind(List<JsonNode> list, String kind) {
        return list.stream().filter(s -> s.path("kind").asText().equals(kind)).findFirst()
                .orElseThrow(() -> new AssertionError("No " + kind + " in " + list));
    }

    private JsonNode getJson(String url) throws Exception {
        return read(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode postJson(String url, String body, ResultMatcher expected) throws Exception {
        return read(mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(expected).andReturn().getResponse().getContentAsString());
    }

    private JsonNode patchJson(String url, String body, ResultMatcher expected) throws Exception {
        return read(mvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(expected).andReturn().getResponse().getContentAsString());
    }

    private JsonNode read(String s) throws Exception {
        return s.isBlank() ? json.nullNode() : json.readTree(s);
    }
}
