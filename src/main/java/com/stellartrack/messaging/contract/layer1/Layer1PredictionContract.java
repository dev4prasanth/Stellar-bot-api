package com.stellartrack.messaging.contract.layer1;

import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.messaging.contract.LayerContract;
import com.stellartrack.messaging.contract.PayloadFields;
import com.stellartrack.messaging.correlation.RaceMarketCorrelator;
import com.stellartrack.messaging.domain.InboundMessage;
import com.stellartrack.messaging.domain.Layer;
import java.util.Set;

/**
 * Contract {@code l1-prediction-v1}. Layer 1 sends no type header, so a delivery is selected by the producer's
 * routing key ({@code layer1.prediction}, or {@code layer1-predictions-queue} when the race-hit replay tool
 * publishes directly with its {@code com.kmss.racehitservice...} type header) and by the payload shape.
 */
public final class Layer1PredictionContract {

    public static final String VERSION = "l1-prediction-v1";
    static final Set<String> ROUTING_KEYS = Set.of("layer1.prediction", "layer1-predictions-queue");
    static final String REPLAY_TYPE_PREFIX = "com.kmss.racehitservice";

    private Layer1PredictionContract() {
    }

    public static LayerContract<Layer1Prediction> create(JsonCodec json, RaceMarketCorrelator correlator) {
        return new LayerContract<>(Layer.LAYER1, VERSION, message -> selects(json, message),
                new Layer1PredictionDeserializer(json), new Layer1PredictionValidator(),
                new Layer1PredictionMapper(correlator));
    }

    static boolean selects(JsonCodec json, InboundMessage message) {
        String typeId = message.header("__TypeId__");
        boolean producer = ROUTING_KEYS.contains(message.routingKey())
                || (typeId != null && typeId.startsWith(REPLAY_TYPE_PREFIX));
        // An unparseable body still belongs here so that it is reported as malformed rather than unsupported.
        return producer && PayloadFields.hasFields(json, message, "finalPrediction", "odds").orElse(true);
    }
}
