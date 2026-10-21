package com.stellartrack.decision.domain;

import com.stellartrack.messaging.domain.Layer;
import com.stellartrack.staking.domain.StakeCalculation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable as-of-decision record of exactly what participated in an evaluation. Values are stored as supplied
 * (strings preserve sign and precision); nothing is recomputed or back-filled after the race.
 */
public record DecisionSnapshot(
        String strategyCode,
        String strategyVersion,
        String formulaReference,
        List<LayerRef> layerRefs,
        List<Factor> inputs,
        List<Factor> intermediate,
        List<Gate> gates,
        List<String> flags,
        BigDecimal finalScore,
        String result,
        List<String> reasons,
        StakeCalculation staking,
        MarketState marketState) {

    public static final int SCHEMA_VERSION = 1;

    public DecisionSnapshot {
        layerRefs = layerRefs == null ? List.of() : List.copyOf(layerRefs);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        intermediate = intermediate == null ? List.of() : List.copyOf(intermediate);
        gates = gates == null ? List.of() : List.copyOf(gates);
        flags = flags == null ? List.of() : List.copyOf(flags);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** Reference to a consumed, validated layer record. */
    public record LayerRef(Layer layer, UUID recordId, UUID receiptId, String dedupKey, String contractVersion,
            Instant sourceEventAt) {
    }

    /** A named value with unit and source; {@code value} keeps the original textual/signed representation. */
    public record Factor(String name, String value, String unit, String source, String formula, String weight) {
    }

    /** A rule/threshold check and its outcome. */
    public record Gate(String name, boolean passed, String observed, String requirement, String explanation) {
    }

    /** Market and price state seen at decision time. */
    public record MarketState(String marketId, long selectionId, String marketStatus, boolean inPlay,
            BigDecimal bestBack, BigDecimal bestLay, BigDecimal proposedPrice, Instant priceAsOf, String priceSource,
            boolean dataDelayed, Integer priceRank, BigDecimal marketTotalMatched) {
    }
}
