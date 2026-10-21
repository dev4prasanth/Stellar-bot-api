package com.stellartrack.watch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Simulated commission for one session and market (charged on net market winnings). */
@Entity
@Table(name = "watch_market_commission")
public class WatchMarketCommissionEntity {

    @Embeddable
    public record Key(@Column(name = "session_id") UUID sessionId, @Column(name = "market_id") String marketId)
            implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Column(name = "base_rate", nullable = false)
    private BigDecimal baseRate;

    @Column(name = "discount_rate", nullable = false)
    private BigDecimal discountRate;

    @Column(name = "net_market_profit", nullable = false)
    private BigDecimal netMarketProfit;

    @Column(nullable = false)
    private BigDecimal commission;

    @Column(name = "best_case_net_market_profit", nullable = false)
    private BigDecimal bestCaseNetMarketProfit;

    @Column(name = "best_case_commission", nullable = false)
    private BigDecimal bestCaseCommission;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WatchMarketCommissionEntity() {
    }

    public WatchMarketCommissionEntity(UUID sessionId, String marketId, BigDecimal baseRate, BigDecimal discountRate,
            BigDecimal netMarketProfit, BigDecimal commission, BigDecimal bestCaseNetMarketProfit,
            BigDecimal bestCaseCommission, Instant createdAt) {
        this.id = new Key(sessionId, marketId);
        this.baseRate = baseRate;
        this.discountRate = discountRate;
        this.netMarketProfit = netMarketProfit;
        this.commission = commission;
        this.bestCaseNetMarketProfit = bestCaseNetMarketProfit;
        this.bestCaseCommission = bestCaseCommission;
        this.createdAt = createdAt;
    }

    public Key getId() { return id; }
    public BigDecimal getBaseRate() { return baseRate; }
    public BigDecimal getDiscountRate() { return discountRate; }
    public BigDecimal getNetMarketProfit() { return netMarketProfit; }
    public BigDecimal getCommission() { return commission; }
    public BigDecimal getBestCaseNetMarketProfit() { return bestCaseNetMarketProfit; }
    public BigDecimal getBestCaseCommission() { return bestCaseCommission; }
    public Instant getCreatedAt() { return createdAt; }
}
