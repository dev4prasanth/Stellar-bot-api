package com.stellartrack.messaging.listener;

import com.stellartrack.messaging.application.IngestionPipeline;
import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.messaging.health.ConsumerHealthRegistry;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Receives Layer 2 planetary-state messages from the queue configured in {@code stellartrack.messaging.layers.LAYER2}. */
@Component
public class Layer2PlanetaryStateListener extends AbstractLayerListener {

    public Layer2PlanetaryStateListener(IngestionPipeline pipeline, RedeliveryPublisher redelivery,
            BackoffPolicy backoff, ConsumerHealthRegistry health, Clock clock) {
        super(pipeline, redelivery, backoff, health, clock);
    }

    @Override
    public Layer layer() {
        return Layer.LAYER2;
    }
}
