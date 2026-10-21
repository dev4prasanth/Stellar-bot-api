package com.stellartrack.messaging.contract.layer3;

import com.stellartrack.messaging.contract.LayerMessageValidator;
import com.stellartrack.messaging.contract.PayloadFields;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Layer3RankingValidator implements LayerMessageValidator<Layer3Ranking> {

    @Override
    public List<String> validate(Layer3Ranking p) {
        List<String> violations = new ArrayList<>();
        if (PayloadFields.splitCardId(p.cardId()).isEmpty()) {
            violations.add("cardId must be {venueId}-{yyyy-MM-dd}");
        }
        if (p.raceNumber() == null || p.raceNumber() < 1) {
            violations.add("raceNumber must be an integer >= 1");
        }
        if (p.source() == null || !Layer3Ranking.SOURCES.contains(p.source())) {
            violations.add("source must be one of " + Layer3Ranking.SOURCES);
        }
        if (p.generatedAt() == null) {
            violations.add("generatedAt is required");
        }
        if (p.rankings() == null || p.rankings().isEmpty()) {
            violations.add("rankings must contain at least one runner");
        } else {
            Set<Integer> ranks = new HashSet<>();
            for (Layer3Ranking.Ranking ranking : p.rankings()) {
                if (ranking.finalRank() == null || ranking.finalRank() < 1) {
                    violations.add("finalRank must be an integer >= 1");
                } else if (!ranks.add(ranking.finalRank())) {
                    violations.add("finalRank " + ranking.finalRank() + " appears more than once");
                }
                if (ranking.horseNumber() == null || ranking.horseNumber() < 1) {
                    violations.add("horseNumber must be an integer >= 1");
                }
            }
        }
        return violations.stream().distinct().toList();
    }
}
