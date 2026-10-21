package com.stellartrack.betfair.stream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Observable state of the Exchange Stream connection, used for stale-data detection and the UI. */
@Component
public class StreamConnectionState {

    public enum Phase {
        DISABLED,
        CONNECTING,
        SUBSCRIBED,
        RECONNECTING,
        FAILED
    }

    public record Snapshot(Phase phase, String connectionId, Instant lastMessageAt, Instant connectedAt,
            int subscribedMarkets, boolean providerReportedStale, String lastError, int reconnects) {
    }

    private final AtomicReference<Snapshot> state = new AtomicReference<>(
            new Snapshot(Phase.DISABLED, null, null, null, 0, false, null, 0));
    private final Clock clock;

    public StreamConnectionState(Clock clock) {
        this.clock = clock;
    }

    void phase(Phase phase, String error) {
        state.updateAndGet(s -> new Snapshot(phase, s.connectionId(), s.lastMessageAt(),
                phase == Phase.SUBSCRIBED ? clock.instant() : s.connectedAt(), s.subscribedMarkets(),
                s.providerReportedStale(), error != null ? error : s.lastError(),
                phase == Phase.RECONNECTING ? s.reconnects() + 1 : s.reconnects()));
    }

    void connected(String connectionId) {
        state.updateAndGet(s -> new Snapshot(s.phase(), connectionId, clock.instant(), s.connectedAt(),
                s.subscribedMarkets(), false, s.lastError(), s.reconnects()));
    }

    void messageReceived(boolean providerStale) {
        state.updateAndGet(s -> new Snapshot(s.phase(), s.connectionId(), clock.instant(), s.connectedAt(),
                s.subscribedMarkets(), providerStale, s.lastError(), s.reconnects()));
    }

    void subscribedMarkets(int count) {
        state.updateAndGet(s -> new Snapshot(s.phase(), s.connectionId(), s.lastMessageAt(), s.connectedAt(), count,
                s.providerReportedStale(), s.lastError(), s.reconnects()));
    }

    public Snapshot snapshot() {
        return state.get();
    }

    /** Fresh = subscribed, provider not flagging stale data, and a message (data or heartbeat) seen recently. */
    public boolean isFresh(Duration maxSilence) {
        Snapshot s = state.get();
        return s.phase() == Phase.SUBSCRIBED && !s.providerReportedStale() && s.lastMessageAt() != null
                && s.lastMessageAt().isAfter(clock.instant().minus(maxSilence));
    }
}
