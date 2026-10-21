package com.stellartrack.messaging.contract.layer2;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Layer 2 {@code state-change} message ({@code StateChangePublisher}, a {@code HashMap} sent with Spring AMQP's
 * default Jackson 2 converter): planetary states after race {@code raceNumber}'s result, which Layer 3 uses to
 * rank race {@code raceNumber + 1}.
 *
 * @param states          keyed by planet name
 * @param raceCardContext the card's IN/OUT context; null when Layer 2 had none yet
 */
public record Layer2StateChange(String cardId, Integer raceNumber, Instant timestamp, Map<String, PlanetaryState> states,
        CardContext raceCardContext) {

    public record PlanetaryState(String cardId, Integer raceNumber, String planet, String stateCode, String stateName,
            Integer strengthScore, String authorityLevel, List<TrendEntry> positionTrend, String momentum,
            Integer absenceDuration, Integer winCount, Integer top4Count, Integer racesSinceLastWin,
            List<String> triggeringRules, Map<String, Object> contextFlags, String rulesetVersion, Instant timestamp) {
    }

    /** {@code hora} is an integer from Layer 2 but typed as text by Layer 3, so it is kept as text. */
    public record TrendEntry(List<Integer> runnerNumbers, List<Integer> positions, Integer raceNumber,
            LocalTime raceTime, String hora, String zone) {
    }

    public record CardContext(String cardId, List<String> inPlanets, List<String> outPlanets, String scenario,
            Integer currentOutStreak, Integer lastRaceProcessed, Integer lastRaceWinner, Integer totalRaces,
            Instant createdAt, Instant lastModified) {
    }
}
