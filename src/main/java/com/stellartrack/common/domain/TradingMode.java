package com.stellartrack.common.domain;

/**
 * The global trading mode. Exactly one is active at a time.
 * <ul>
 *   <li>MANUAL: real bets; every proposal waits for the administrator's approval.</li>
 *   <li>AUTOMATIC: real bets; proposals are submitted without approval.</li>
 *   <li>WATCH: simulation only; no real bet can be placed. Proposals become simulated bets against a virtual
 *   capital. Entered and left only through a Watch session.</li>
 * </ul>
 * Distinct from {@link OperatingMode}, which records the origin of an order and the mode a real bet was placed under.
 */
public enum TradingMode {
    MANUAL,
    AUTOMATIC,
    WATCH;

    /** True for the modes that place real bets. */
    public boolean isLive() {
        return this != WATCH;
    }
}
