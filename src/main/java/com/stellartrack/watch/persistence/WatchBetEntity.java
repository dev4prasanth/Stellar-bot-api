package com.stellartrack.watch.persistence;

import com.stellartrack.common.domain.BetSide;
import com.stellartrack.common.domain.MarketType;
import com.stellartrack.staking.domain.BetMath;
import com.stellartrack.watch.domain.MatchingStatus;
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

/** A simulated bet. It never corresponds to a Betfair order. */
@Entity
@Table(name = "watch_bet")
public class WatchBetEntity {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "session_configuration_id", nullable = false)
    private UUID sessionConfigurationId;

    @Column(name = "decision_id", nullable = false)
    private UUID decisionId;

    @Column(name = "configuration_id", nullable = false)
    private UUID configurationId;

    @Column(name = "configuration_version", nullable = false)
    private int configurationVersion;

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

    @Column(name = "persistence_type", nullable = false)
    private String persistenceType;

    @Column(name = "requested_price", nullable = false)
    private BigDecimal requestedPrice;

    @Column(name = "requested_size", nullable = false)
    private BigDecimal requestedSize;

    @Column(name = "limit_price", nullable = false)
    private BigDecimal limitPrice;

    @Column(name = "slippage_ticks", nullable = false)
    private int slippageTicks;

    @Column(name = "size_matched", nullable = false)
    private BigDecimal sizeMatched;

    @Column(name = "average_price_matched")
    private BigDecimal averagePriceMatched;

    @Column(name = "size_remaining", nullable = false)
    private BigDecimal sizeRemaining;

    @Column(name = "size_lapsed", nullable = false)
    private BigDecimal sizeLapsed;

    @Column(name = "size_voided", nullable = false)
    private BigDecimal sizeVoided;

    @Column(name = "max_liability", nullable = false)
    private BigDecimal maxLiability;

    @Enumerated(EnumType.STRING)
    @Column(name = "matching_status", nullable = false)
    private MatchingStatus matchingStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false)
    private ResultStatus resultStatus;

    @Column(name = "data_delay_ms")
    private Long dataDelayMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "book_at_placement", nullable = false)
    private String bookAtPlacement;

    @Column(name = "lapse_reason")
    private String lapseReason;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "matched_at")
    private Instant matchedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    protected WatchBetEntity() {
    }

    public WatchBetEntity(UUID sessionId, UUID sessionConfigurationId, UUID decisionId, UUID configurationId,
            int configurationVersion, String marketId, long selectionId, BetSide side, MarketType marketType,
            String persistenceType, BigDecimal requestedPrice, BigDecimal requestedSize, BigDecimal limitPrice,
            int slippageTicks, Long dataDelayMs, String bookAtPlacement, Instant placedAt) {
        this.id = UUID.randomUUID();
        this.sessionId = sessionId;
        this.sessionConfigurationId = sessionConfigurationId;
        this.decisionId = decisionId;
        this.configurationId = configurationId;
        this.configurationVersion = configurationVersion;
        this.marketId = marketId;
        this.selectionId = selectionId;
        this.side = side;
        this.marketType = marketType;
        this.persistenceType = persistenceType;
        this.requestedPrice = requestedPrice;
        this.requestedSize = requestedSize;
        this.limitPrice = limitPrice;
        this.slippageTicks = slippageTicks;
        this.sizeMatched = BigDecimal.ZERO;
        this.sizeRemaining = requestedSize;
        this.sizeLapsed = BigDecimal.ZERO;
        this.sizeVoided = BigDecimal.ZERO;
        this.maxLiability = marketType == MarketType.EACH_WAY ? BetMath.eachWayTotalStake(requestedSize)
                : BetMath.exposure(side, requestedSize, requestedPrice);
        this.matchingStatus = MatchingStatus.UNMATCHED;
        this.resultStatus = ResultStatus.OPEN;
        this.dataDelayMs = dataDelayMs;
        this.bookAtPlacement = bookAtPlacement;
        this.placedAt = placedAt;
    }

    /** Applies new fills; {@code averagePrice} is the average over all fills of the bet. */
    public void filled(BigDecimal newlyMatched, BigDecimal averagePrice, Instant now) {
        this.sizeMatched = sizeMatched.add(newlyMatched);
        this.sizeRemaining = requestedSize.subtract(sizeMatched).max(BigDecimal.ZERO);
        this.averagePriceMatched = averagePrice;
        if (matchedAt == null) {
            this.matchedAt = now; // first match: orders the bet against later non-runner removals
        }
        if (sizeRemaining.signum() == 0) {
            this.matchingStatus = MatchingStatus.MATCHED;
            this.closedAt = now;
        } else {
            this.matchingStatus = MatchingStatus.PARTIALLY_MATCHED;
        }
    }

    /**
     * Matching ends: the unmatched remainder lapses (or is voided when the runner was removed). The bet still settles
     * with the race result, so even an unmatched bet carries its best-case figure.
     */
    public void closeMatching(boolean runnerRemoved, String reason, Instant now) {
        if (runnerRemoved) {
            this.sizeVoided = sizeRemaining;
        } else {
            this.sizeLapsed = sizeRemaining;
        }
        this.sizeRemaining = BigDecimal.ZERO;
        this.lapseReason = reason;
        this.closedAt = now;
        this.matchingStatus = runnerRemoved && sizeMatched.signum() == 0 ? MatchingStatus.VOIDED : MatchingStatus.LAPSED;
    }

    /** The administrator revokes the unmatched remainder: it lapses with the given reason; any matched part stands. */
    public void revoke(String reason, Instant now) {
        closeMatching(false, reason, now);
    }

    public void settled(ResultStatus result, Instant now) {
        this.resultStatus = result;
        this.settledAt = now;
        if (closedAt == null) {
            this.closedAt = now;
        }
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public UUID getSessionConfigurationId() { return sessionConfigurationId; }
    public UUID getDecisionId() { return decisionId; }
    public UUID getConfigurationId() { return configurationId; }
    public int getConfigurationVersion() { return configurationVersion; }
    public String getMarketId() { return marketId; }
    public long getSelectionId() { return selectionId; }
    public BetSide getSide() { return side; }
    public MarketType getMarketType() { return marketType; }
    public String getPersistenceType() { return persistenceType; }
    public BigDecimal getRequestedPrice() { return requestedPrice; }
    public BigDecimal getRequestedSize() { return requestedSize; }
    public BigDecimal getLimitPrice() { return limitPrice; }
    public int getSlippageTicks() { return slippageTicks; }
    public BigDecimal getSizeMatched() { return sizeMatched; }
    public BigDecimal getAveragePriceMatched() { return averagePriceMatched; }
    public BigDecimal getSizeRemaining() { return sizeRemaining; }
    public BigDecimal getSizeLapsed() { return sizeLapsed; }
    public BigDecimal getSizeVoided() { return sizeVoided; }
    public BigDecimal getMaxLiability() { return maxLiability; }
    public MatchingStatus getMatchingStatus() { return matchingStatus; }
    public ResultStatus getResultStatus() { return resultStatus; }
    public Long getDataDelayMs() { return dataDelayMs; }
    public String getBookAtPlacement() { return bookAtPlacement; }
    public String getLapseReason() { return lapseReason; }
    public Instant getPlacedAt() { return placedAt; }
    public Instant getMatchedAt() { return matchedAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getSettledAt() { return settledAt; }
}
