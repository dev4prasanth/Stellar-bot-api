package com.stellartrack.decision.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.market.domain.MarketResult;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Post-race selection-outcome rules for rejected (and accepted) proposals. Test-only fixtures. */
class OutcomeClassifierTest {

    private static Optional<MarketResult> result(MarketType type, int winners, Map<Long, String> statuses) {
        Map<Long, MarketResult.RunnerResult> runners = new java.util.HashMap<>();
        statuses.forEach((id, status) -> runners.put(id, new MarketResult.RunnerResult(status, null)));
        return Optional.of(new MarketResult("1.1", type, winners, runners, Instant.parse("2026-10-01T15:00:00Z")));
    }

    @Test
    void backWinnerIsFavorableAndLayWinnerUnfavorable() {
        var market = result(MarketType.WIN, 1, Map.of(1L, "WINNER", 2L, "LOSER"));
        assertThat(OutcomeClassifier.classify(BetSide.BACK, MarketType.WIN, 1L, market).classification())
                .isEqualTo(OutcomeClassification.FAVORABLE);
        assertThat(OutcomeClassifier.classify(BetSide.LAY, MarketType.WIN, 1L, market).classification())
                .isEqualTo(OutcomeClassification.UNFAVORABLE);
    }

    @Test
    void losingHorseFavorsLay() {
        var market = result(MarketType.WIN, 1, Map.of(1L, "WINNER", 2L, "LOSER"));
        assertThat(OutcomeClassifier.classify(BetSide.LAY, MarketType.WIN, 2L, market).classification())
                .isEqualTo(OutcomeClassification.FAVORABLE);
        assertThat(OutcomeClassifier.classify(BetSide.BACK, MarketType.WIN, 2L, market).classification())
                .isEqualTo(OutcomeClassification.UNFAVORABLE);
    }

    @Test
    void placeMarketUsesPlaceResult() {
        // In a 3-place market the third-placed runner is a WINNER of the PLACE market.
        var place = result(MarketType.PLACE, 3, Map.of(1L, "WINNER", 2L, "WINNER", 3L, "WINNER", 4L, "LOSER"));
        assertThat(OutcomeClassifier.classify(BetSide.BACK, MarketType.PLACE, 3L, place).classification())
                .isEqualTo(OutcomeClassification.FAVORABLE);
        assertThat(OutcomeClassifier.classify(BetSide.LAY, MarketType.PLACE, 4L, place).classification())
                .isEqualTo(OutcomeClassification.FAVORABLE);
    }

    @Test
    void marketTypeMismatchIsNotEvaluable() {
        var win = result(MarketType.WIN, 1, Map.of(1L, "WINNER"));
        assertThat(OutcomeClassifier.classify(BetSide.BACK, MarketType.PLACE, 1L, win).classification())
                .isEqualTo(OutcomeClassification.NOT_EVALUABLE);
    }

    @Test
    void nonRunnerIsVoid() {
        var market = result(MarketType.WIN, 1, Map.of(1L, "WINNER", 5L, "REMOVED"));
        assertThat(OutcomeClassifier.classify(BetSide.BACK, MarketType.WIN, 5L, market).classification())
                .isEqualTo(OutcomeClassification.VOID);
    }

    @Test
    void missingResultIsNotEvaluableNeverAssumed() {
        assertThat(OutcomeClassifier.classify(BetSide.BACK, MarketType.WIN, 1L, Optional.empty()).classification())
                .isEqualTo(OutcomeClassification.NOT_EVALUABLE);
        var hidden = result(MarketType.WIN, 1, Map.of(1L, "HIDDEN"));
        assertThat(OutcomeClassifier.classify(BetSide.BACK, MarketType.WIN, 1L, hidden).classification())
                .isEqualTo(OutcomeClassification.NOT_EVALUABLE);
    }

    @Test
    void deadHeatIsFlaggedWithoutInferringPayout() {
        var deadHeat = result(MarketType.WIN, 1, Map.of(1L, "WINNER", 2L, "WINNER", 3L, "LOSER"));
        var classified = OutcomeClassifier.classify(BetSide.BACK, MarketType.WIN, 1L, deadHeat);
        assertThat(classified.classification()).isEqualTo(OutcomeClassification.FAVORABLE);
        assertThat(classified.deadHeat()).isTrue();
        assertThat(classified.evidence()).containsKey("note");
    }
}
