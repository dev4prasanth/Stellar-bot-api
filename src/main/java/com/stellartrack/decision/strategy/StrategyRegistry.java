package com.stellartrack.decision.strategy;

import com.stellartrack.common.capability.Capability;
import com.stellartrack.common.capability.CapabilityProvider;
import com.stellartrack.common.capability.CapabilityReport;
import com.stellartrack.common.capability.CapabilityState;
import com.stellartrack.common.exception.CapabilityBlockedException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Registered selection strategies. Only explicitly approved strategies are registered as beans; there is
 * currently no approved prediction strategy, so prediction-based selection reports BLOCKED.
 */
@Component
public class StrategyRegistry implements CapabilityProvider {

    private final Map<String, SelectionStrategy> strategies;

    public StrategyRegistry(List<SelectionStrategy> strategies) {
        this.strategies = strategies.stream().collect(Collectors.toMap(SelectionStrategy::code, Function.identity()));
    }

    public Optional<SelectionStrategy> find(String code) {
        return Optional.ofNullable(strategies.get(code));
    }

    public SelectionStrategy marketCriteria() {
        return strategies.get(MarketCriteriaStrategy.CODE);
    }

    /** Resolves an approved prediction strategy or explains why prediction selection is blocked. */
    public SelectionStrategy requirePrediction(String code) {
        SelectionStrategy strategy = strategies.get(code);
        if (strategy == null || MarketCriteriaStrategy.CODE.equals(code)) {
            throw new CapabilityBlockedException(Capability.PREDICTION_STRATEGY,
                    "No approved prediction strategy is registered under code " + code);
        }
        return strategy;
    }

    public List<SelectionStrategy> all() {
        return List.copyOf(strategies.values());
    }

    @Override
    public List<CapabilityReport> capabilities() {
        boolean predictionRegistered = strategies.keySet().stream()
                .anyMatch(code -> !MarketCriteriaStrategy.CODE.equals(code));
        CapabilityReport prediction = predictionRegistered
                ? CapabilityReport.of(Capability.PREDICTION_STRATEGY, CapabilityState.IMPLEMENTED,
                        "Approved strategy registered", List.of())
                : CapabilityReport.blocked(Capability.PREDICTION_STRATEGY,
                        "No approved Layer 1/2/3 decision logic has been supplied",
                        "Approved scoring/combination formulas and versions",
                        "Conflict, missing, stale and out-of-order input policies",
                        "Required layers per strategy");
        CapabilityReport correlation = CapabilityReport.blocked(Capability.CROSS_LAYER_CORRELATION,
                "Correlation keys between Layer 1/2/3 records and Betfair markets/runners are not yet defined",
                "Layer identifiers and timestamps", "Approved mapping to Betfair marketId/selectionId");
        return List.of(prediction, correlation);
    }
}
