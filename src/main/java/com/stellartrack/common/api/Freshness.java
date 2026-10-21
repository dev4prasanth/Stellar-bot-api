package com.stellartrack.common.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Truthful data-freshness label shared by every API that shows provider data.
 * LIVE is used only for fresh, non-delayed data; delayed data is DELAYED even when recent.
 */
public record Freshness(State state, Instant asOf, String source, String detail) {

    public enum State {
        LIVE,
        DELAYED,
        STALE,
        UNAVAILABLE
    }

    public static Freshness evaluate(Instant asOf, Duration maxAge, boolean providerDelayed, String source, Clock clock) {
        if (asOf == null) {
            return unavailable(source, "No data has been received");
        }
        if (asOf.isBefore(clock.instant().minus(maxAge))) {
            return new Freshness(State.STALE, asOf, source, "Older than " + maxAge.toSeconds() + "s");
        }
        if (providerDelayed) {
            return new Freshness(State.DELAYED, asOf, source, "Provider reports delayed market data");
        }
        return new Freshness(State.LIVE, asOf, source, null);
    }

    public static Freshness unavailable(String source, String detail) {
        return new Freshness(State.UNAVAILABLE, null, source, detail);
    }

    public boolean isUsableForExecution() {
        return state == State.LIVE;
    }
}
