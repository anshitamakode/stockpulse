package com.stockpulse.service;

import com.stockpulse.domain.InventorySnapshot;
import com.stockpulse.domain.PricingAction;
import com.stockpulse.domain.PricingSuggestion;
import com.stockpulse.domain.Product;
import com.stockpulse.domain.ReorderSuggestion;
import com.stockpulse.domain.SnapshotReason;
import com.stockpulse.domain.Suggestion;
import com.stockpulse.domain.SuggestionStatus;
import com.stockpulse.domain.TriggerReason;
import com.stockpulse.event.SuggestionsOpenedEvent;
import com.stockpulse.exception.BadRequestException;
import com.stockpulse.exception.ConflictException;
import com.stockpulse.exception.NotFoundException;
import com.stockpulse.repository.InventorySnapshotRepository;
import com.stockpulse.repository.PricingSuggestionRepository;
import com.stockpulse.repository.ProductRepository;
import com.stockpulse.repository.ReorderSuggestionRepository;
import com.stockpulse.web.dto.SuggestionDto;
import com.stockpulse.web.dto.SuggestionKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Opens, lists and decides suggestions. Opening only creates PROCESSING rows and publishes an
 * event; the slow part (strategy / LLM) runs in {@link SuggestionGenerationService} after commit.
 */
@Service
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);

    private final ProductRepository products;
    private final PricingSuggestionRepository pricing;
    private final ReorderSuggestionRepository reorders;
    private final InventorySnapshotRepository snapshots;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SuggestionService(ProductRepository products, PricingSuggestionRepository pricing,
                             ReorderSuggestionRepository reorders, InventorySnapshotRepository snapshots,
                             ApplicationEventPublisher events, Clock clock) {
        this.products = products;
        this.pricing = pricing;
        this.reorders = reorders;
        this.snapshots = snapshots;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ opening

    /**
     * Opens a pricing and a reorder suggestion for the product unless open ones already exist.
     * A stronger trigger (spike &gt; low stock &gt; manual) supersedes weaker open suggestions so the
     * merchandiser always sees advice for the most important current signal.
     * Must run inside the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SuggestionDto> open(Product product, TriggerReason trigger) {
        Instant now = clock.instant();

        PricingSuggestion newPricing = null;
        if (makeRoom(pricing.findByProductIdAndStatusIn(product.getId(), SuggestionStatus.OPEN), trigger, now)) {
            newPricing = new PricingSuggestion();
            newPricing.setBasePrice(product.getCurrentPrice());
            init(newPricing, product, trigger, now);
            newPricing = pricing.save(newPricing);
        }

        ReorderSuggestion newReorder = null;
        if (makeRoom(reorders.findByProductIdAndStatusIn(product.getId(), SuggestionStatus.OPEN), trigger, now)) {
            newReorder = new ReorderSuggestion();
            newReorder.setStockAtRequest(product.getStockLevel());
            init(newReorder, product, trigger, now);
            newReorder = reorders.save(newReorder);
        }

        SuggestionsOpenedEvent event = new SuggestionsOpenedEvent(product.getId(), trigger,
                newPricing == null ? null : newPricing.getId(),
                newReorder == null ? null : newReorder.getId());
        if (event.isEmpty()) {
            log.debug("{} signal for {} ignored: stronger or equal suggestions already open", trigger, product.getId());
            return List.of();
        }
        events.publishEvent(event);
        log.info("Opened suggestions for {} ({}): pricing={} reorder={}", product.getId(), trigger,
                event.pricingSuggestionId(), event.reorderSuggestionId());

        List<SuggestionDto> opened = new ArrayList<>(2);
        if (newPricing != null) opened.add(SuggestionDto.from(newPricing));
        if (newReorder != null) opened.add(SuggestionDto.from(newReorder));
        return opened;
    }

    /** @return true if a new suggestion may be opened (after superseding weaker open ones) */
    private boolean makeRoom(List<? extends Suggestion> open, TriggerReason trigger, Instant now) {
        if (open.isEmpty()) return true;
        boolean outranksAll = open.stream().allMatch(s -> trigger.outranks(s.getTriggerReason()));
        if (!outranksAll) return false;
        open.forEach(s -> {
            s.transitionTo(SuggestionStatus.SUPERSEDED, now);
            s.setReasoning(appendNote(s.getReasoning(), "Superseded by a " + trigger + " signal."));
        });
        return true;
    }

    private static void init(Suggestion s, Product product, TriggerReason trigger, Instant now) {
        s.setProduct(product);
        s.setTriggerReason(trigger);
        s.setCreatedAt(now);
    }

    /** Manual "ask the advisor" from the console. 409 if both kinds are already open. */
    @Transactional
    public List<SuggestionDto> requestManual(String productId) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product " + productId + " not found"));
        List<SuggestionDto> opened = open(product, TriggerReason.MANUAL);
        if (opened.isEmpty()) {
            throw new ConflictException("Suggestions for " + productId + " are already open; decide those first");
        }
        refreshProductStatus(product);
        return opened;
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<SuggestionDto> list(Collection<SuggestionStatus> statuses) {
        Stream<SuggestionDto> p = (statuses == null || statuses.isEmpty()
                ? pricing.findAllByOrderByCreatedAtDesc()
                : pricing.findByStatusInOrderByCreatedAtDesc(statuses)).stream().map(SuggestionDto::from);
        Stream<SuggestionDto> r = (statuses == null || statuses.isEmpty()
                ? reorders.findAllByOrderByCreatedAtDesc()
                : reorders.findByStatusInOrderByCreatedAtDesc(statuses)).stream().map(SuggestionDto::from);
        return Stream.concat(p, r)
                .sorted(Comparator.comparing(SuggestionDto::createdAt).reversed()
                        .thenComparing(SuggestionDto::kind))
                .toList();
    }

    // ------------------------------------------------------------------ deciding

    private static final Set<SuggestionStatus> DECISIONS = Set.of(SuggestionStatus.ACCEPTED, SuggestionStatus.REJECTED);

    @Transactional
    public SuggestionDto decide(SuggestionKind kind, UUID id, SuggestionStatus decision) {
        if (!DECISIONS.contains(decision)) {
            throw new BadRequestException("status must be ACCEPTED or REJECTED");
        }
        return switch (kind) {
            case PRICING -> decidePricing(id, decision);
            case REORDER -> decideReorder(id, decision);
        };
    }

    private SuggestionDto decidePricing(UUID id, SuggestionStatus decision) {
        PricingSuggestion s = pricing.findById(id)
                .orElseThrow(() -> new NotFoundException("Pricing suggestion " + id + " not found"));
        requirePending(s);
        Product product = s.getProduct();
        Instant now = clock.instant();

        if (decision == SuggestionStatus.ACCEPTED) {
            if (product.getCurrentPrice().compareTo(s.getBasePrice()) != 0) {
                throw new ConflictException("Price changed from " + s.getBasePrice() + " to "
                        + product.getCurrentPrice() + " since this suggestion was made; reject it and request a new one");
            }
            if (s.getAction() != PricingAction.HOLD) {
                product.setCurrentPrice(s.getRecommendedPrice());
                snapshots.save(InventorySnapshot.of(product, 0, SnapshotReason.PRICE_CHANGE, now));
                log.info("Price of {} changed {} -> {} (suggestion {})", product.getId(), s.getBasePrice(),
                        s.getRecommendedPrice(), id);
            }
        }
        s.transitionTo(decision, now);
        refreshProductStatus(product);
        return SuggestionDto.from(s);
    }

    private SuggestionDto decideReorder(UUID id, SuggestionStatus decision) {
        ReorderSuggestion s = reorders.findById(id)
                .orElseThrow(() -> new NotFoundException("Reorder suggestion " + id + " not found"));
        requirePending(s);
        // Sprint 2: ACCEPTED becomes a purchase order. Until then acceptance is recorded and the
        // stock arrives through POST /products/{id}/restock.
        s.transitionTo(decision, clock.instant());
        return SuggestionDto.from(s);
    }

    private static void requirePending(Suggestion s) {
        if (s.getStatus() != SuggestionStatus.PENDING) {
            throw new ConflictException("Suggestion " + s.getId() + " is " + s.getStatus() + "; only PENDING can be decided");
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Recomputes the derived product status inside the current transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshProductStatus(Product product) {
        product.refreshStatus(pricing.existsByProductIdAndStatusIn(product.getId(), SuggestionStatus.OPEN));
    }

    static String appendNote(String reasoning, String note) {
        String combined = reasoning == null || reasoning.isBlank() ? note : reasoning + " " + note;
        return combined.length() > 2000 ? combined.substring(0, 1999) + "…" : combined;
    }
}
