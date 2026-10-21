package com.stellartrack.watch.domain;

/**
 * The single status shown for a Watch bet, combining its matching and settlement state. Its derivation is defined
 * once, as {@code display_status} in the {@code v_watch_bet} view.
 */
public enum WatchBetStatus {
    PENDING,
    PARTIALLY_MATCHED,
    MATCHED,
    UNMATCHED,
    WON,
    LOST,
    VOID,
    UNRESOLVED,
    /** Filter only: pending, partially matched or matched (waiting for the race). */
    OPEN,
    /** Filter only: won, lost or void. */
    SETTLED,
    /** Filter only: no longer open (settled, unmatched or unresolved). */
    CLOSED
}
