package com.stellartrack.watch.domain;

/** Simulated settlement result of a Watch bet. */
public enum ResultStatus {
    OPEN,
    /** Nothing was matched: no profit or loss. */
    NOT_MATCHED,
    WON,
    LOST,
    VOID,
    /** The settlement rules cannot determine the result (never guessed). */
    UNRESOLVED;

    public boolean isSettled() {
        return this != OPEN;
    }
}
