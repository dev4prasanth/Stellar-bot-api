package com.stellartrack.messaging.config;

import com.stellartrack.messaging.domain.Layer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Property-driven RabbitMQ bindings. Queue names stay empty until configured; nothing here guesses exchanges or
 * routing keys. The Layer 1/2/3 producers publish to a shared topic exchange; this application subscribes with
 * its <em>own</em> queues bound to that exchange so it never competes with the producers' consumers.
 */
@ConfigurationProperties(prefix = "stellartrack.messaging")
public record MessagingProperties(
        boolean declareOwnedTopology,
        int maxPayloadBytes,
        boolean storeRawPayload,
        Retry retry,
        String retryExchange,
        String deadLetterExchange,
        Map<Layer, LayerBinding> layers) {

    public MessagingProperties {
        Map<Layer, LayerBinding> copy = new EnumMap<>(Layer.class);
        if (layers != null) {
            copy.putAll(layers);
        }
        for (Layer layer : Layer.values()) {
            copy.putIfAbsent(layer, LayerBinding.disabled());
        }
        layers = Map.copyOf(copy);
    }

    public LayerBinding binding(Layer layer) {
        return layers.get(layer);
    }

    public String retryQueue(Layer layer) {
        return "stellartrack." + layer.token() + ".retry";
    }

    public String deadLetterQueue(Layer layer) {
        return "stellartrack." + layer.token() + ".dlq";
    }

    public record Retry(int maxAttempts, Duration initialInterval, double multiplier, Duration maxInterval,
            double jitterRatio) {
    }

    /**
     * @param queue                the queue this application consumes; with {@code declareSubscription} it is an
     *                             application-owned queue (prefix {@code stellartrack.}) bound to {@code exchange}
     * @param exchange             the producers' existing exchange (never redeclared with different arguments)
     * @param routingKeys          routing keys bound to {@code queue} on {@code exchange}
     * @param declareSubscription  declare {@code queue} and its bindings before the consumer starts
     * @param rejectStale          mark records older than {@code freshnessMaxAge} as STALE (off for historical replays)
     */
    public record LayerBinding(boolean enabled, String queue, int concurrency, int prefetch, Duration freshnessMaxAge,
            String exchange, List<String> routingKeys, boolean declareSubscription,
            @DefaultValue("true") boolean rejectStale) {

        public LayerBinding {
            routingKeys = routingKeys == null ? List.of()
                    : routingKeys.stream().map(String::trim).filter(key -> !key.isEmpty()).toList();
            freshnessMaxAge = freshnessMaxAge == null ? Duration.ofMinutes(15) : freshnessMaxAge;
        }

        static LayerBinding disabled() {
            return new LayerBinding(false, null, 1, 10, Duration.ofMinutes(15), null, List.of(), false, true);
        }

        public boolean hasQueue() {
            return queue != null && !queue.isBlank() && !queue.startsWith("<");
        }

        public boolean hasExchange() {
            return exchange != null && !exchange.isBlank() && !exchange.startsWith("<");
        }
    }
}
