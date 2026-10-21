package com.stockpulse.service;

import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.Suggestion;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.event.SuggestionsOpenedEvent;
import com.stockpulse.repository.PricingSuggestionRepository;
import com.stockpulse.repository.ReorderSuggestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Closes the gap left by in-memory events (ADR-1): if the app stops, an async task dies, or a
 * call hangs, suggestions would sit in PROCESSING forever. Every minute this sweeper:
 * <ul>
 *   <li>re-dispatches PROCESSING rows older than {@code staleAfterSeconds} through the same
 *       generation path (results for rows that finished meanwhile are discarded there), and</li>
 *   <li>marks rows older than {@code giveUpAfterSeconds} FAILED, so nothing spins forever.</li>
 * </ul>
 * Pricing and reorder rows opened by the same signal are re-dispatched together, which keeps
 * them on one LLM call.
 */
@Component
public class StaleSuggestionSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaleSuggestionSweeper.class);

    private final PricingSuggestionRepository pricing;
    private final ReorderSuggestionRepository reorders;
    private final SuggestionGenerationService generation;
    private final StockPulseProperties.Recovery recovery;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public StaleSuggestionSweeper(PricingSuggestionRepository pricing, ReorderSuggestionRepository reorders,
                                  SuggestionGenerationService generation, StockPulseProperties props,
                                  PlatformTransactionManager txManager, Clock clock) {
        this.pricing = pricing;
        this.reorders = reorders;
        this.generation = generation;
        this.recovery = props.recovery();
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    /** A PROCESSING row reduced to what the sweep needs (read inside a short transaction). */
    record StaleRow(UUID id, boolean isPricing, String productId, TriggerReason trigger, Instant createdAt) {}

    /** What to do with the stale rows found in one sweep. */
    record Plan(List<SuggestionsOpenedEvent> retry, List<SuggestionsOpenedEvent> giveUp) {}

    @Scheduled(fixedDelayString = "${stockpulse.recovery.sweep-interval-ms:60000}",
               initialDelayString = "${stockpulse.recovery.sweep-interval-ms:60000}")
    public void sweep() {
        Instant now = clock.instant();
        Instant staleBefore = now.minusSeconds(recovery.staleAfterSeconds());
        List<StaleRow> rows = readTx.execute(status -> {
            List<StaleRow> found = new ArrayList<>();
            pricing.findByStatusAndCreatedAtBefore(SuggestionStatus.PROCESSING, staleBefore)
                    .forEach(s -> found.add(row(s, true)));
            reorders.findByStatusAndCreatedAtBefore(SuggestionStatus.PROCESSING, staleBefore)
                    .forEach(s -> found.add(row(s, false)));
            return found;
        });
        if (rows == null || rows.isEmpty()) return;

        Plan plan = plan(rows, now.minusSeconds(recovery.giveUpAfterSeconds()));
        plan.giveUp().forEach(e -> {
            log.warn("Giving up on suggestions for {} ({}): PROCESSING for over {} s", e.productId(), e.trigger(),
                    recovery.giveUpAfterSeconds());
            generation.giveUp(e, "Timed out: no recommendation after " + recovery.giveUpAfterSeconds()
                    + " seconds. Request a new one with Ask advisor.");
        });
        plan.retry().forEach(e -> {
            log.info("Re-dispatching stale suggestions for {} ({})", e.productId(), e.trigger());
            generation.onSuggestionsOpened(e); // @Async through the proxy
        });
    }

    /** Pure decision logic, unit-tested without Spring. Rows past the give-up instant are failed. */
    static Plan plan(List<StaleRow> rows, Instant giveUpBefore) {
        Map<String, UUID[]> retryGroups = new LinkedHashMap<>();
        Map<String, UUID[]> giveUpGroups = new LinkedHashMap<>();
        Map<String, StaleRow> sample = new LinkedHashMap<>();
        for (StaleRow r : rows) {
            boolean expired = r.createdAt().isBefore(giveUpBefore);
            String key = (expired ? "x|" : "r|") + r.productId() + "|" + r.trigger();
            UUID[] ids = (expired ? giveUpGroups : retryGroups).computeIfAbsent(key, k -> new UUID[2]);
            ids[r.isPricing() ? 0 : 1] = r.id();
            sample.putIfAbsent(key, r);
        }
        return new Plan(events(retryGroups, sample), events(giveUpGroups, sample));
    }

    private static List<SuggestionsOpenedEvent> events(Map<String, UUID[]> groups, Map<String, StaleRow> sample) {
        List<SuggestionsOpenedEvent> out = new ArrayList<>();
        groups.forEach((key, ids) -> {
            StaleRow r = sample.get(key);
            out.add(new SuggestionsOpenedEvent(r.productId(), r.trigger(), ids[0], ids[1]));
        });
        return out;
    }

    private static StaleRow row(Suggestion s, boolean isPricing) {
        return new StaleRow(s.getId(), isPricing, s.getProduct().getId(), s.getTriggerReason(), s.getCreatedAt());
    }
}
