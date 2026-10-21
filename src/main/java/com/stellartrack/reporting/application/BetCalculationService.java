package com.stellartrack.reporting.application;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.exception.NotFoundException;
import com.stellartrack.common.json.JsonCodec;
import com.stellartrack.common.money.MoneyPolicy;
import com.stellartrack.decision.application.RecommendationQueryService;
import com.stellartrack.decision.application.RecommendationQueryService.RecommendationDetail;
import com.stellartrack.market.application.MarketResultService;
import com.stellartrack.market.domain.MarketResult;
import com.stellartrack.order.persistence.BetOrderEntity;
import com.stellartrack.order.persistence.BetOrderRepository;
import com.stellartrack.order.persistence.MarketSettlementRepository;
import com.stellartrack.order.persistence.OrderIntentEntity;
import com.stellartrack.order.persistence.OrderIntentRepository;
import com.stellartrack.order.persistence.SettlementEntity;
import com.stellartrack.order.persistence.SettlementRepository;
import com.stellartrack.order.persistence.SubmissionAttemptEntity;
import com.stellartrack.order.persistence.SubmissionAttemptRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * "Why was this selection made, what numbers were used, and how was the actual profit calculated?"
 * Assembles the immutable decision snapshot, the stake calculation, execution facts and the authoritative
 * settlement revisions for one bet. Base-case formulas are shown for illustration only and reconciled against
 * Betfair's settled profit, which is always the authoritative figure.
 */
@Service
@Transactional(readOnly = true)
public class BetCalculationService {

    public record Step(String label, String formula, BigDecimal value, String note) {
    }

    public record BetCalculation(
            String betId,
            Map<String, Object> order,
            RecommendationDetail decision,
            JsonNode stakeCalculationAtDecision,
            List<SubmissionAttemptEntity> submissionAttempts,
            Map<String, Object> execution,
            Map<String, Object> officialResult,
            List<SettlementEntity> settlementRevisions,
            Map<String, Object> marketCommission,
            List<Step> illustrativeCalculation,
            Map<String, Object> estimateVersusActual,
            List<String> notes) {
    }

    private final BetOrderRepository betOrders;
    private final OrderIntentRepository intents;
    private final SubmissionAttemptRepository attempts;
    private final SettlementRepository settlements;
    private final MarketSettlementRepository marketSettlements;
    private final RecommendationQueryService recommendations;
    private final MarketResultService marketResults;
    private final JsonCodec json;

    public BetCalculationService(BetOrderRepository betOrders, OrderIntentRepository intents,
            SubmissionAttemptRepository attempts, SettlementRepository settlements,
            MarketSettlementRepository marketSettlements, RecommendationQueryService recommendations,
            MarketResultService marketResults, JsonCodec json) {
        this.betOrders = betOrders;
        this.intents = intents;
        this.attempts = attempts;
        this.settlements = settlements;
        this.marketSettlements = marketSettlements;
        this.recommendations = recommendations;
        this.marketResults = marketResults;
        this.json = json;
    }

    public BetCalculation calculate(String betId) {
        BetOrderEntity order = betOrders.findById(betId).orElseThrow(() -> new NotFoundException("Bet", betId));
        OrderIntentEntity intent = order.getIntentId() == null ? null : intents.findById(order.getIntentId()).orElse(null);
        RecommendationDetail decision = intent == null || intent.getRecommendationId() == null ? null
                : recommendations.detail(intent.getRecommendationId());
        List<SettlementEntity> revisions = settlements.findByBetIdOrderByBetStatusAscRevisionAsc(betId);
        SettlementEntity current = revisions.stream()
                .filter(s -> s.isCurrent() && ("SETTLED".equals(s.getBetStatus()) || "VOIDED".equals(s.getBetStatus())))
                .findFirst().orElse(null);
        List<String> notes = new ArrayList<>();

        Map<String, Object> orderView = new LinkedHashMap<>();
        orderView.put("betId", betId);
        orderView.put("marketId", order.getMarketId());
        orderView.put("selectionId", order.getSelectionId());
        orderView.put("side", order.getSide());
        if (intent != null) {
            orderView.put("intentId", intent.getId());
            orderView.put("mode", intent.getTradingMode());
            orderView.put("origin", intent.getMode());
            orderView.put("marketType", intent.getMarketType());
            orderView.put("configurationId", intent.getConfigurationId());
            orderView.put("configurationVersion", intent.getConfigurationVersion());
            orderView.put("customerOrderRef", intent.getCustomerOrderRef());
            orderView.put("createdAt", intent.getCreatedAt());
        }

        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("requestedPrice", intent == null ? order.getPriceRequested() : intent.getRequestedPrice());
        execution.put("requestedSize", intent == null ? order.getSizeRequested() : intent.getRequestedSize());
        execution.put("averagePriceMatched", order.getAveragePriceMatched());
        execution.put("sizeMatched", order.getSizeMatched());
        execution.put("sizeRemaining", order.getSizeRemaining());
        execution.put("sizeLapsed", order.getSizeLapsed());
        execution.put("sizeCancelled", order.getSizeCancelled());
        execution.put("sizeVoided", order.getSizeVoided());
        execution.put("placedAt", order.getPlacedAt());
        execution.put("matchedAt", order.getMatchedAt());
        execution.put("lastProviderUpdate", order.getProviderUpdatedAt());
        execution.put("lastSource", order.getLastSource());

        Map<String, Object> result = new LinkedHashMap<>();
        MarketResult marketResult = marketResults.result(order.getMarketId()).orElse(null);
        if (marketResult == null) {
            result.put("status", "Official result not captured yet");
        } else {
            marketResult.runner(order.getSelectionId()).ifPresent(r -> {
                result.put("runnerStatus", r.status());
                result.put("adjustmentFactor", r.adjustmentFactor());
            });
            result.put("numberOfWinners", marketResult.numberOfWinners());
            result.put("deadHeat", marketResult.isDeadHeat());
            result.put("capturedAt", marketResult.capturedAt());
        }

        Map<String, Object> commission = new LinkedHashMap<>();
        marketSettlements.findByMarketIdAndCurrentTrue(order.getMarketId()).ifPresentOrElse(ms -> {
            commission.put("marketCommission", ms.getCommission());
            commission.put("marketProfit", ms.getProfit());
            commission.put("revision", ms.getRevision());
            commission.put("note", "Betfair charges commission per market on net winnings; it is not allocated to individual bets.");
        }, () -> commission.put("note", "Market-level commission not yet synchronized"));

        JsonNode stakeAtDecision = intent == null ? null : json.readTree(intent.getStaking());
        List<Step> steps = illustrative(order, current, notes);
        Map<String, Object> comparison = new LinkedHashMap<>();
        if (stakeAtDecision != null && stakeAtDecision.has("estimatedGrossProfit")) {
            comparison.put("estimatedGrossProfitIfWins", stakeAtDecision.path("estimatedGrossProfit").decimalValue());
            comparison.put("estimatedExposure", stakeAtDecision.path("exposure").decimalValue());
            comparison.put("estimateLabel", "ESTIMATE at decision time, assuming a full match at the proposed price; not earned money");
        }
        comparison.put("actualSettledProfit", current == null ? null : current.getProfit());
        comparison.put("actualLabel", current == null ? "Not settled yet" : "Betfair settled profit (authoritative, before commission)");
        if (revisions.stream().anyMatch(s -> s.getRevision() > 1)) {
            notes.add("This bet has settlement corrections; all revisions are listed and the current one is used.");
        }
        return new BetCalculation(betId, orderView, decision, stakeAtDecision,
                intent == null ? List.of() : attempts.findByIntentIdOrderByAttemptNoAsc(intent.getId()), execution, result,
                revisions, commission, steps, comparison, notes);
    }

    /** Base-case formula for the matched portion at the average matched price, reconciled with Betfair's profit. */
    private static List<Step> illustrative(BetOrderEntity order, SettlementEntity current, List<String> notes) {
        List<Step> steps = new ArrayList<>();
        BigDecimal s = order.getSizeMatched();
        BigDecimal p = order.getAveragePriceMatched();
        if (s == null || s.signum() == 0 || p == null) {
            notes.add("No matched amount: there is no settlement calculation to illustrate.");
            return steps;
        }
        steps.add(new Step("Matched stake (s)", "provider sizeMatched", s, null));
        steps.add(new Step("Average matched price (p)", "provider averagePriceMatched", p,
                "When matched at several prices, the average is an approximation of the per-fill results."));
        BigDecimal winning = MoneyPolicy.amount(s.multiply(p.subtract(BigDecimal.ONE)));
        if (order.getSide() == BetSide.BACK) {
            steps.add(new Step("BACK profit if the selection wins", "s × (p − 1)", winning, "Before commission"));
            steps.add(new Step("BACK loss if the selection loses", "−s", s.negate(), null));
        } else {
            steps.add(new Step("LAY liability", "s × (p − 1)", winning, null));
            steps.add(new Step("LAY profit if the selection loses", "+s", s, "Before commission"));
            steps.add(new Step("LAY loss if the selection wins", "−s × (p − 1)", winning.negate(), null));
        }
        if (current != null && current.getProfit() != null) {
            steps.add(new Step("Betfair settled profit (authoritative)", "listClearedOrders profit", current.getProfit(),
                    Boolean.TRUE.equals(current.getPriceReduced())
                            ? "A Betfair reduction factor was applied (e.g. a late non-runner)" : null));
            boolean matchesBaseCase = current.getProfit().compareTo(winning) == 0
                    || current.getProfit().compareTo(s) == 0
                    || current.getProfit().compareTo(s.negate()) == 0
                    || current.getProfit().compareTo(winning.negate()) == 0;
            if (!matchesBaseCase) {
                notes.add("Settled profit differs from the base-case formula; causes can include partial fills at different"
                        + " prices, reduction factors, dead heats or voided portions. The settled figure is authoritative.");
            }
        }
        return steps;
    }
}
