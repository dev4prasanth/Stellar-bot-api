package com.stellartrack.watch.domain;

/** Simulated matching state of a Watch bet. */
public enum MatchingStatus {
    /** Nothing matched yet; still waiting for the price. */
    UNMATCHED,
    PARTIALLY_MATCHED,
    MATCHED,
    /** The unmatched part lapsed (at the off or at close); any matched part stands. */
    LAPSED,
    /** The runner was removed before anything matched. */
    VOIDED;

    public boolean isOpen() {
        return this == UNMATCHED || this == PARTIALLY_MATCHED;
    }
}
