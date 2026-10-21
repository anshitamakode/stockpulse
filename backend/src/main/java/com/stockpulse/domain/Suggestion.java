package com.stockpulse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Lifecycle shared by pricing and reorder suggestions. Status only changes through
 * {@link #transitionTo}, which enforces the {@link SuggestionStatus} state machine, and
 * {@code @Version} makes two concurrent decisions on the same row collide instead of both winning.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class Suggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Product product;

    private double confidence;

    @Column(length = 2000)
    private String reasoning;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Setter(lombok.AccessLevel.NONE)
    private SuggestionStatus status = SuggestionStatus.PROCESSING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerReason triggerReason;

    @Enumerated(EnumType.STRING)
    private RecommendationSource source;

    /** Model that answered when source is AI (may be a backup model); null otherwise. */
    @Column(length = 100)
    private String aiModel;

    /** What the rules alone would have recommended, when the AI answered; for side-by-side comparison. */
    @Column(length = 300)
    private String ruleBaseline;

    /** Why the AI output was discarded when source is AI_FALLBACK; null otherwise. */
    @Column(length = 500)
    private String fallbackReason;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant decidedAt;

    @Version
    private Long version;

    /** @throws IllegalStateException if the state machine does not allow the move. */
    public void transitionTo(SuggestionStatus next, Instant at) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Suggestion " + id + " cannot move from " + status + " to " + next);
        }
        this.status = next;
        if (next != SuggestionStatus.PENDING) {
            this.decidedAt = at;
        }
    }

    public boolean isOpen() {
        return status.isOpen();
    }
}
