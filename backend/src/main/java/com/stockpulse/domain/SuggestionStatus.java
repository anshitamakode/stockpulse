package com.stockpulse.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Explicit state machine shared by pricing and reorder suggestions.
 *
 * <pre>
 *   PROCESSING ──► PENDING ──► ACCEPTED
 *       │             │  └───► REJECTED
 *       │             └──────► SUPERSEDED   (a stronger signal replaced it)
 *       ├──► FAILED        (no recommendation could be produced)
 *       ├──► SKIPPED       (engine decided nothing is needed, e.g. reorder qty 0)
 *       └──► SUPERSEDED
 * </pre>
 * ACCEPTED, REJECTED, FAILED, SKIPPED and SUPERSEDED are terminal.
 */
public enum SuggestionStatus {
    PROCESSING, PENDING, ACCEPTED, REJECTED, FAILED, SKIPPED, SUPERSEDED;

    private static final Map<SuggestionStatus, Set<SuggestionStatus>> ALLOWED = Map.of(
            PROCESSING, EnumSet.of(PENDING, FAILED, SKIPPED, SUPERSEDED),
            PENDING, EnumSet.of(ACCEPTED, REJECTED, SUPERSEDED),
            ACCEPTED, EnumSet.noneOf(SuggestionStatus.class),
            REJECTED, EnumSet.noneOf(SuggestionStatus.class),
            FAILED, EnumSet.noneOf(SuggestionStatus.class),
            SKIPPED, EnumSet.noneOf(SuggestionStatus.class),
            SUPERSEDED, EnumSet.noneOf(SuggestionStatus.class));

    /** Statuses that block a new suggestion of the same kind for the same product. */
    public static final Set<SuggestionStatus> OPEN = EnumSet.of(PROCESSING, PENDING);

    public boolean canTransitionTo(SuggestionStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isOpen() {
        return OPEN.contains(this);
    }
}
