package com.stellartrack.decision.strategy;

import com.stellartrack.decision.domain.DecisionSnapshot;
import java.math.BigDecimal;
import java.util.List;

/** Per-runner strategy result with every factor that participated. */
public record StrategyEvaluation(
        long selectionId,
        boolean recommend,
        BigDecimal finalScore,
        List<DecisionSnapshot.Factor> inputs,
        List<DecisionSnapshot.Factor> intermediate,
        List<DecisionSnapshot.Gate> gates,
        List<String> flags,
        List<String> reasons,
        List<DecisionSnapshot.LayerRef> layerRefs) {
}
