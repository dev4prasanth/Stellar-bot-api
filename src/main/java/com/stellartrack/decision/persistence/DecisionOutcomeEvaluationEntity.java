package com.stellartrack.decision.persistence;

import com.stellartrack.decision.domain.OutcomeClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Post-race selection-outcome analysis; separate from decision snapshots and from realized settlements. */
@Entity
@Table(name = "decision_outcome_evaluation")
public class DecisionOutcomeEvaluationEntity {

    @Id
    private UUID id;

    @Column(name = "recommendation_id", nullable = false)
    private UUID recommendationId;

    @Column(name = "rule_version", nullable = false)
    private String ruleVersion;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutcomeClassification classification;

    @Column(name = "runner_result")
    private String runnerResult;

    @Column(name = "dead_heat", nullable = false)
    private boolean deadHeat;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String evidence;

    @Column(name = "hypothetical_profit")
    private BigDecimal hypotheticalProfit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hypothetical_basis")
    private String hypotheticalBasis;

    protected DecisionOutcomeEvaluationEntity() {
    }

    public DecisionOutcomeEvaluationEntity(UUID recommendationId, String ruleVersion, Instant evaluatedAt,
            OutcomeClassification classification, String runnerResult, boolean deadHeat, String evidence) {
        this.id = UUID.randomUUID();
        this.recommendationId = recommendationId;
        this.ruleVersion = ruleVersion;
        this.evaluatedAt = evaluatedAt;
        this.classification = classification;
        this.runnerResult = runnerResult;
        this.deadHeat = deadHeat;
        this.evidence = evidence;
    }

    public UUID getId() { return id; }
    public UUID getRecommendationId() { return recommendationId; }
    public String getRuleVersion() { return ruleVersion; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public OutcomeClassification getClassification() { return classification; }
    public String getRunnerResult() { return runnerResult; }
    public boolean isDeadHeat() { return deadHeat; }
    public String getEvidence() { return evidence; }
    public BigDecimal getHypotheticalProfit() { return hypotheticalProfit; }
    public String getHypotheticalBasis() { return hypotheticalBasis; }
}
