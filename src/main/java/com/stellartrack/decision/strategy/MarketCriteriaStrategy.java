package com.stellartrack.decision.strategy;

import com.stellartrack.decision.domain.DecisionSnapshot;
import com.stellartrack.market.filter.FilterOutcome;
import com.stellartrack.messaging.domain.Layer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Explicitly configured market-based criteria: a runner is selected when it passes every filter the
 * administrator configured. When several qualify, the configured maximum per race keeps the lowest price ranks.
 * This is a transparent rule over market data, not a prediction model.
 */
@Component
public class MarketCriteriaStrategy implements SelectionStrategy {

    public static final String CODE = "MARKET_CRITERIA";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public String formulaReference() {
        return "docs/reporting/business-rules.md#market-criteria-selection-v1";
    }

    @Override
    public Set<Layer> requiredLayers() {
        return Set.of();
    }

    @Override
    public List<StrategyEvaluation> evaluate(StrategyInput input) {
        Integer maxPerRace = input.configuration().settings().selection().maxSelectionsPerRace();
        List<Long> eligibleByRank = input.filterOutcomes().values().stream()
                .filter(FilterOutcome::eligible)
                .map(FilterOutcome::selectionId)
                .sorted(Comparator.comparing(id -> input.priceRanks().getOrDefault(id, Integer.MAX_VALUE)))
                .toList();
        List<StrategyEvaluation> results = new ArrayList<>();
        for (var runner : input.runners()) {
            FilterOutcome outcome = input.filterOutcomes().get(runner.selectionId());
            List<DecisionSnapshot.Gate> gates = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            if (outcome != null) {
                for (FilterOutcome.RuleResult r : outcome.results()) {
                    gates.add(new DecisionSnapshot.Gate(r.field().name(), r.status() == FilterOutcome.Status.PASSED,
                            r.observedValue(), r.requirement(), r.explanation()));
                    if (r.status() != FilterOutcome.Status.PASSED) {
                        reasons.add(r.explanation());
                    }
                }
            }
            boolean eligible = outcome != null && outcome.eligible();
            int position = eligibleByRank.indexOf(runner.selectionId());
            boolean withinLimit = maxPerRace == null || (position >= 0 && position < maxPerRace);
            gates.add(new DecisionSnapshot.Gate("MAX_SELECTIONS_PER_RACE", withinLimit || !eligible,
                    position >= 0 ? String.valueOf(position + 1) : "-",
                    maxPerRace == null ? "no limit" : "at most " + maxPerRace, null));
            if (eligible && !withinLimit) {
                reasons.add("Another qualifying runner has a better price rank (max " + maxPerRace + " per race)");
            }
            Integer rank = input.priceRanks().get(runner.selectionId());
            List<DecisionSnapshot.Factor> inputs = List.of(new DecisionSnapshot.Factor("priceRank",
                    rank == null ? null : rank.toString(), "rank", "Derived from Betfair best back prices", null, null));
            results.add(new StrategyEvaluation(runner.selectionId(), eligible && withinLimit, null, inputs, List.of(),
                    gates, List.of(), reasons, List.of()));
        }
        return results;
    }
}
