package com.stockpulse.domain;

/**
 * Why a suggestion was requested. {@link #priority} decides whether a new signal may supersede
 * an open suggestion: a demand spike outranks a low-stock signal, which outranks a manual ask.
 */
public enum TriggerReason {
    MANUAL(0, false),
    LOW_STOCK(1, true),
    DEMAND_SPIKE(2, true);

    private final int priority;
    private final boolean automatic;

    TriggerReason(int priority, boolean automatic) {
        this.priority = priority;
        this.automatic = automatic;
    }

    public int priority() { return priority; }

    public boolean isAutomatic() { return automatic; }

    public boolean outranks(TriggerReason other) {
        return this.priority > other.priority;
    }
}
