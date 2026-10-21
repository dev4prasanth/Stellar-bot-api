package com.stellartrack.order.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One revision of a bet settlement. A provider correction adds a new revision; history is kept. */
@Entity
@Table(name = "settlement")
public class SettlementEntity {

    @Id
    private UUID id;

    @Column(name = "bet_id", nullable = false)
    private String betId;

    @Column(nullable = false)
    private int revision;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Column(name = "bet_status", nullable = false)
    private String betStatus;

    @Column(name = "bet_outcome")
    private String betOutcome;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "price_requested")
    private BigDecimal priceRequested;

    @Column(name = "price_matched")
    private BigDecimal priceMatched;

    @Column(name = "price_reduced")
    private Boolean priceReduced;

    @Column(name = "size_settled")
    private BigDecimal sizeSettled;

    @Column(name = "size_cancelled")
    private BigDecimal sizeCancelled;

    private BigDecimal profit;

    @Column(name = "last_matched_at")
    private Instant lastMatchedAt;

    @Column(nullable = false)
    private String fingerprint;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected SettlementEntity() {
    }

    public SettlementEntity(String betId, int revision, String betStatus, String betOutcome, Instant settledAt,
            BigDecimal priceRequested, BigDecimal priceMatched, Boolean priceReduced, BigDecimal sizeSettled,
            BigDecimal sizeCancelled, BigDecimal profit, Instant lastMatchedAt, String fingerprint, Instant receivedAt) {
        this.id = UUID.randomUUID();
        this.betId = betId;
        this.revision = revision;
        this.current = true;
        this.betStatus = betStatus;
        this.betOutcome = betOutcome;
        this.settledAt = settledAt;
        this.priceRequested = priceRequested;
        this.priceMatched = priceMatched;
        this.priceReduced = priceReduced;
        this.sizeSettled = sizeSettled;
        this.sizeCancelled = sizeCancelled;
        this.profit = profit;
        this.lastMatchedAt = lastMatchedAt;
        this.fingerprint = fingerprint;
        this.receivedAt = receivedAt;
    }

    public void supersede() {
        this.current = false;
    }

    public UUID getId() { return id; }
    public String getBetId() { return betId; }
    public int getRevision() { return revision; }
    public boolean isCurrent() { return current; }
    public String getBetStatus() { return betStatus; }
    public String getBetOutcome() { return betOutcome; }
    public Instant getSettledAt() { return settledAt; }
    public BigDecimal getPriceRequested() { return priceRequested; }
    public BigDecimal getPriceMatched() { return priceMatched; }
    public Boolean getPriceReduced() { return priceReduced; }
    public BigDecimal getSizeSettled() { return sizeSettled; }
    public BigDecimal getSizeCancelled() { return sizeCancelled; }
    public BigDecimal getProfit() { return profit; }
    public Instant getLastMatchedAt() { return lastMatchedAt; }
    public String getFingerprint() { return fingerprint; }
    public Instant getReceivedAt() { return receivedAt; }
}
