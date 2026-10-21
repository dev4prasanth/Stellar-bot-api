package com.stellartrack.market.domain;

import com.stellartrack.common.domain.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Official post-race runner statuses captured from the closed Betfair market book
 * (WINNER, LOSER, PLACED, REMOVED, REMOVED_VACANT, HIDDEN). A dead heat is detected when more runners hold
 * WINNER status than the market's number of winners; dead-heat payout reductions are not inferred here —
 * actual bets use Betfair's settled profit.
 */
public record MarketResult(
        String marketId,
        MarketType marketType,
        Integer numberOfWinners,
        Map<Long, RunnerResult> runners,
        Instant capturedAt) {

    /** @param removalDate when a removed runner was taken out of the market (orders it against bets), if known */
    public record RunnerResult(String status, BigDecimal adjustmentFactor, Instant removalDate) {

        public RunnerResult(String status, BigDecimal adjustmentFactor) {
            this(status, adjustmentFactor, null);
        }

        public boolean isWinner() {
            return "WINNER".equals(status);
        }

        /** EACH_WAY markets: placed but did not win. */
        public boolean isPlaced() {
            return "PLACED".equals(status);
        }

        public boolean isLoser() {
            return "LOSER".equals(status);
        }

        public boolean isRemoved() {
            return status != null && status.startsWith("REMOVED");
        }
    }

    public Optional<RunnerResult> runner(long selectionId) {
        return Optional.ofNullable(runners.get(selectionId));
    }

    public boolean isDeadHeat() {
        long winners = runners.values().stream().filter(RunnerResult::isWinner).count();
        return numberOfWinners != null && winners > numberOfWinners;
    }
}
