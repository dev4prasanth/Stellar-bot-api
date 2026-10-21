package com.stellartrack.watch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.market.domain.MarketResult;
import com.stellartrack.watch.domain.WatchSettlementCalculator.BetToSettle;
import com.stellartrack.watch.domain.WatchSettlementCalculator.Result;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WatchSettlementCalculatorTest {

    private static final Instant MATCHED = Instant.parse("2026-10-14T13:00:00Z");

    private static BetToSettle bet(BetSide side, MarketType type, String matched, String avgPrice, String requested,
            String requestedPrice) {
        return new BetToSettle(side, type, 7L, matched == null ? BigDecimal.ZERO : new BigDecimal(matched),
                avgPrice == null ? null : new BigDecimal(avgPrice), new BigDecimal(requested), new BigDecimal(requestedPrice),
                matched == null ? null : MATCHED, MATCHED);
    }

    private static Map.Entry<Long, MarketResult.RunnerResult> runner(long id, String status) {
        return removed(id, status, null, null);
    }

    private static Map.Entry<Long, MarketResult.RunnerResult> removed(long id, String status, String factor, Instant at) {
        return Map.entry(id, new MarketResult.RunnerResult(status, factor == null ? null : new BigDecimal(factor), at));
    }

    @SafeVarargs
    private static MarketResult result(MarketType type, int winners, Map.Entry<Long, MarketResult.RunnerResult>... runners) {
        Map<Long, MarketResult.RunnerResult> map = new LinkedHashMap<>();
        for (Map.Entry<Long, MarketResult.RunnerResult> r : runners) {
            map.put(r.getKey(), r.getValue());
        }
        return new MarketResult("1.1", type, winners, map, MATCHED.plusSeconds(3600));
    }

    @Test
    void backWinnerAndLoser() {
        Result won = WatchSettlementCalculator.settle(bet(BetSide.BACK, MarketType.WIN, "40", "5.42", "40", "5.5"),
                result(MarketType.WIN, 1, runner(7, "WINNER"), runner(8, "LOSER")));
        assertThat(won.result()).isEqualTo(ResultStatus.WON);
        assertThat(won.grossProfit()).isEqualByComparingTo("176.80");
        assertThat(won.bestCaseGrossProfit()).isEqualByComparingTo("180.00");

        Result lost = WatchSettlementCalculator.settle(bet(BetSide.BACK, MarketType.WIN, "25", "3.15", "25", "3.2"),
                result(MarketType.WIN, 1, runner(7, "LOSER"), runner(8, "WINNER")));
        assertThat(lost.result()).isEqualTo(ResultStatus.LOST);
        assertThat(lost.grossProfit()).isEqualByComparingTo("-25.00");
    }

    @Test
    void layWinsWhenTheRunnerLosesAndLosesTheLiabilityWhenItWins() {
        Result layWon = WatchSettlementCalculator.settle(bet(BetSide.LAY, MarketType.WIN, "30", "4.0", "30", "4.0"),
                result(MarketType.WIN, 1, runner(7, "LOSER"), runner(8, "WINNER")));
        assertThat(layWon.result()).isEqualTo(ResultStatus.WON);
        assertThat(layWon.grossProfit()).isEqualByComparingTo("30.00");

        Result layLost = WatchSettlementCalculator.settle(bet(BetSide.LAY, MarketType.WIN, "30", "4.0", "30", "4.0"),
                result(MarketType.WIN, 1, runner(7, "WINNER"), runner(8, "LOSER")));
        assertThat(layLost.result()).isEqualTo(ResultStatus.LOST);
        assertThat(layLost.grossProfit()).isEqualByComparingTo("-90.00");
    }

    @Test
    void placeMarketUsesWinnerStatusForPlacedRunners() {
        Result placed = WatchSettlementCalculator.settle(bet(BetSide.BACK, MarketType.PLACE, "10", "2.0", "10", "2.0"),
                result(MarketType.PLACE, 3, runner(7, "WINNER"), runner(8, "WINNER"), runner(9, "WINNER"), runner(10, "LOSER")));
        assertThat(placed.result()).isEqualTo(ResultStatus.WON);
        assertThat(placed.grossProfit()).isEqualByComparingTo("10.00");
    }

    @Test
    void unmatchedBetHasNoProfitButKeepsABestCase() {
        Result result = WatchSettlementCalculator.settle(bet(BetSide.BACK, MarketType.WIN, null, null, "10", "6.0"),
                result(MarketType.WIN, 1, runner(7, "WINNER")));
        assertThat(result.result()).isEqualTo(ResultStatus.NOT_MATCHED);
        assertThat(result.grossProfit()).isEqualByComparingTo("0");
        assertThat(result.bestCaseGrossProfit()).isEqualByComparingTo("50.00");
    }

    @Test
    void nonRunnerIsVoid() {
        Result result = WatchSettlementCalculator.settle(bet(BetSide.BACK, MarketType.WIN, "10", "6.0", "10", "6.0"),
                result(MarketType.WIN, 1, runner(7, "REMOVED"), runner(8, "WINNER")));
        assertThat(result.result()).isEqualTo(ResultStatus.VOID);
        assertThat(result.grossProfit()).isEqualByComparingTo("0");
    }

    @Test
    void laterNonRunnerReducesTheMatchedPrice() {
        var removedLater = removed(9, "REMOVED", "25", MATCHED.plusSeconds(600));
        var removedBefore = removed(10, "REMOVED", "10", MATCHED.minusSeconds(600));
        var tiny = removed(11, "REMOVED", "1.5", MATCHED.plusSeconds(600));
        Result result = WatchSettlementCalculator.settle(bet(BetSide.BACK, MarketType.WIN, "10", "10.0", "10", "10.0"),
                result(MarketType.WIN, 1, runner(7, "WINNER"), removedLater, removedBefore, tiny));
        assertThat(result.reductionFactor()).isEqualByComparingTo("25");
        assertThat(result.settledPrice()).isEqualByComparingTo("7.5");
        assertThat(result.grossProfit()).isEqualByComparingTo("65.00");
    }

    @Test
    void reductionWithoutRemovalTimeIsUnresolved() {
        var noTime = removed(9, "REMOVED", "20", null);
        Result result = WatchSettlementCalculator.settle(bet(BetSide.BACK, MarketType.WIN, "10", "10.0", "10", "10.0"),
                result(MarketType.WIN, 1, runner(7, "WINNER"), noTime));
        assertThat(result.result()).isEqualTo(ResultStatus.UNRESOLVED);
    }

    @Test
    void winMarketDeadHeatSplitsTheStake() {
        Result back = WatchSettlementCalculator.settle(bet(BetSide.BACK, MarketType.WIN, "10", "5.0", "10", "5.0"),
                result(MarketType.WIN, 1, runner(7, "WINNER"), runner(8, "WINNER"), runner(9, "LOSER")));
        assertThat(back.deadHeatDivisor()).isEqualTo(2);
        // Half the stake wins at 5.0 (+20), half loses (−5).
        assertThat(back.grossProfit()).isEqualByComparingTo("15.00");

        Result lay = WatchSettlementCalculator.settle(bet(BetSide.LAY, MarketType.WIN, "10", "5.0", "10", "5.0"),
                result(MarketType.WIN, 1, runner(7, "WINNER"), runner(8, "WINNER"), runner(9, "LOSER")));
        assertThat(lay.grossProfit()).isEqualByComparingTo("-15.00");
        assertThat(lay.result()).isEqualTo(ResultStatus.LOST);
    }

    @Test
    void placeMarketDeadHeatOnAWinnerIsUnresolved() {
        Result result = WatchSettlementCalculator.settle(bet(BetSide.BACK, MarketType.PLACE, "10", "2.0", "10", "2.0"),
                result(MarketType.PLACE, 2, runner(7, "WINNER"), runner(8, "WINNER"), runner(9, "WINNER")));
        assertThat(result.result()).isEqualTo(ResultStatus.UNRESOLVED);
    }

    @Test
    void unknownStatusIsUnresolved() {
        Result result = WatchSettlementCalculator.settle(bet(BetSide.BACK, MarketType.WIN, "10", "5.0", "10", "5.0"),
                result(MarketType.WIN, 1, runner(7, "HIDDEN")));
        assertThat(result.result()).isEqualTo(ResultStatus.UNRESOLVED);
    }

    private static BetToSettle eachWay(String size, String price) {
        return new BetToSettle(BetSide.BACK, MarketType.EACH_WAY, 7L, new BigDecimal(size), new BigDecimal(price),
                new BigDecimal(size), new BigDecimal(price), MATCHED, MATCHED, new BigDecimal("5"));
    }

    @Test
    void eachWayWinnerWinsBothPartsPlacedWinsThePlacePartLoserLosesBoth() {
        // 10 each way at 6.0, 1/5 odds for 3 places: place part pays at 2.0.
        Result won = WatchSettlementCalculator.settle(eachWay("10", "6.0"),
                result(MarketType.EACH_WAY, 3, runner(7, "WINNER"), runner(8, "PLACED"), runner(9, "LOSER")));
        assertThat(won.result()).isEqualTo(ResultStatus.WON);
        assertThat(won.grossProfit()).isEqualByComparingTo("60.00");

        Result placed = WatchSettlementCalculator.settle(eachWay("10", "11.0"),
                result(MarketType.EACH_WAY, 3, runner(8, "WINNER"), runner(7, "PLACED"), runner(9, "LOSER")));
        assertThat(placed.result()).isEqualTo(ResultStatus.WON);
        assertThat(placed.grossProfit()).isEqualByComparingTo("10.00");

        Result lost = WatchSettlementCalculator.settle(eachWay("10", "6.0"),
                result(MarketType.EACH_WAY, 3, runner(8, "WINNER"), runner(9, "PLACED"), runner(7, "LOSER")));
        assertThat(lost.result()).isEqualTo(ResultStatus.LOST);
        assertThat(lost.grossProfit()).isEqualByComparingTo("-20.00");
    }

    @Test
    void eachWayWithoutPlaceTermsOrWithADeadHeatIsUnresolved() {
        Result noTerms = WatchSettlementCalculator.settle(new BetToSettle(BetSide.BACK, MarketType.EACH_WAY, 7L,
                        new BigDecimal("10"), new BigDecimal("6.0"), new BigDecimal("10"), new BigDecimal("6.0"), MATCHED, MATCHED),
                result(MarketType.EACH_WAY, 3, runner(7, "WINNER")));
        assertThat(noTerms.result()).isEqualTo(ResultStatus.UNRESOLVED);

        Result deadHeat = WatchSettlementCalculator.settle(eachWay("10", "6.0"),
                result(MarketType.EACH_WAY, 2, runner(8, "WINNER"), runner(7, "PLACED"), runner(9, "PLACED")));
        assertThat(deadHeat.result()).isEqualTo(ResultStatus.UNRESOLVED);
    }
}
