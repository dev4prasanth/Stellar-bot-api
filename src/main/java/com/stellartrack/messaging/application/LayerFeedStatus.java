package com.stellartrack.messaging.application;

import com.stellartrack.messaging.domain.Layer;
import java.time.Instant;

/**
 * Freshness of one layer feed as seen by dependent automatic rules. Anything other than {@code FRESH}
 * must fail closed for rules that require the layer.
 */
public record LayerFeedStatus(Layer layer, State state, String reason, Instant lastAcceptedAt,
        Instant lastSourceEventAt) {

    public enum State {
        BLOCKED,
        DISABLED,
        DISCONNECTED,
        MISSING,
        STALE,
        FRESH
    }

    public boolean isUsable() {
        return state == State.FRESH;
    }
}
