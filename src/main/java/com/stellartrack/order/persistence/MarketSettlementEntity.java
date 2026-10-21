package com.stellartrack.order.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Market-level settled profit and commission revision (Betfair reports commission only at this grain). */
@Entity
@Table(name = "market_settlement")
public class MarketSettlementEntity {

    @Id
    private UUID id;

    @Column(name = "market_id", nullable = false)
    private String marketId;

    @Column(nullable = false)
    private int revision;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Column(name = "settled_at")
    private Instant settledAt;

    private BigDecimal profit;

    private BigDecimal commission;

    @Column(nullable = false)
    private String fingerprint;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected MarketSettlementEntity() {
    }

    public MarketSettlementEntity(String marketId, int revision, Instant settledAt, BigDecimal profit,
            BigDecimal commission, String fingerprint, Instant receivedAt) {
        this.id = UUID.randomUUID();
        this.marketId = marketId;
        this.revision = revision;
        this.current = true;
        this.settledAt = settledAt;
        this.profit = profit;
        this.commission = commission;
        this.fingerprint = fingerprint;
        this.receivedAt = receivedAt;
    }

    public void supersede() {
        this.current = false;
    }

    public String getMarketId() { return marketId; }
    public int getRevision() { return revision; }
    public boolean isCurrent() { return current; }
    public Instant getSettledAt() { return settledAt; }
    public BigDecimal getProfit() { return profit; }
    public BigDecimal getCommission() { return commission; }
    public String getFingerprint() { return fingerprint; }
}
