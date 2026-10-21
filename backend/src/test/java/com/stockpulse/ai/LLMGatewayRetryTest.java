package com.stockpulse.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpulse.ai.LLMGateway.Failure;
import com.stockpulse.ai.LLMGateway.LLMException;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retry / next-model / fatal behaviour, with HTTP replaced by a scripted {@code callOnce}. */
class LLMGatewayRetryTest {

    /** Each script entry is either a reply text or an LLMException to throw. */
    static class ScriptedGateway extends LLMGateway {
        final Deque<Object> script = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();

        ScriptedGateway(int retries, Object... steps) {
            super(new ObjectMapper(), "gemini", "test-key", "primary", "backup", "https://example.test",
                    0.2, 800, retries, 0, 1000, 1000);
            for (Object s : steps) script.add(s);
        }

        @Override
        protected String callOnce(String model, String systemPrompt, String userPrompt) {
            calls.add(model);
            Object next = script.poll();
            if (next instanceof LLMException e) throw e;
            return (String) next;
        }
    }

    private static LLMException fail(Failure f, String msg) {
        return new LLMException(f, msg);
    }

    @Test
    void firstAttemptSucceeds() {
        ScriptedGateway g = new ScriptedGateway(1, "{\"ok\":1}");
        LlmResponse r = g.complete("sys", "user");
        assertThat(r.model()).isEqualTo("primary");
        assertThat(r.attempts()).isEqualTo(1);
    }

    @Test
    void transientErrorIsRetriedOnSameModel() {
        ScriptedGateway g = new ScriptedGateway(1, fail(Failure.RETRYABLE, "HTTP 503"), "{\"ok\":1}");
        LlmResponse r = g.complete("sys", "user");
        assertThat(r.model()).isEqualTo("primary");
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(g.calls).isEqualTo(List.of("primary", "primary"));
    }

    @Test
    void persistentOutageMovesToBackupModel() {
        ScriptedGateway g = new ScriptedGateway(1,
                fail(Failure.RETRYABLE, "timeout"), fail(Failure.RETRYABLE, "timeout"), "{\"ok\":1}");
        LlmResponse r = g.complete("sys", "user");
        assertThat(r.model()).isEqualTo("backup");
        assertThat(g.calls).isEqualTo(List.of("primary", "primary", "backup"));
    }

    @Test
    void unknownModelSkipsRetriesAndGoesStraightToBackup() {
        ScriptedGateway g = new ScriptedGateway(3, fail(Failure.NEXT_MODEL, "HTTP 404"), "{\"ok\":1}");
        LlmResponse r = g.complete("sys", "user");
        assertThat(r.model()).isEqualTo("backup");
        assertThat(g.calls).isEqualTo(List.of("primary", "backup"));
    }

    @Test
    void fatalErrorStopsImmediately() {
        ScriptedGateway g = new ScriptedGateway(3, fail(Failure.FATAL, "HTTP 403: bad key"), "{\"ok\":1}");
        assertThatThrownBy(() -> g.complete("sys", "user")).isInstanceOf(LLMException.class);
        assertThat(g.calls).isEqualTo(List.of("primary"));
    }

    @Test
    void everythingFailingSummarisesEachAttempt() {
        ScriptedGateway g = new ScriptedGateway(0, fail(Failure.RETRYABLE, "HTTP 503"), fail(Failure.NEXT_MODEL, "HTTP 404"));
        try {
            g.complete("sys", "user");
            throw new AssertionError("expected failure");
        } catch (LLMException e) {
            assertThat(e.getMessage()).contains("all 2 attempt(s) failed").contains("primary → HTTP 503").contains("backup → HTTP 404");
        }
    }

    @Test
    void httpStatusClassification() {
        assertThat(LLMGateway.classify(503)).isEqualTo(Failure.RETRYABLE);
        assertThat(LLMGateway.classify(429)).isEqualTo(Failure.RETRYABLE);
        assertThat(LLMGateway.classify(404)).isEqualTo(Failure.NEXT_MODEL);
        assertThat(LLMGateway.classify(401)).isEqualTo(Failure.FATAL);
        assertThat(LLMGateway.classify(400)).isEqualTo(Failure.FATAL);
    }
}
