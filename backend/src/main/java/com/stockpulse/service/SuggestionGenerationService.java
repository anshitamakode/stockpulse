package com.stockpulse.service;

import com.stockpulse.domain.PricingSuggestion;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.RecommendationSource;
import com.stockpulse.domain.ReorderSuggestion;
import com.stockpulse.domain.Suggestion;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.engine.CommerceEngine;
import com.stockpulse.engine.CommerceRecommendation;
import com.stockpulse.engine.PricingRecommendation;
import com.stockpulse.engine.ProductContext;
import com.stockpulse.engine.ReorderRecommendation;
import com.stockpulse.event.SuggestionsOpenedEvent;
import com.stockpulse.repository.PricingSuggestionRepository;
import com.stockpulse.repository.ProductRepository;
import com.stockpulse.repository.ReorderSuggestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * The asynchronous half of the loop: PROCESSING → PENDING (or FAILED / SKIPPED).
 * <ol>
 *   <li>Runs on a virtual thread only after the opening transaction commits.</li>
 *   <li>Reads a fresh {@link ProductContext} in a short read-only transaction.</li>
 *   <li>Calls the active strategy <em>outside</em> any transaction — an LLM call can take seconds
 *       and must not hold a DB connection or row lock.</li>
 *   <li>Writes each result in its own short transaction. If the row is no longer PROCESSING
 *       (a stronger signal superseded it meanwhile) the result is discarded.</li>
 * </ol>
 * Pricing and reorder are written independently, so one failing never loses the other.
 */
@Service
public class SuggestionGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionGenerationService.class);

    private final ProductRepository products;
    private final PricingSuggestionRepository pricing;
    private final ReorderSuggestionRepository reorders;
    private final DemandTracker demand;
    private final CommerceEngine engine;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public SuggestionGenerationService(ProductRepository products, PricingSuggestionRepository pricing,
                                       ReorderSuggestionRepository reorders, DemandTracker demand,
                                       CommerceEngine engine, PlatformTransactionManager txManager, Clock clock) {
        this.products = products;
        this.pricing = pricing;
        this.reorders = reorders;
        this.demand = demand;
        this.engine = engine;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSuggestionsOpened(SuggestionsOpenedEvent event) {
        String strategy = engine.activeName();
        CommerceRecommendation recommendation;
        try {
            ProductContext context = readTx.execute(status -> products.findById(event.productId())
                    .map(p -> demand.contextFor(p, event.trigger()))
                    .orElseThrow(() -> new IllegalStateException("Product " + event.productId() + " disappeared")));
            recommendation = engine.recommend(context);
        } catch (RuntimeException e) {
            log.error("Suggestion generation failed for {} ({}) with strategy {}", event.productId(), event.trigger(), strategy, e);
            String reason = "Could not generate a recommendation: " + e.getMessage();
            markFailed(event.pricingSuggestionId(), true, reason);
            markFailed(event.reorderSuggestionId(), false, reason);
            refreshStatus(event.productId());
            return;
        }

        CommerceRecommendation baseline = recommendation.ruleBaseline();
        if (event.pricingSuggestionId() != null) {
            completePricing(event.pricingSuggestionId(), recommendation.pricing(), recommendation.aiModel(),
                    baseline == null ? null : describe(baseline.pricing()));
        }
        if (event.reorderSuggestionId() != null) {
            completeReorder(event.reorderSuggestionId(), recommendation.reorder(), recommendation.aiModel(),
                    baseline == null ? null : describe(baseline.reorder()));
        }
        log.info("Suggestions ready for {} ({}, strategy={}, pricingSource={}, reorderSource={})",
                event.productId(), event.trigger(), strategy,
                recommendation.pricing().source(), recommendation.reorder().source());
    }

    /** Called by the sweeper for suggestions that have been PROCESSING for far too long. */
    public void giveUp(SuggestionsOpenedEvent event, String reason) {
        markFailed(event.pricingSuggestionId(), true, reason);
        markFailed(event.reorderSuggestionId(), false, reason);
        refreshStatus(event.productId());
    }

    private void completePricing(UUID id, PricingRecommendation r, String model, String baseline) {
        writeIfStillProcessing(() -> pricing.findById(id).orElse(null), s -> {
            PricingSuggestion p = (PricingSuggestion) s;
            p.setAction(r.action());
            p.setRecommendedPrice(r.recommendedPrice());
            p.setConfidence(r.confidence());
            p.setReasoning(r.reasoning());
            p.setSource(r.source());
            p.setFallbackReason(r.fallbackReason());
            applyAiMetadata(p, r.source(), model, baseline);
            p.transitionTo(SuggestionStatus.PENDING, clock.instant());
        });
    }

    private void completeReorder(UUID id, ReorderRecommendation r, String model, String baseline) {
        writeIfStillProcessing(() -> reorders.findById(id).orElse(null), s -> {
            ReorderSuggestion o = (ReorderSuggestion) s;
            o.setRecommendedQuantity(r.quantity());
            o.setConfidence(r.confidence());
            o.setReasoning(r.reasoning());
            o.setSource(r.source());
            o.setFallbackReason(r.fallbackReason());
            applyAiMetadata(o, r.source(), model, baseline);
            o.transitionTo(r.quantity() > 0 ? SuggestionStatus.PENDING : SuggestionStatus.SKIPPED, clock.instant());
        });
    }

    /** Model and rule comparison are only meaningful when the AI's answer is the one shown. */
    private static void applyAiMetadata(Suggestion s, RecommendationSource source, String model, String baseline) {
        boolean ai = source == RecommendationSource.AI;
        s.setAiModel(ai ? model : null);
        s.setRuleBaseline(ai ? baseline : null);
    }

    static String describe(PricingRecommendation p) {
        return switch (p.action()) {
            case HOLD -> "hold at $" + p.recommendedPrice().toPlainString();
            case RAISE -> "raise to $" + p.recommendedPrice().toPlainString();
            case LOWER -> "lower to $" + p.recommendedPrice().toPlainString();
        };
    }

    static String describe(ReorderRecommendation r) {
        return r.quantity() > 0 ? "reorder " + r.quantity() + " units" : "no reorder";
    }

    private void markFailed(UUID id, boolean isPricing, String reason) {
        if (id == null) return;
        writeIfStillProcessing(
                () -> isPricing ? pricing.findById(id).orElse(null) : reorders.findById(id).orElse(null),
                s -> {
                    s.setReasoning(SuggestionService.appendNote(null, reason));
                    s.transitionTo(SuggestionStatus.FAILED, clock.instant());
                });
    }

    private void writeIfStillProcessing(java.util.function.Supplier<? extends Suggestion> loader,
                                        java.util.function.Consumer<Suggestion> update) {
        try {
            tx.executeWithoutResult(status -> {
                Suggestion s = loader.get();
                if (s == null) return;
                if (s.getStatus() != SuggestionStatus.PROCESSING) {
                    log.info("Discarding result for suggestion {}: now {}", s.getId(), s.getStatus());
                    return;
                }
                update.accept(s);
            });
        } catch (OptimisticLockingFailureException e) {
            log.info("Suggestion changed concurrently (likely superseded); result discarded");
        }
    }

    /** Needed only when a pricing suggestion fails: the product may no longer be under review. */
    private void refreshStatus(String productId) {
        try {
            tx.executeWithoutResult(status -> products.findById(productId).ifPresent((Product p) ->
                    p.refreshStatus(pricing.existsByProductIdAndStatusIn(productId, SuggestionStatus.OPEN))));
        } catch (OptimisticLockingFailureException e) {
            log.debug("Product {} changed concurrently; its status is refreshed by that change", productId);
        }
    }
}
