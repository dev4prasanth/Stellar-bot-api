package com.stellartrack.watch.persistence;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.watch.domain.WatchDecisionOutcome;
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

/** One evaluated runner in Watch Mode, with the full decision snapshot (same content as a live recommendation). */
@Entity
@Table(name = "watch_decision")
public class WatchDecisionEntity {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "session_configuration_id", nullable = false)
    private UUID sessionConfigurationId;

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
    private WatchDecisionOutcome outcome;

    private String reasons;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String snapshot;

    @Column(name = "proposed_price")
    private BigDecimal proposedPrice;

    @Column(name = "proposed_stake")
    private BigDecimal proposedStake;

    @Column(name = "proposed_liability")
    private BigDecimal proposedLiability;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WatchDecisionEntity() {
    }

    public WatchDecisionEntity(UUID sessionId, UUID sessionConfigurationId, String marketId, long selectionId,
            BetSide side, MarketType marketType, WatchDecisionOutcome outcome, String reasons, String snapshot,
            BigDecimal proposedPrice, BigDecimal proposedStake, BigDecimal proposedLiability, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.sessionId = sessionId;
        this.sessionConfigurationId = sessionConfigurationId;
        this.marketId = marketId;
        this.selectionId = selectionId;
        this.side = side;
        this.marketType = marketType;
        this.outcome = outcome;
        this.reasons = reasons == null || reasons.length() <= 2000 ? reasons : reasons.substring(0, 2000);
        this.snapshot = snapshot;
        this.proposedPrice = proposedPrice;
        this.proposedStake = proposedStake;
        this.proposedLiability = proposedLiability;
        this.createdAt = createdAt;
    }

    public void outcome(WatchDecisionOutcome outcome, String reasons) {
        this.outcome = outcome;
        if (reasons != null) {
            this.reasons = reasons.length() <= 2000 ? reasons : reasons.substring(0, 2000);
        }
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public UUID getSessionConfigurationId() { return sessionConfigurationId; }
    public String getMarketId() { return marketId; }
    public long getSelectionId() { return selectionId; }
    public BetSide getSide() { return side; }
    public MarketType getMarketType() { return marketType; }
    public WatchDecisionOutcome getOutcome() { return outcome; }
    public String getReasons() { return reasons; }
    public String getSnapshot() { return snapshot; }
    public BigDecimal getProposedPrice() { return proposedPrice; }
    public BigDecimal getProposedStake() { return proposedStake; }
    public BigDecimal getProposedLiability() { return proposedLiability; }
    public Instant getCreatedAt() { return createdAt; }
}
