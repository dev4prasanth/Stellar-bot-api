package com.stellartrack.messaging.contract.layer1;

import com.stellartrack.messaging.contract.LayerMessageValidator;
import com.stellartrack.messaging.contract.PayloadFields;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Mirrors stellartrackbackend's class-validator rules on {@code RacePredictionDto}, plus the card id format. */
public class Layer1PredictionValidator implements LayerMessageValidator<Layer1Prediction> {

    @Override
    public List<String> validate(Layer1Prediction p) {
        List<String> violations = new ArrayList<>();
        String[] card = PayloadFields.splitCardId(p.cardId()).orElse(null);
        if (card == null) {
            violations.add("cardId must be {venueId}-{yyyy-MM-dd}");
        }
        if (p.raceNumber() == null || p.raceNumber() < 2) {
            violations.add("raceNumber must be an integer >= 2");
        }
        if (p.raceDate() == null) {
            violations.add("raceDate is required");
        } else {
            try {
                LocalDate.parse(p.raceDate());
                if (card != null && !card[1].equals(p.raceDate())) {
                    violations.add("raceDate does not match the date in cardId");
                }
            } catch (DateTimeParseException e) {
                violations.add("raceDate must be yyyy-MM-dd");
            }
        }
        if (p.finalPrediction() == null || p.finalPrediction().isEmpty()) {
            violations.add("finalPrediction must contain at least one runner");
        }
        Set<Integer> horses = new HashSet<>();
        Set<Integer> choices = new HashSet<>();
        List<Layer1Prediction.Runner> all = new ArrayList<>(p.finalPrediction() == null ? List.of() : p.finalPrediction());
        all.addAll(p.otherRunners());
        for (Layer1Prediction.Runner runner : all) {
            if (runner.horseNumber() == null || runner.horseNumber() < 1) {
                violations.add("horseNumber must be an integer >= 1");
            } else if (!horses.add(runner.horseNumber())) {
                violations.add("horseNumber " + runner.horseNumber() + " appears more than once");
            }
            if (runner.choice() == null || runner.choice() < 1) {
                violations.add("choice must be an integer >= 1");
            } else if (!choices.add(runner.choice())) {
                violations.add("choice " + runner.choice() + " appears more than once");
            }
            if (runner.planetScore() == null) {
                violations.add("planetScore must be an integer");
            }
        }
        if (p.odds() == null) {
            violations.add("odds is required");
        } else {
            for (Layer1Prediction.Odds odds : p.odds()) {
                if (odds.horseNumber() == null) {
                    violations.add("odds.horseNumber must be an integer");
                }
                // null (race-hit replay tool) and 0 (Layer 1) both mean "price unknown"
                if (odds.odds() != null && odds.odds().compareTo(BigDecimal.ZERO) < 0) {
                    violations.add("odds.odds must be >= 0");
                }
            }
        }
        return violations.stream().distinct().toList();
    }
}
