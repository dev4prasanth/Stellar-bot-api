package com.stellartrack.messaging.contract.layer2;

import static com.stellartrack.messaging.contract.PayloadFields.array;
import static com.stellartrack.messaging.contract.PayloadFields.instant;
import static com.stellartrack.messaging.contract.PayloadFields.integer;
import static com.stellartrack.messaging.contract.PayloadFields.integers;
import static com.stellartrack.messaging.contract.PayloadFields.localTime;
import static com.stellartrack.messaging.contract.PayloadFields.present;
import static com.stellartrack.messaging.contract.PayloadFields.strings;
import static com.stellartrack.messaging.contract.PayloadFields.text;

import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.messaging.contract.ContractViolationException;
import com.stellartrack.messaging.contract.LayerMessageDeserializer;
import com.stellartrack.messaging.contract.PayloadFields;
import com.stellartrack.messaging.domain.ErrorClass;
import com.stellartrack.messaging.domain.InboundMessage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public class Layer2StateChangeDeserializer implements LayerMessageDeserializer<Layer2StateChange> {

    private final JsonCodec json;

    public Layer2StateChangeDeserializer(JsonCodec json) {
        this.json = json;
    }

    @Override
    public Layer2StateChange deserialize(InboundMessage message) {
        JsonNode root = PayloadFields.readObject(json, message);
        JsonNode statesNode = root.get("states");
        Map<String, Layer2StateChange.PlanetaryState> states = null;
        if (present(statesNode)) {
            if (!statesNode.isObject()) {
                throw new ContractViolationException(ErrorClass.MALFORMED_PAYLOAD, "states must be an object");
            }
            states = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : statesNode.properties()) {
                states.put(entry.getKey(), state(entry.getValue()));
            }
        }
        JsonNode context = root.get("raceCardContext");
        return new Layer2StateChange(text(root, "cardId"), integer(root, "raceNumber"), instant(root, "timestamp"),
                states, present(context) && context.isObject() ? context(context) : null);
    }

    private Layer2StateChange.PlanetaryState state(JsonNode n) {
        List<JsonNode> trend = array(n, "positionTrend");
        return new Layer2StateChange.PlanetaryState(text(n, "cardId"), integer(n, "raceNumber"), text(n, "planet"),
                text(n, "stateCode"), text(n, "stateName"), integer(n, "strengthScore"), text(n, "authorityLevel"),
                trend == null ? List.of() : trend.stream().map(Layer2StateChangeDeserializer::trendEntry).toList(),
                text(n, "momentum"), integer(n, "absenceDuration"), integer(n, "winCount"), integer(n, "top4Count"),
                integer(n, "racesSinceLastWin"), strings(n, "triggeringRules"),
                PayloadFields.object(json, n, "contextFlags"), text(n, "rulesetVersion"), instant(n, "timestamp"));
    }

    private static Layer2StateChange.TrendEntry trendEntry(JsonNode n) {
        return new Layer2StateChange.TrendEntry(integers(n, "runnerNumbers"), integers(n, "positions"),
                integer(n, "raceNumber"), localTime(n, "raceTime"), text(n, "hora"), text(n, "zone"));
    }

    private static Layer2StateChange.CardContext context(JsonNode n) {
        return new Layer2StateChange.CardContext(text(n, "cardId"), strings(n, "inPlanets"), strings(n, "outPlanets"),
                text(n, "scenario"), integer(n, "currentOutStreak"), integer(n, "lastRaceProcessed"),
                integer(n, "lastRaceWinner"), integer(n, "totalRaces"), instant(n, "createdAt"),
                instant(n, "lastModified"));
    }
}
