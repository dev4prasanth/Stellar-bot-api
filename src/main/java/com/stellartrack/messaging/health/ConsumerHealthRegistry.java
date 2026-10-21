package com.stellartrack.messaging.health;

import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.messaging.domain.ReceiptStatus;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * In-process runtime health per layer consumer (connection state, counters since start, last processing time).
 * Durable facts such as "last accepted message" are read from message_receipt so they survive restarts.
 */
@Component
public class ConsumerHealthRegistry {

    public enum ConsumerState {
        NOT_STARTED,
        RUNNING,
        STOPPED,
        FAILED
    }

    public record Snapshot(ConsumerState state, String stateDetail, Instant lastDeliveryAt, Long lastProcessingMs,
            Map<ReceiptStatus, Long> countsSinceStart, long retriesScheduled, long deadLettered) {
    }

    private static final class LayerStats {
        final AtomicReference<ConsumerState> state = new AtomicReference<>(ConsumerState.NOT_STARTED);
        final AtomicReference<String> stateDetail = new AtomicReference<>("");
        final AtomicReference<Instant> lastDeliveryAt = new AtomicReference<>();
        final AtomicReference<Long> lastProcessingMs = new AtomicReference<>();
        final Map<ReceiptStatus, AtomicLong> counts = new EnumMap<>(ReceiptStatus.class);
        final AtomicLong retries = new AtomicLong();
        final AtomicLong deadLettered = new AtomicLong();

        LayerStats() {
            for (ReceiptStatus status : ReceiptStatus.values()) {
                counts.put(status, new AtomicLong());
            }
        }
    }

    private final Map<Layer, LayerStats> stats = new EnumMap<>(Layer.class);
    private final AtomicReference<Boolean> brokerConnected = new AtomicReference<>(null);

    public ConsumerHealthRegistry() {
        for (Layer layer : Layer.values()) {
            stats.put(layer, new LayerStats());
        }
    }

    public void consumerState(Layer layer, ConsumerState state, String detail) {
        stats.get(layer).state.set(state);
        stats.get(layer).stateDetail.set(detail == null ? "" : detail);
    }

    public void delivered(Layer layer, Instant at) {
        stats.get(layer).lastDeliveryAt.set(at);
    }

    public void processed(Layer layer, ReceiptStatus status, long processingMs) {
        LayerStats s = stats.get(layer);
        s.counts.get(status).incrementAndGet();
        s.lastProcessingMs.set(processingMs);
    }

    public void retryScheduled(Layer layer) {
        stats.get(layer).retries.incrementAndGet();
    }

    public void deadLettered(Layer layer) {
        stats.get(layer).deadLettered.incrementAndGet();
    }

    public void brokerConnection(boolean connected) {
        brokerConnected.set(connected);
    }

    /** {@code null} when no connection has been attempted (no layer enabled). */
    public Boolean brokerConnected() {
        return brokerConnected.get();
    }

    public Snapshot snapshot(Layer layer) {
        LayerStats s = stats.get(layer);
        Map<ReceiptStatus, Long> counts = new EnumMap<>(ReceiptStatus.class);
        s.counts.forEach((status, count) -> counts.put(status, count.get()));
        return new Snapshot(s.state.get(), s.stateDetail.get(), s.lastDeliveryAt.get(), s.lastProcessingMs.get(),
                counts, s.retries.get(), s.deadLettered.get());
    }
}
