package com.stellartrack.messaging.contract.layer1;

import java.math.BigDecimal;
import java.util.List;

/**
 * Layer 1 {@code RacePredictionDto} as stellartrackbackend publishes it to {@code stellar-track-exchange} with
 * routing key {@code layer1.prediction}: plain JSON, no type header, message id or timestamp.
 *
 * @param finalPrediction usually the top 4; {@code choice} is 1..n
 * @param otherRunners    optional; {@code choice} continues after {@code finalPrediction}
 * @param odds            best bet365/bookmaker price per runner; {@code 0} means unknown
 */
public record Layer1Prediction(String cardId, Integer raceNumber, String raceDate, List<Runner> finalPrediction,
        List<Runner> otherRunners, List<Odds> odds) {

    public Layer1Prediction {
        finalPrediction = finalPrediction == null ? null : List.copyOf(finalPrediction);
        otherRunners = otherRunners == null ? List.of() : List.copyOf(otherRunners);
        odds = odds == null ? null : List.copyOf(odds);
    }

    /** @param planetScore Layer 1 {@code odPriority}, defaulting to 0 */
    public record Runner(Integer horseNumber, Integer choice, Integer planetScore) {
    }

    public record Odds(Integer horseNumber, BigDecimal odds) {
    }
}
