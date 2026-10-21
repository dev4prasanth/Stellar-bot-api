package com.stellartrack.decision.strategy;

import com.stellartrack.messaging.domain.Layer;
import java.util.List;
import java.util.Set;

/**
 * An approved selection/evaluation rule. Implementations must be deterministic, use only inputs available at
 * decision time, and report every participating factor so the decision snapshot is reproducible.
 * Prediction strategies over Layer 1/2/3 data may only be added once their formulas are approved.
 */
public interface SelectionStrategy {

    String code();

    String version();

    /** Human-readable reference to the approved formula/specification document. */
    String formulaReference();

    /** Layers whose fresh, validated data the strategy requires; any non-fresh layer blocks evaluation. */
    Set<Layer> requiredLayers();

    List<StrategyEvaluation> evaluate(StrategyInput input);
}
