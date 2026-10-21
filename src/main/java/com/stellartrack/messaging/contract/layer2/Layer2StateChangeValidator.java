package com.stellartrack.messaging.contract.layer2;

import com.stellartrack.messaging.contract.LayerMessageValidator;
import com.stellartrack.messaging.contract.PayloadFields;
import com.stellartrack.messaging.domain.Planet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Layer2StateChangeValidator implements LayerMessageValidator<Layer2StateChange> {

    @Override
    public List<String> validate(Layer2StateChange p) {
        List<String> violations = new ArrayList<>();
        if (PayloadFields.splitCardId(p.cardId()).isEmpty()) {
            violations.add("cardId must be {venueId}-{yyyy-MM-dd}");
        }
        if (p.raceNumber() == null || p.raceNumber() < 1) {
            violations.add("raceNumber must be an integer >= 1");
        }
        if (p.timestamp() == null) {
            violations.add("timestamp is required");
        }
        if (p.states() == null || p.states().isEmpty()) {
            violations.add("states must contain at least one planet");
        } else {
            for (Map.Entry<String, Layer2StateChange.PlanetaryState> entry : p.states().entrySet()) {
                if (!Planet.isKnown(entry.getKey())) {
                    violations.add("states contains unknown planet " + entry.getKey());
                }
                Integer strength = entry.getValue().strengthScore();
                if (strength == null || strength < 0 || strength > 100) {
                    violations.add("strengthScore of " + entry.getKey() + " must be 0-100");
                }
            }
        }
        return violations;
    }
}
