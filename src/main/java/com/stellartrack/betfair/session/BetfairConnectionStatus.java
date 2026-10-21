package com.stellartrack.betfair.session;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Observed (not assumed) Betfair connectivity, surfaced to the account header and capability report. */
@Component
public class BetfairConnectionStatus {

    public record Snapshot(boolean sessionActive, Instant lastLoginAt, Instant lastSuccessfulCallAt,
            Instant lastFailureAt, String lastError) {
    }

    private final AtomicReference<Snapshot> state =
            new AtomicReference<>(new Snapshot(false, null, null, null, null));
    private final Clock clock;

    public BetfairConnectionStatus(Clock clock) {
        this.clock = clock;
    }

    public void loggedIn() {
        state.updateAndGet(s -> new Snapshot(true, clock.instant(), s.lastSuccessfulCallAt(), s.lastFailureAt(), s.lastError()));
    }

    public void sessionLost(String reason) {
        state.updateAndGet(s -> new Snapshot(false, s.lastLoginAt(), s.lastSuccessfulCallAt(), clock.instant(), reason));
    }

    public void callSucceeded() {
        state.updateAndGet(s -> new Snapshot(s.sessionActive(), s.lastLoginAt(), clock.instant(), s.lastFailureAt(), s.lastError()));
    }

    public void callFailed(String error) {
        state.updateAndGet(s -> new Snapshot(s.sessionActive(), s.lastLoginAt(), s.lastSuccessfulCallAt(), clock.instant(), error));
    }

    public Snapshot snapshot() {
        return state.get();
    }

    /** True once at least one real API call has succeeded in this runtime. */
    public boolean verified() {
        return state.get().lastSuccessfulCallAt() != null;
    }
}
