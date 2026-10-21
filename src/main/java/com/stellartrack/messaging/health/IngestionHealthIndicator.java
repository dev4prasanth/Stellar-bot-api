package com.stellartrack.messaging.health;

import com.stellartrack.messaging.application.LayerFeedMonitor;
import com.stellartrack.messaging.application.LayerFeedStatus;
import com.stellartrack.messaging.domain.Layer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator view of the three consumers. A layer that is blocked or disabled by design does not make the
 * application unhealthy; an eligible consumer that has lost its broker connection does.
 */
@Component("ingestion")
public class IngestionHealthIndicator implements HealthIndicator {

    private final LayerFeedMonitor monitor;

    public IngestionHealthIndicator(LayerFeedMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean anyDisconnected = false;
        for (Layer layer : Layer.values()) {
            LayerFeedStatus status = monitor.status(layer);
            details.put(layer.name(), Map.of("state", status.state().name(), "reason", status.reason()));
            anyDisconnected |= status.state() == LayerFeedStatus.State.DISCONNECTED;
        }
        return (anyDisconnected ? Health.down() : Health.up()).withDetails(details).build();
    }
}
