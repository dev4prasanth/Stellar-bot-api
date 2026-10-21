package com.stellartrack.messaging.contract.layer3;

import static com.stellartrack.messaging.contract.PayloadFields.array;
import static com.stellartrack.messaging.contract.PayloadFields.decimal;
import static com.stellartrack.messaging.contract.PayloadFields.flag;
import static com.stellartrack.messaging.contract.PayloadFields.instant;
import static com.stellartrack.messaging.contract.PayloadFields.integer;
import static com.stellartrack.messaging.contract.PayloadFields.strings;
import static com.stellartrack.messaging.contract.PayloadFields.text;

import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.messaging.contract.LayerMessageDeserializer;
import com.stellartrack.messaging.contract.PayloadFields;
import com.stellartrack.messaging.domain.InboundMessage;
import java.util.List;
import tools.jackson.databind.JsonNode;

public class Layer3RankingDeserializer implements LayerMessageDeserializer<Layer3Ranking> {

    private final JsonCodec json;

    public Layer3RankingDeserializer(JsonCodec json) {
        this.json = json;
    }

    @Override
    public Layer3Ranking deserialize(InboundMessage message) {
        JsonNode root = PayloadFields.readObject(json, message);
        List<JsonNode> rankings = array(root, "rankings");
        return new Layer3Ranking(text(root, "cardId"), integer(root, "raceNumber"), text(root, "source"),
                text(root, "rerankPolicyVersion"), instant(root, "generatedAt"),
                rankings == null ? null : rankings.stream().map(Layer3RankingDeserializer::ranking).toList());
    }

    private static Layer3Ranking.Ranking ranking(JsonNode n) {
        return new Layer3Ranking.Ranking(text(n, "horseId"), integer(n, "horseNumber"), integer(n, "finalRank"),
                decimal(n, "confidenceScore"), decimal(n, "selectionConfidenceScore"), strings(n, "rationaleTags"),
                flag(n, "top4", "isTop4"), flag(n, "nap", "isNap"), decimal(n, "odds"));
    }
}
