package com.stockpulse.event;

import com.stockpulse.domain.TriggerReason;

import java.util.UUID;

/**
 * Published inside the transaction that created PROCESSING suggestions. The listener runs only
 * after that transaction commits, so it always finds the rows (and never runs for a rolled-back order).
 *
 * @param pricingSuggestionId null when no pricing suggestion was opened
 * @param reorderSuggestionId null when no reorder suggestion was opened
 */
public record SuggestionsOpenedEvent(String productId, TriggerReason trigger,
                                     UUID pricingSuggestionId, UUID reorderSuggestionId) {

    public boolean isEmpty() {
        return pricingSuggestionId == null && reorderSuggestionId == null;
    }
}
