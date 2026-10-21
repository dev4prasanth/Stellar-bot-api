package com.stellartrack.watch.persistence;

import com.stellartrack.watch.domain.ResultStatus;
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

/** Simulated settlement of a Watch bet under one rule version, with its evidence. */
@Entity
@Table(name = "watch_bet_settlement")
public class WatchBetSettlementEntity {

    @Id
    private UUID id;

    @Column(name = "bet_id", nullable = false)
    private UUID betId;

    @Column(name = "rule_version", nullable = false)
    private String ruleVersion;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResultStatus result;

    @Column(name = "runner_status")
    private String runnerStatus;

    @Column(name = "reduction_factor")
    private BigDecimal reductionFactor;

    @Column(name = "dead_heat_divisor")
    private Integer deadHeatDivisor;

    @Column(name = "settled_price")
    private BigDecimal settledPrice;

    @Column(name = "gross_profit", nullable = false)
    private BigDecimal grossProfit;

    @Column(nullable = false)
    private BigDecimal commission;

    @Column(name = "net_profit", nullable = false)
    private BigDecimal netProfit;

    @Column(name = "best_case_gross_profit", nullable = false)
    private BigDecimal bestCaseGrossProfit;

    @Column(name = "best_case_commission", nullable = false)
    private BigDecimal bestCaseCommission;

    @Column(name = "best_case_net_profit", nullable = false)
    private BigDecimal bestCaseNetProfit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String evidence;

    @Column(name = "settled_at", nullable = false)
    private Instant settledAt;

    protected WatchBetSettlementEntity() {
    }

    public WatchBetSettlementEntity(UUID betId, String ruleVersion, ResultStatus result, String runnerStatus,
            BigDecimal reductionFactor, Integer deadHeatDivisor, BigDecimal settledPrice, BigDecimal grossProfit,
            BigDecimal commission, BigDecimal bestCaseGrossProfit, BigDecimal bestCaseCommission, String evidence,
            Instant settledAt) {
        this.id = UUID.randomUUID();
        this.betId = betId;
        this.ruleVersion = ruleVersion;
        this.current = true;
        this.result = result;
        this.runnerStatus = runnerStatus;
        this.reductionFactor = reductionFactor;
        this.deadHeatDivisor = deadHeatDivisor;
        this.settledPrice = settledPrice;
        this.grossProfit = grossProfit;
        this.commission = commission;
        this.netProfit = grossProfit.subtract(commission);
        this.bestCaseGrossProfit = bestCaseGrossProfit;
        this.bestCaseCommission = bestCaseCommission;
        this.bestCaseNetProfit = bestCaseGrossProfit.subtract(bestCaseCommission);
        this.evidence = evidence;
        this.settledAt = settledAt;
    }

    public UUID getId() { return id; }
    public UUID getBetId() { return betId; }
    public String getRuleVersion() { return ruleVersion; }
    public boolean isCurrent() { return current; }
    public ResultStatus getResult() { return result; }
    public String getRunnerStatus() { return runnerStatus; }
    public BigDecimal getReductionFactor() { return reductionFactor; }
    public Integer getDeadHeatDivisor() { return deadHeatDivisor; }
    public BigDecimal getSettledPrice() { return settledPrice; }
    public BigDecimal getGrossProfit() { return grossProfit; }
    public BigDecimal getCommission() { return commission; }
    public BigDecimal getNetProfit() { return netProfit; }
    public BigDecimal getBestCaseGrossProfit() { return bestCaseGrossProfit; }
    public BigDecimal getBestCaseCommission() { return bestCaseCommission; }
    public BigDecimal getBestCaseNetProfit() { return bestCaseNetProfit; }
    public String getEvidence() { return evidence; }
    public Instant getSettledAt() { return settledAt; }
}
