package com.stellartrack.watch.domain;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.common.money.MoneyPolicy;
import com.stellartrack.market.domain.MarketResult;
import com.stellartrack.staking.domain.BetMath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Settles a simulated bet the way the exchange would, rule version {@value #RULE_VERSION}, from the official
 * {@link MarketResult}. Gross profit only; commission is charged per market by {@link CommissionCalculator}.
 * <ul>
 *   <li>Nothing matched → NOT_MATCHED, no profit or loss.</li>
 *   <li>Own runner removed (non-runner) → VOID, stake returned.</li>
 *   <li>Non-runner reduction: runners removed <em>after</em> the bet was first matched with a reduction factor of at
 *   least {@value #MIN_REDUCTION_PERCENT}% reduce the matched price: {@code price × (1 − ΣR/100)}, never below 1.0.
 *   A qualifying removal without a removal time cannot be ordered against the bet → UNRESOLVED.</li>
 *   <li>WIN market dead heat: the stake on a winner is divided by the number of runners tied. A dead heat in a
 *   PLACE market cannot be attributed to a position from statuses alone → UNRESOLVED for winning runners.</li>
 *   <li>Win and loss amounts follow {@link BetMath}.</li>
 *   <li>EACH_WAY (BACK, stake per part): WINNER → win and place parts win; PLACED → the place part wins at
 *   {@code 1 + (p − 1)/d}, the win part loses; LOSER → both parts lose. A dead heat for a win or a place, or unknown
 *   place terms → UNRESOLVED.</li>
 *   <li>Any other runner status (HIDDEN, PLACED, missing) → UNRESOLVED.</li>
 * </ul>
 * The best case applies the same rules to the full requested size at the requested price.
 */
public final class WatchSettlementCalculator {

    public static final String RULE_VERSION = "WATCH_SETTLEMENT_V1";
    public static final BigDecimal MIN_REDUCTION_PERCENT = new BigDecimal("2.5");

    /** The bet to settle. {@code matchedAt} (first match) orders the bet against non-runner removals. */
    public record BetToSettle(BetSide side, MarketType marketType, long selectionId, BigDecimal sizeMatched,
            BigDecimal averagePriceMatched, BigDecimal requestedSize, BigDecimal requestedPrice, Instant matchedAt,
            Instant placedAt, BigDecimal eachWayDivisor) {

        public BetToSettle(BetSide side, MarketType marketType, long selectionId, BigDecimal sizeMatched,
                BigDecimal averagePriceMatched, BigDecimal requestedSize, BigDecimal requestedPrice, Instant matchedAt,
                Instant placedAt) {
            this(side, marketType, selectionId, sizeMatched, averagePriceMatched, requestedSize, requestedPrice, matchedAt,
                    placedAt, null);
        }
    }

    public record Result(ResultStatus result, String runnerStatus, BigDecimal reductionFactor, Integer deadHeatDivisor,
            BigDecimal settledPrice, BigDecimal grossProfit, BigDecimal bestCaseGrossProfit, Map<String, Object> evidence) {
    }

    private WatchSettlementCalculator() {
    }

    public static Result settle(BetToSettle bet, MarketResult market) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ruleVersion", RULE_VERSION);
        evidence.put("source", "Betfair runner statuses of the CLOSED market (captured by the market result job)");
        evidence.put("side", bet.side().name());
        evidence.put("marketType", bet.marketType().name());
        evidence.put("numberOfWinners", market.numberOfWinners());
        Optional<MarketResult.RunnerResult> ownResult = market.runner(bet.selectionId());
        if (ownResult.isEmpty() || ownResult.get().status() == null) {
            evidence.put("reason", "Runner result not available");
            return unresolved(evidence, null);
        }
        MarketResult.RunnerResult own = ownResult.get();
        evidence.put("runnerStatus", own.status());
        if (own.isRemoved()) {
            evidence.put("reason", "Runner removed (non-runner): stake returned");
            return new Result(ResultStatus.VOID, own.status(), null, null, null, MoneyPolicy.zero(), MoneyPolicy.zero(),
                    evidence);
        }
        Optional<BigDecimal> reduction = reduction(bet, market, evidence);
        if (reduction.isEmpty()) {
            return unresolved(evidence, own.status());
        }
        if (bet.marketType() == MarketType.EACH_WAY) {
            return settleEachWay(bet, market, own, reduction.get(), evidence);
        }
        if (!own.isWinner() && !own.isLoser()) {
            evidence.put("reason", "Status " + own.status() + " is not covered by rule version " + RULE_VERSION);
            return unresolved(evidence, own.status());
        }
        Integer divisor = null;
        if (market.isDeadHeat() && own.isWinner()) {
            if (bet.marketType() != MarketType.WIN || !Integer.valueOf(1).equals(market.numberOfWinners())) {
                evidence.put("reason", "Dead heat in a " + bet.marketType() + " market: the tied position cannot be "
                        + "determined from runner statuses");
                return unresolved(evidence, own.status());
            }
            divisor = (int) market.runners().values().stream().filter(MarketResult.RunnerResult::isWinner).count();
        }
        evidence.put("deadHeatDivisor", divisor);
        BigDecimal bestCasePrice = reduce(bet.requestedPrice(), reduction.get());
        BigDecimal bestCase = profit(bet.side(), own.isWinner(), bet.requestedSize(), bestCasePrice, divisor);
        evidence.put("bestCasePrice", bestCasePrice);
        if (bet.sizeMatched() == null || bet.sizeMatched().signum() <= 0) {
            evidence.put("reason", "Nothing was matched: no profit or loss");
            return new Result(ResultStatus.NOT_MATCHED, own.status(), reduction.get(), divisor, null, MoneyPolicy.zero(),
                    bestCase, evidence);
        }
        BigDecimal settledPrice = reduce(bet.averagePriceMatched(), reduction.get());
        evidence.put("settledPrice", settledPrice);
        boolean favorable = bet.side() == BetSide.BACK ? own.isWinner() : own.isLoser();
        return new Result(favorable ? ResultStatus.WON : ResultStatus.LOST, own.status(), reduction.get(), divisor,
                settledPrice, profit(bet.side(), own.isWinner(), bet.sizeMatched(), settledPrice, divisor), bestCase, evidence);
    }

    private static Result settleEachWay(BetToSettle bet, MarketResult market, MarketResult.RunnerResult own,
            BigDecimal reduction, Map<String, Object> evidence) {
        BigDecimal divisor = bet.eachWayDivisor();
        evidence.put("eachWayDivisor", divisor);
        if (bet.side() != BetSide.BACK || divisor == null || divisor.signum() <= 0) {
            evidence.put("reason", bet.side() != BetSide.BACK ? "Each-Way is settled for BACK bets only"
                    : "Each-Way place terms are not known");
            return unresolved(evidence, own.status());
        }
        if (!own.isWinner() && !own.isPlaced() && !own.isLoser()) {
            evidence.put("reason", "Status " + own.status() + " is not covered by rule version " + RULE_VERSION);
            return unresolved(evidence, own.status());
        }
        long winners = market.runners().values().stream().filter(MarketResult.RunnerResult::isWinner).count();
        long placed = winners + market.runners().values().stream().filter(MarketResult.RunnerResult::isPlaced).count();
        if ((own.isWinner() && winners > 1)
                || (!own.isLoser() && market.numberOfWinners() != null && placed > market.numberOfWinners())) {
            evidence.put("reason", "Dead heat in an Each-Way market: the reduced payout is not simulated");
            return unresolved(evidence, own.status());
        }
        BigDecimal bestCasePrice = reduce(bet.requestedPrice(), reduction);
        BigDecimal bestCase = eachWayProfit(own, bet.requestedSize(), bestCasePrice, divisor);
        evidence.put("bestCasePrice", bestCasePrice);
        if (bet.sizeMatched() == null || bet.sizeMatched().signum() <= 0) {
            evidence.put("reason", "Nothing was matched: no profit or loss");
            return new Result(ResultStatus.NOT_MATCHED, own.status(), reduction, null, null, MoneyPolicy.zero(), bestCase,
                    evidence);
        }
        BigDecimal settledPrice = reduce(bet.averagePriceMatched(), reduction);
        evidence.put("settledPrice", settledPrice);
        BigDecimal profit = eachWayProfit(own, bet.sizeMatched(), settledPrice, divisor);
        return new Result(profit.signum() >= 0 && !own.isLoser() ? ResultStatus.WON : ResultStatus.LOST, own.status(),
                reduction, null, settledPrice, profit, bestCase, evidence);
    }

    /** Gross profit of an Each-Way stake per part {@code size}: both parts win, only the place part, or neither. */
    static BigDecimal eachWayProfit(MarketResult.RunnerResult own, BigDecimal size, BigDecimal price, BigDecimal divisor) {
        if (own.isWinner()) {
            return BetMath.eachWayProfitIfWins(size, price, divisor);
        }
        if (own.isPlaced()) {
            return BetMath.eachWayProfitIfPlaced(size, price, divisor);
        }
        return BetMath.eachWayTotalStake(size).negate();
    }

    /** Total reduction factor (percent) of qualifying removals after the bet; empty when it cannot be determined. */
    private static Optional<BigDecimal> reduction(BetToSettle bet, MarketResult market, Map<String, Object> evidence) {
        Instant reference = bet.matchedAt() != null ? bet.matchedAt() : bet.placedAt();
        BigDecimal total = BigDecimal.ZERO;
        List<Map<String, Object>> applied = new ArrayList<>();
        for (Map.Entry<Long, MarketResult.RunnerResult> entry : market.runners().entrySet()) {
            MarketResult.RunnerResult r = entry.getValue();
            if (!r.isRemoved() || r.adjustmentFactor() == null || r.adjustmentFactor().compareTo(MIN_REDUCTION_PERCENT) < 0) {
                continue;
            }
            if (r.removalDate() == null) {
                evidence.put("reason", "Runner " + entry.getKey() + " was removed with a reduction factor but no removal "
                        + "time, so it cannot be ordered against the bet");
                return Optional.empty();
            }
            if (reference != null && r.removalDate().isAfter(reference)) {
                total = total.add(r.adjustmentFactor());
                applied.add(Map.of("selectionId", entry.getKey(), "reductionFactor", r.adjustmentFactor(),
                        "removalDate", r.removalDate().toString()));
            }
        }
        evidence.put("reductionsApplied", applied);
        return Optional.of(total);
    }

    /** Gross profit of {@code size} at {@code price}; a dead-heat divisor splits the stake on a winning runner. */
    static BigDecimal profit(BetSide side, boolean runnerWon, BigDecimal size, BigDecimal price, Integer deadHeatDivisor) {
        if (!runnerWon) {
            return side == BetSide.BACK ? BetMath.exposure(side, size, price).negate() : BetMath.grossProfitIfWins(side, size, price);
        }
        BigDecimal winningPart = deadHeatDivisor == null ? size
                : size.divide(BigDecimal.valueOf(deadHeatDivisor), MoneyPolicy.CALCULATION_SCALE, MoneyPolicy.AMOUNT_ROUNDING);
        BigDecimal losingPart = size.subtract(winningPart);
        return side == BetSide.BACK
                ? BetMath.backWinProfit(winningPart, price).subtract(MoneyPolicy.amount(losingPart))
                : BetMath.layLiability(winningPart, price).negate().add(MoneyPolicy.amount(losingPart));
    }

    static BigDecimal reduce(BigDecimal price, BigDecimal reductionPercent) {
        if (price == null || reductionPercent == null || reductionPercent.signum() == 0) {
            return price;
        }
        BigDecimal factor = BigDecimal.ONE.subtract(
                reductionPercent.divide(BigDecimal.valueOf(100), MoneyPolicy.CALCULATION_SCALE, RoundingMode.HALF_EVEN));
        return price.multiply(factor).max(BigDecimal.ONE).setScale(4, RoundingMode.HALF_EVEN);
    }

    private static Result unresolved(Map<String, Object> evidence, String status) {
        return new Result(ResultStatus.UNRESOLVED, status, null, null, null, MoneyPolicy.zero(), MoneyPolicy.zero(), evidence);
    }
}
