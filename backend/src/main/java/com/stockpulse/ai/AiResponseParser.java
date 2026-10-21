package com.stockpulse.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Turns a model reply into a JSON tree. Models wrap JSON in markdown fences, prepend
 * chatter, or (reasoning models) emit {@code <think>} blocks — all of that is stripped
 * before parsing. Semantic validation happens in the strategy, not here.
 */
@Component
public class AiResponseParser {

    private static final Pattern THINK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern FENCE = Pattern.compile("(?i)```(?:json)?");

    private final ObjectMapper mapper;

    public AiResponseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** @throws InvalidAiResponseException if no JSON object can be extracted */
    public JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) throw new InvalidAiResponseException("empty reply");
        String cleaned = FENCE.matcher(THINK.matcher(raw).replaceAll("")).replaceAll("").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) throw new InvalidAiResponseException("no JSON object in reply");
        try {
            JsonNode node = mapper.readTree(cleaned.substring(start, end + 1));
            if (!node.isObject()) throw new InvalidAiResponseException("reply is not a JSON object");
            return node;
        } catch (JsonProcessingException e) {
            throw new InvalidAiResponseException("malformed JSON: " + e.getOriginalMessage());
        }
    }

    public static class InvalidAiResponseException extends RuntimeException {
        public InvalidAiResponseException(String message) { super(message); }
    }
}
