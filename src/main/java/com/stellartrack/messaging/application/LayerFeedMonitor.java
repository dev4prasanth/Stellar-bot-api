package com.stellartrack.messaging.application;

import com.stellartrack.messaging.config.MessagingProperties;
import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.messaging.listener.LayerListenerRegistrar;
import com.stellartrack.messaging.persistence.MessageReceiptRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Determines, per layer, whether its data can be relied on right now. Used to fail closed in Automatic Mode. */
@Service
public class LayerFeedMonitor {

    private final LayerListenerRegistrar registrar;
    private final MessageReceiptRepository receipts;
    private final MessagingProperties properties;
    private final Clock clock;

    public LayerFeedMonitor(LayerListenerRegistrar registrar, MessageReceiptRepository receipts,
            MessagingProperties properties, Clock clock) {
        this.registrar = registrar;
        this.receipts = receipts;
        this.properties = properties;
        this.clock = clock;
    }

    public LayerFeedStatus status(Layer layer) {
        Instant lastAccepted = receipts.lastAcceptedAt(layer).orElse(null);
        Instant lastSourceEvent = receipts.lastAcceptedSourceEventAt(layer).orElse(null);
        Optional<LayerListenerRegistrar.ConsumerBlock> blocked = registrar.blockingReason(layer);
        if (blocked.isPresent()) {
            LayerFeedStatus.State state = blocked.get().contractPending()
                    ? LayerFeedStatus.State.BLOCKED
                    : LayerFeedStatus.State.DISABLED;
            return new LayerFeedStatus(layer, state, blocked.get().message(), lastAccepted, lastSourceEvent);
        }
        if (!registrar.isConsuming(layer)) {
            return new LayerFeedStatus(layer, LayerFeedStatus.State.DISCONNECTED, "Consumer is not connected",
                    lastAccepted, lastSourceEvent);
        }
        if (lastAccepted == null) {
            return new LayerFeedStatus(layer, LayerFeedStatus.State.MISSING, "No accepted message yet", null, null);
        }
        Duration maxAge = properties.binding(layer).freshnessMaxAge();
        Instant reference = lastSourceEvent != null ? lastSourceEvent : lastAccepted;
        if (reference.isBefore(clock.instant().minus(maxAge))) {
            return new LayerFeedStatus(layer, LayerFeedStatus.State.STALE,
                    "Latest accepted data is older than " + maxAge, lastAccepted, lastSourceEvent);
        }
        return new LayerFeedStatus(layer, LayerFeedStatus.State.FRESH, "Receiving current data", lastAccepted,
                lastSourceEvent);
    }
}
