package com.stockpulse.domain;

public enum RecommendationSource {
    /** LLM output that passed validation. */
    AI,
    /** Deterministic rules were the active strategy. */
    RULE_BASED,
    /** AI was the active strategy but its output failed or was invalid, so rules decided. */
    AI_FALLBACK
}
