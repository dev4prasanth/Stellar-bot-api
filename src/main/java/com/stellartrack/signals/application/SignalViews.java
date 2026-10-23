package com.stellartrack.signals.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only API views of the race-signal read model. Absent data is null, never a placeholder value. */
public final class SignalViews {

    private SignalViews() {
    }

    /** Whether a layer's message for a race has arrived, is still awaited, or is never produced (e.g. race 1). */
    public enum LayerState {
        RECEIVED,
        WAITING,
        NOT_EXPECTED
    }

    /**
     * @param lastRaceWinner   winning runner of {@code lastRaceProcessed}, from Layer 2's card context (0: none)
     * @param contextUpdatedAt Layer 2 timestamp of the message that last updated the card context
     */
    public record CardSummary(String cardId, String venueId, String betfairVenue, LocalDate raceDate,
            Integer totalRaces, int racesSeen, long layer1Races, long layer2Races, long layer3Races,
            List<String> inPlanets, List<String> outPlanets, String scenario, Integer currentOutStreak,
            Integer lastRaceProcessed, Integer lastRaceWinner, Instant contextUpdatedAt, Instant updatedAt) {
    }

    public record RaceSummary(int raceNumber, LayerState layer1, LayerState layer2, LayerState layer3,
            Integer layer1Revision, List<String> layer3Sources, Integer napHorseNumber) {
    }

    public record CardDetail(CardSummary card, List<RaceSummary> races) {
    }

    /** Where a record came from: its receipt (see Settings → Integrations) and when it arrived. */
    public record Trace(UUID receiptId, UUID recordId, Instant receivedAt, Instant sourceEventAt) {
    }

    public record Layer1Runner(int horseNumber, int choice, Integer planetScore, BigDecimal odds, String planet,
            boolean isFinal) {
    }

    public record Layer1Section(LayerState status, Integer revision, Trace trace, List<Layer1Runner> runners) {
    }

    public record PlanetState(String planet, String stateCode, String stateName, Integer strengthScore,
            String authorityLevel, String momentum, Integer winCount, Integer top4Count, Integer absenceDuration,
            Integer racesSinceLastWin, Object triggeringRules, Object contextFlags, Object positionTrend,
            String rulesetVersion, Instant sourceEventAt) {
    }

    /** Planetary states after race {@code inputRaceNumber} (N − 1): the states Layer 3 used for race N. */
    public record Layer2Section(LayerState status, Integer inputRaceNumber, Trace trace, List<PlanetState> states) {
    }

    /**
     * One runner across the layers.
     *
     * @param rankChange     Layer 1 choice − Layer 3 final rank (positive: Layer 3 moved the runner up)
     * @param betfairMapping MAPPED, or why the runner has no Betfair selection
     */
    public record MergedRunner(int horseNumber, String planet, Integer layer1Choice, Boolean layer1Final,
            Integer finalRank, Integer rankChange, BigDecimal confidenceScore, BigDecimal selectionConfidenceScore,
            boolean nap, boolean top4, BigDecimal odds, List<String> rationaleTags, Long selectionId,
            String betfairMapping) {
    }

    public record Ranking(String source, String rerankPolicyVersion, Instant generatedAt, Integer napHorseNumber,
            Trace trace, List<MergedRunner> runners) {
    }

    public record Layer3Section(LayerState status, List<Ranking> rankings) {
    }

    public record Mapping(String status, String reason, String betfairVenue, String marketId) {
    }

    public record RaceDetail(String cardId, String venueId, String betfairVenue, LocalDate raceDate, int raceNumber,
            Integer totalRaces, List<String> inPlanets, List<String> outPlanets, String scenario,
            Layer1Section layer1, Layer2Section layer2, Layer3Section layer3, Mapping mapping,
            Map<String, Trace> trace) {
    }
}
