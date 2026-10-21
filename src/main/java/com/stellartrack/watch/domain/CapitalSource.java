package com.stellartrack.watch.domain;

/** Where a Watch session's starting capital came from. */
public enum CapitalSource {
    /** An amount entered by the administrator. */
    CUSTOM,
    /** A copy of the real Betfair available-to-bet balance at the moment Watch started (nothing is moved). */
    REAL_BALANCE
}
