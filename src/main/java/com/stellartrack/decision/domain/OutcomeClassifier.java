package com.stellartrack.decision.domain;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.market.domain.MarketResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Post-race selection-outcome rules, version {@value #RULE_VERSION}. Uses the proposed side and market:
 * <ul>
 *   <li>Runner REMOVED (non-runner) → VOID.</li>
 *   <li>WIN market: BACK is favorable when the runner is WINNER; LAY is favorable when the runner is LOSER.</li>
 *   <li>PLACE market: Betfair marks placed runners WINNER, so the same status rule applies to the place outcome.</li>
 *   <li>Any other status (HIDDEN, PLACED, missing result) → NOT_EVALUABLE.</li>
 *   <li>Dead heats are flagged; the classification follows the status, but no payout is inferred from it.</li>
 * </ul>
 * This classifies selections, not money: it never asserts a proposal would have been matched or paid.
 */
public final class OutcomeClassifier {

    public static final String RULE_VERSION = "OUTCOME_RULES_V1";

    public record Result(OutcomeClassification classification, String runnerStatus, boolean deadHeat,
            Map<String, Object> evidence) {
    }

    private OutcomeClassifier() {
    }

    public static Result classify(BetSide side, MarketType proposedMarketType, long selectionId,
            Optional<MarketResult> marketResult) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ruleVersion", RULE_VERSION);
        evidence.put("source", "Betfair listMarketBook of the CLOSED market");
        evidence.put("proposedSide", side.name());
        evidence.put("proposedMarketType", proposedMarketType.name());
        if (marketResult.isEmpty()) {
            evidence.put("reason", "Official market result not captured");
            return new Result(OutcomeClassification.NOT_EVALUABLE, null, false, evidence);
        }
        MarketResult result = marketResult.get();
        evidence.put("marketId", result.marketId());
        evidence.put("numberOfWinners", result.numberOfWinners());
        evidence.put("resultCapturedAt", result.capturedAt());
        boolean deadHeat = result.isDeadHeat();
        evidence.put("deadHeatDetected", deadHeat);
        if (result.marketType() != proposedMarketType) {
            evidence.put("reason", "Result market type differs from the proposed market type");
            return new Result(OutcomeClassification.NOT_EVALUABLE, null, deadHeat, evidence);
        }
        Optional<MarketResult.RunnerResult> runner = result.runner(selectionId);
        if (runner.isEmpty() || runner.get().status() == null) {
            evidence.put("reason", "Runner status not available");
            return new Result(OutcomeClassification.NOT_EVALUABLE, null, deadHeat, evidence);
        }
        MarketResult.RunnerResult r = runner.get();
        evidence.put("runnerStatus", r.status());
        evidence.put("adjustmentFactor", r.adjustmentFactor());
        if (r.isRemoved()) {
            return new Result(OutcomeClassification.VOID, r.status(), deadHeat, evidence);
        }
        if (r.isPlaced() && proposedMarketType == MarketType.EACH_WAY) {
            evidence.put("note", "Placed: the place part of the Each-Way bet wins, the win part loses");
            return new Result(side == BetSide.BACK ? OutcomeClassification.FAVORABLE : OutcomeClassification.UNFAVORABLE,
                    r.status(), deadHeat, evidence);
        }
        if (!r.isWinner() && !r.isLoser()) {
            evidence.put("reason", "Status " + r.status() + " is not covered by rule version " + RULE_VERSION);
            return new Result(OutcomeClassification.NOT_EVALUABLE, r.status(), deadHeat, evidence);
        }
        boolean favorable = side == BetSide.BACK ? r.isWinner() : r.isLoser();
        if (deadHeat && r.isWinner()) {
            evidence.put("note", "Dead heat: settlement would be reduced; no payout is inferred");
        }
        return new Result(favorable ? OutcomeClassification.FAVORABLE : OutcomeClassification.UNFAVORABLE, r.status(),
                deadHeat && r.isWinner(), evidence);
    }
}
