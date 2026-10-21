package com.stellartrack.decision.persistence;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.common.domain.OperatingMode;
import com.stellartrack.common.exception.ConflictException;
import com.stellartrack.configuration.domain.BotConfigurationSettings.SelectionSourceType;
import com.stellartrack.decision.domain.RecommendationStatus;
import com.stellartrack.staking.domain.StakingMethodType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recommendation")
public class RecommendationEntity {

    @Id
    private UUID id;

    @Column(name = "configuration_id")
    private UUID configurationId;

    @Column(name = "configuration_version")
    private Integer configurationVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperatingMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "selection_source", nullable = false)
    private SelectionSourceType selectionSource;

    @Column(name = "market_id", nullable = false)
    private String marketId;

    @Column(name = "selection_id", nullable = false)
    private long selectionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BetSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", nullable = false)
    private MarketType marketType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecommendationStatus status;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

    @Column(name = "strategy_code", nullable = false)
    private String strategyCode;

    @Column(name = "strategy_version", nullable = false)
    private String strategyVersion;

    @Column(name = "proposed_price")
    private BigDecimal proposedPrice;

    @Column(name = "proposed_price_at")
    private Instant proposedPriceAt;

    @Column(name = "proposed_stake")
    private BigDecimal proposedStake;

    @Column(name = "proposed_liability")
    private BigDecimal proposedLiability;

    @Enumerated(EnumType.STRING)
    @Column(name = "staking_method")
    private StakingMethodType stakingMethod;

    @Column(name = "no_bet_reason")
    private String noBetReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected RecommendationEntity() {
    }

    public static RecommendationEntity create(UUID configurationId, Integer configurationVersion, OperatingMode mode,
            SelectionSourceType source, String marketId, long selectionId, BetSide side, MarketType marketType,
            RecommendationStatus status, boolean requiresApproval, String strategyCode, String strategyVersion,
            BigDecimal proposedPrice, Instant proposedPriceAt, BigDecimal proposedStake, BigDecimal proposedLiability,
            StakingMethodType stakingMethod, String noBetReason, Instant now, Instant expiresAt) {
        RecommendationEntity e = new RecommendationEntity();
        e.id = UUID.randomUUID();
        e.configurationId = configurationId;
        e.configurationVersion = configurationVersion;
        e.mode = mode;
        e.selectionSource = source;
        e.marketId = marketId;
        e.selectionId = selectionId;
        e.side = side;
        e.marketType = marketType;
        e.status = status;
        e.requiresApproval = requiresApproval;
        e.strategyCode = strategyCode;
        e.strategyVersion = strategyVersion;
        e.proposedPrice = proposedPrice;
        e.proposedPriceAt = proposedPriceAt;
        e.proposedStake = proposedStake;
        e.proposedLiability = proposedLiability;
        e.stakingMethod = stakingMethod;
        e.noBetReason = noBetReason;
        e.createdAt = now;
        e.decidedAt = status == RecommendationStatus.PENDING_APPROVAL ? null : now;
        e.expiresAt = expiresAt;
        return e;
    }

    public void decide(RecommendationStatus decision, Instant now) {
        if (status != RecommendationStatus.PENDING_APPROVAL) {
            throw new ConflictException("RECOMMENDATION_ALREADY_DECIDED", "This recommendation is no longer awaiting review");
        }
        this.status = decision;
        this.decidedAt = now;
    }

    /** Notes why an approved/accepted proposal did not result in an order (e.g. failed pre-trade validation). */
    public void noteNotExecuted(String reason) {
        this.noBetReason = reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason;
    }

    public UUID getId() { return id; }
    public UUID getConfigurationId() { return configurationId; }
    public Integer getConfigurationVersion() { return configurationVersion; }
    public OperatingMode getMode() { return mode; }
    public SelectionSourceType getSelectionSource() { return selectionSource; }
    public String getMarketId() { return marketId; }
    public long getSelectionId() { return selectionId; }
    public BetSide getSide() { return side; }
    public MarketType getMarketType() { return marketType; }
    public RecommendationStatus getStatus() { return status; }
    public boolean isRequiresApproval() { return requiresApproval; }
    public String getStrategyCode() { return strategyCode; }
    public String getStrategyVersion() { return strategyVersion; }
    public BigDecimal getProposedPrice() { return proposedPrice; }
    public Instant getProposedPriceAt() { return proposedPriceAt; }
    public BigDecimal getProposedStake() { return proposedStake; }
    public BigDecimal getProposedLiability() { return proposedLiability; }
    public StakingMethodType getStakingMethod() { return stakingMethod; }
    public String getNoBetReason() { return noBetReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
