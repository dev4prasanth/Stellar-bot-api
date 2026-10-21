package com.stellartrack.market.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.market.domain.MarketPrices;
import com.stellartrack.market.domain.MarketSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RunnerFilterEvaluatorTest {

    private final RunnerFilterEvaluator evaluator = new RunnerFilterEvaluator();

    private static MarketPrices.RunnerPrices runner(long id, String status, String back, String lay) {
        return new MarketPrices.RunnerPrices(id, status, null, null, null,
                back == null ? List.of() : List.of(new MarketPrices.PriceLevel(new BigDecimal(back), new BigDecimal("50"))),
                lay == null ? List.of() : List.of(new MarketPrices.PriceLevel(new BigDecimal(lay), new BigDecimal("30"))),
                null);
    }

    private static final MarketSummary MARKET = new MarketSummary("1.1", "2m Hcap", MarketType.WIN,
            Instant.parse("2026-10-12T14:00:00Z"), "e1", "Ascot", "Ascot", "GB", "Flat", null, List.of(
                    new MarketSummary.RunnerInfo(1, "Alpha", BigDecimal.ZERO, 1, Map.of("AGE", "4", "OFFICIAL_RATING", "88")),
                    new MarketSummary.RunnerInfo(2, "Bravo", BigDecimal.ZERO, 2, Map.of("AGE", "6")),
                    new MarketSummary.RunnerInfo(3, "Charlie", BigDecimal.ZERO, 3, Map.of())));

    private static final MarketPrices PRICES = new MarketPrices("1.1", "OPEN", false, false, 0, 1, 3,
            new BigDecimal("12000"), 5L, Instant.now(), MarketPrices.PriceSource.POLL, List.of(
                    runner(1, "ACTIVE", "3.5", "3.6"), runner(2, "ACTIVE", "2.2", "2.24"), runner(3, "REMOVED", null, null)));

    private static RunnerFilterCriteria criteria(NumericRange odds, NumericRange rank, NumericRange age, NumericRange rating,
            Set<String> classes) {
        return new RunnerFilterCriteria(null, rank, odds, null, classes, age, null, null, null, null, null, rating, null,
                null, null, null, null);
    }

    @Test
    void ranksByBestBackPriceAmongActiveRunners() {
        assertThat(evaluator.priceRanks(PRICES)).containsEntry(2L, 1).containsEntry(1L, 2).doesNotContainKey(3L);
    }

    @Test
    void appliesNumericRulesWithExplanations() {
        var outcomes = evaluator.evaluate(MARKET, PRICES,
                criteria(new NumericRange(new BigDecimal("3"), new BigDecimal("10")), null, null, null, null), BetSide.BACK);
        assertThat(outcomes.get(0).eligible()).isTrue();
        assertThat(outcomes.get(1).eligible()).isFalse();
        assertThat(outcomes.get(1).failures().getFirst().explanation()).contains("outside");
    }

    @Test
    void layUsesLayPrices() {
        var outcomes = evaluator.evaluate(MARKET, PRICES,
                criteria(new NumericRange(null, new BigDecimal("2.22")), null, null, null, null), BetSide.LAY);
        assertThat(outcomes.get(1).eligible()).isFalse(); // best lay 2.24 > 2.22
    }

    @Test
    void missingDataNeverPasses() {
        var outcomes = evaluator.evaluate(MARKET, PRICES,
                criteria(null, null, null, new NumericRange(new BigDecimal("80"), null), null), BetSide.BACK);
        assertThat(outcomes.get(0).eligible()).isTrue();
        FilterOutcome bravo = outcomes.get(1);
        assertThat(bravo.eligible()).isFalse();
        assertThat(bravo.failures()).anyMatch(r -> r.status() == FilterOutcome.Status.DATA_UNAVAILABLE);
    }

    @Test
    void fieldWithoutVerifiedSourceAlwaysFailsWhenActive() {
        var outcomes = evaluator.evaluate(MARKET, PRICES, criteria(null, null, null, null, Set.of("Class 2")), BetSide.BACK);
        assertThat(outcomes).noneMatch(FilterOutcome::eligible);
        assertThat(outcomes.get(0).failures().getFirst().explanation()).contains("no verified data source");
    }

    @Test
    void removedRunnerIsNeverEligible() {
        var outcomes = evaluator.evaluate(MARKET, PRICES, RunnerFilterCriteria.none(), BetSide.BACK);
        assertThat(outcomes.get(2).eligible()).isFalse();
        assertThat(outcomes.get(0).eligible()).isTrue();
    }
}
