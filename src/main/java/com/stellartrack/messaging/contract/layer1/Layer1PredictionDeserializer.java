package com.stellartrack.messaging.contract.layer1;

import static com.stellartrack.messaging.contract.PayloadFields.array;
import static com.stellartrack.messaging.contract.PayloadFields.decimal;
import static com.stellartrack.messaging.contract.PayloadFields.integer;
import static com.stellartrack.messaging.contract.PayloadFields.text;

import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.messaging.contract.LayerMessageDeserializer;
import com.stellartrack.messaging.contract.PayloadFields;
import com.stellartrack.messaging.domain.InboundMessage;
import java.util.List;
import tools.jackson.databind.JsonNode;

public class Layer1PredictionDeserializer implements LayerMessageDeserializer<Layer1Prediction> {

    private final JsonCodec json;

    public Layer1PredictionDeserializer(JsonCodec json) {
        this.json = json;
    }

    @Override
    public Layer1Prediction deserialize(InboundMessage message) {
        JsonNode root = PayloadFields.readObject(json, message);
        return new Layer1Prediction(text(root, "cardId"), integer(root, "raceNumber"), text(root, "raceDate"),
                runners(array(root, "finalPrediction")), runners(array(root, "otherRunners")), odds(array(root, "odds")));
    }

    private static List<Layer1Prediction.Runner> runners(List<JsonNode> items) {
        return items == null ? null : items.stream()
                .map(n -> new Layer1Prediction.Runner(integer(n, "horseNumber"), integer(n, "choice"), integer(n, "planetScore")))
                .toList();
    }

    private static List<Layer1Prediction.Odds> odds(List<JsonNode> items) {
        return items == null ? null : items.stream()
                .map(n -> new Layer1Prediction.Odds(integer(n, "horseNumber"), decimal(n, "odds")))
                .toList();
    }
}
