package com.stellartrack.messaging.listener;

import com.stellartrack.messaging.application.IngestionPipeline;
import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.messaging.health.ConsumerHealthRegistry;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Receives Layer 1 prediction messages from the queue configured in {@code stellartrack.messaging.layers.LAYER1}. */
@Component
public class Layer1PredictionListener extends AbstractLayerListener {

    public Layer1PredictionListener(IngestionPipeline pipeline, RedeliveryPublisher redelivery, BackoffPolicy backoff,
            ConsumerHealthRegistry health, Clock clock) {
        super(pipeline, redelivery, backoff, health, clock);
    }

    @Override
    public Layer layer() {
        return Layer.LAYER1;
    }
}
