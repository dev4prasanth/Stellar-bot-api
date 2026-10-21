package com.stellartrack.decision.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Persistence form of {@link com.stellartrack.decision.domain.DecisionSnapshot}; immutable (DB trigger enforced). */
@Entity
@Immutable
@Table(name = "decision_snapshot")
public class DecisionSnapshotEntity {

    @Id
    private UUID id;

    @Column(name = "recommendation_id", nullable = false)
    private UUID recommendationId;

    @Column(name = "snapshot_schema_version", nullable = false)
    private int snapshotSchemaVersion;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "strategy_code", nullable = false)
    private String strategyCode;

    @Column(name = "strategy_version", nullable = false)
    private String strategyVersion;

    @Column(name = "formula_reference")
    private String formulaReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layer_refs", nullable = false)
    private String layerRefs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String inputs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String intermediate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String gates;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String flags;

    @Column(name = "final_score")
    private BigDecimal finalScore;

    @Column(nullable = false)
    private String result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String reasons;

    @JdbcTypeCode(SqlTypes.JSON)
    private String staking;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "market_state", nullable = false)
    private String marketState;

    protected DecisionSnapshotEntity() {
    }

    public DecisionSnapshotEntity(UUID recommendationId, int schemaVersion, Instant capturedAt, String strategyCode,
            String strategyVersion, String formulaReference, String layerRefs, String inputs, String intermediate,
            String gates, String flags, BigDecimal finalScore, String result, String reasons, String staking,
            String marketState) {
        this.id = UUID.randomUUID();
        this.recommendationId = recommendationId;
        this.snapshotSchemaVersion = schemaVersion;
        this.capturedAt = capturedAt;
        this.strategyCode = strategyCode;
        this.strategyVersion = strategyVersion;
        this.formulaReference = formulaReference;
        this.layerRefs = layerRefs;
        this.inputs = inputs;
        this.intermediate = intermediate;
        this.gates = gates;
        this.flags = flags;
        this.finalScore = finalScore;
        this.result = result;
        this.reasons = reasons;
        this.staking = staking;
        this.marketState = marketState;
    }

    public UUID getId() { return id; }
    public UUID getRecommendationId() { return recommendationId; }
    public int getSnapshotSchemaVersion() { return snapshotSchemaVersion; }
    public Instant getCapturedAt() { return capturedAt; }
    public String getStrategyCode() { return strategyCode; }
    public String getStrategyVersion() { return strategyVersion; }
    public String getFormulaReference() { return formulaReference; }
    public String getLayerRefs() { return layerRefs; }
    public String getInputs() { return inputs; }
    public String getIntermediate() { return intermediate; }
    public String getGates() { return gates; }
    public String getFlags() { return flags; }
    public BigDecimal getFinalScore() { return finalScore; }
    public String getResult() { return result; }
    public String getReasons() { return reasons; }
    public String getStaking() { return staking; }
    public String getMarketState() { return marketState; }
}
