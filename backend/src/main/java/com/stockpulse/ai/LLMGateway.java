package com.stockpulse.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Provider-specific HTTP for Gemini, Groq, Ollama and any OpenAI-compatible endpoint (LiteLLM).
 * Returns raw text — parsing, validation and rule fallback live in {@code AiCommerceStrategy}.
 *
 * <pre>
 *   llm.provider        = gemini | groq | ollama | openai
 *   llm.api-key         = ${LLM_API_KEY}           (not needed for ollama)
 *   llm.model           = gemini-3.5-flash-lite    primary model
 *   llm.fallback-models = gemini-3.1-flash-lite    comma-separated, tried in order
 *   llm.retries         = 1                        extra attempts per model on transient errors
 *   llm.base-url        = https://generativelanguage.googleapis.com
 * </pre>
 *
 * <b>Resilience.</b> Each failure is classified:
 * <ul>
 *   <li>{@link Failure#RETRYABLE} — timeout, connection reset, 429, 5xx: retry the same model
 *       with linear backoff, then move to the next model.</li>
 *   <li>{@link Failure#NEXT_MODEL} — 404 (model retired/unknown), empty or blocked reply,
 *       non-JSON body: retrying won't help, so go straight to the next model.</li>
 *   <li>{@link Failure#FATAL} — missing key, 400/401/403: no model will succeed, stop now.</li>
 * </ul>
 * When everything fails an {@link LLMException} carries a one-line summary of every attempt,
 * which the strategy records as the fallback reason. The API key is sent in a header (never
 * the URL) and never logged.
 */
@Component
public class LLMGateway implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LLMGateway.class);

    public enum Failure { RETRYABLE, NEXT_MODEL, FATAL }

    private final ObjectMapper mapper;
    private final String provider;
    private final String apiKey;
    private final List<String> models;
    private final String baseUrl;
    private final double temperature;
    private final int maxTokens;
    private final int retries;
    private final long backoffMs;
    private final RestClient http;

    public LLMGateway(ObjectMapper mapper,
                      @Value("${llm.provider}") String provider,
                      @Value("${llm.api-key:}") String apiKey,
                      @Value("${llm.model}") String model,
                      @Value("${llm.fallback-models:}") String fallbackModels,
                      @Value("${llm.base-url}") String baseUrl,
                      @Value("${llm.temperature:0.2}") double temperature,
                      @Value("${llm.max-tokens:800}") int maxTokens,
                      @Value("${llm.retries:1}") int retries,
                      @Value("${llm.retry-backoff-ms:750}") long backoffMs,
                      @Value("${llm.connect-timeout-ms:5000}") int connectTimeoutMs,
                      @Value("${llm.timeout-ms:20000}") int readTimeoutMs) {
        this.mapper = mapper;
        this.provider = provider.trim().toLowerCase();
        this.apiKey = apiKey;
        this.models = modelList(model, fallbackModels);
        this.baseUrl = stripTrailingSlash(baseUrl.trim());
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.retries = Math.max(0, retries);
        this.backoffMs = Math.max(0, backoffMs);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Single-prompt convenience kept from the brief's original signature. */
    public String callLLM(String prompt) {
        return complete(null, prompt).text();
    }

    @Override
    public LlmResponse complete(String systemPrompt, String userPrompt) {
        List<String> failures = new ArrayList<>();
        LLMException last = null;
        int attempts = 0;

        for (String model : models) {
            for (int attempt = 0; attempt <= retries; attempt++) {
                attempts++;
                long start = System.currentTimeMillis();
                try {
                    String text = callOnce(model, systemPrompt, userPrompt);
                    long ms = System.currentTimeMillis() - start;
                    log.info("LLM [{}:{}] answered in {} ms (attempt {})", provider, model, ms, attempts);
                    return new LlmResponse(text, model, ms, attempts);
                } catch (LLMException e) {
                    last = e;
                    failures.add(model + " → " + shorten(e.getMessage(), 120));
                    log.warn("LLM [{}:{}] attempt {} failed ({}): {}", provider, model, attempt + 1, e.failure(), e.getMessage());
                    if (e.failure() == Failure.FATAL) throw e;
                    if (e.failure() == Failure.NEXT_MODEL) break;
                    if (attempt < retries) pause(backoffMs * (attempt + 1));
                }
            }
        }
        throw new LLMException(Failure.RETRYABLE,
                "all " + attempts + " attempt(s) failed: " + String.join("; ", failures), last);
    }

    /** One HTTP call to one model. Overridable so retry behaviour can be unit-tested without a server. */
    protected String callOnce(String model, String systemPrompt, String userPrompt) {
        return switch (provider) {
            case "gemini" -> callGemini(model, systemPrompt, userPrompt);
            case "groq"   -> callOpenAICompatible(model, systemPrompt, userPrompt, baseUrl + "/openai/v1/chat/completions", true);
            case "openai" -> callOpenAICompatible(model, systemPrompt, userPrompt, baseUrl + "/v1/chat/completions", true);
            case "ollama" -> callOpenAICompatible(model, systemPrompt, userPrompt, baseUrl + "/v1/chat/completions", false);
            default       -> throw new LLMException(Failure.FATAL, "Unknown llm.provider: " + provider);
        };
    }

    public String provider() { return provider; }

    public String model() { return models.get(0); }

    public List<String> fallbackModels() { return models.subList(1, models.size()); }

    public boolean isConfigured() {
        return "ollama".equals(provider) || (apiKey != null && !apiKey.isBlank());
    }

    // ---------------------------------------------------------------- Gemini

    private String callGemini(String model, String systemPrompt, String userPrompt) {
        requireApiKey();
        String url = baseUrl + "/v1beta/models/" + model + ":generateContent";

        Map<String, Object> body = new LinkedHashMap<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        }
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))));
        body.put("generationConfig", Map.of(
                "temperature", temperature,
                "maxOutputTokens", maxTokens,
                "responseMimeType", "application/json"));

        JsonNode json = post(url, body, headers -> headers.set("x-goog-api-key", apiKey));

        JsonNode candidates = json.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            String blockReason = json.path("promptFeedback").path("blockReason").asText("none");
            throw new LLMException(Failure.NEXT_MODEL, "no candidates (blockReason=" + blockReason + ")");
        }
        JsonNode first = candidates.get(0);
        StringBuilder text = new StringBuilder();
        for (JsonNode part : first.path("content").path("parts")) {
            text.append(part.path("text").asText(""));
        }
        if (text.isEmpty()) {
            throw new LLMException(Failure.NEXT_MODEL, "empty text (finishReason="
                    + first.path("finishReason").asText("unknown") + ")");
        }
        JsonNode usage = json.path("usageMetadata");
        log.debug("Gemini tokens: prompt={} output={}", usage.path("promptTokenCount").asInt(),
                usage.path("candidatesTokenCount").asInt());
        return text.toString().trim();
    }

    // ------------------------------------------- OpenAI-compatible (Groq, LiteLLM, Ollama)

    private String callOpenAICompatible(String model, String systemPrompt, String userPrompt, String url, boolean needsKey) {
        if (needsKey) requireApiKey();

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "temperature", temperature,
                "max_tokens", maxTokens,
                "stream", false);

        JsonNode json = post(url, body, headers -> {
            if (apiKey != null && !apiKey.isBlank()) headers.setBearerAuth(apiKey);
        });

        JsonNode choices = json.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LLMException(Failure.NEXT_MODEL, "no choices in reply");
        }
        String content = choices.get(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new LLMException(Failure.NEXT_MODEL, "empty content (finish_reason="
                    + choices.get(0).path("finish_reason").asText("unknown") + ")");
        }
        JsonNode usage = json.path("usage");
        log.debug("{} tokens: prompt={} completion={}", provider, usage.path("prompt_tokens").asInt(),
                usage.path("completion_tokens").asInt());
        return content.trim();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Reads the reply as text and parses it ourselves instead of trusting the Content-Type header:
     * proxies and some gateways return JSON labelled application/octet-stream (or no type at all),
     * and when the body is not JSON the error should show what actually came back.
     */
    private JsonNode post(String url, Object body, Consumer<HttpHeaders> headers) {
        String raw;
        try {
            raw = http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            throw new LLMException(classify(status),
                    "HTTP " + status + ": " + shorten(e.getResponseBodyAsString(), 200), e);
        } catch (RestClientException e) {
            // Spring's message ("Error while extracting response…") hides the real I/O cause,
            // e.g. a read timeout or a proxy resetting the connection — surface it.
            throw new LLMException(Failure.RETRYABLE, "request failed: " + rootCause(e), e);
        }
        if (raw == null || raw.isBlank()) {
            throw new LLMException(Failure.NEXT_MODEL, "empty body");
        }
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new LLMException(Failure.NEXT_MODEL, "non-JSON body: " + shorten(raw.strip(), 200), e);
        }
    }

    static Failure classify(int status) {
        if (status == 429 || status >= 500) return Failure.RETRYABLE;
        if (status == 404) return Failure.NEXT_MODEL;
        return Failure.FATAL; // 400 bad request, 401/403 bad key or no access
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LLMException(Failure.FATAL, "llm.api-key is not set for provider " + provider);
        }
    }

    private static List<String> modelList(String primary, String fallbacks) {
        Set<String> all = new LinkedHashSet<>();
        all.add(primary.trim());
        if (fallbacks != null) {
            Arrays.stream(fallbacks.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(all::add);
        }
        return List.copyOf(all);
    }

    private static void pause(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException(Failure.FATAL, "interrupted while waiting to retry");
        }
    }

    private static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String msg = t.getMessage() == null ? "" : ": " + t.getMessage();
        return t == e ? e.getMessage() : t.getClass().getSimpleName() + msg;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    /** Thrown on any LLM failure; the AI strategy catches it and falls back to rules. */
    public static class LLMException extends RuntimeException {
        private final Failure failure;

        public LLMException(String message) { this(Failure.RETRYABLE, message, null); }

        public LLMException(Failure failure, String message) { this(failure, message, null); }

        public LLMException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }

        public Failure failure() { return failure; }
    }
}
