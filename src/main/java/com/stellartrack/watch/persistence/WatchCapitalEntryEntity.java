package com.stellartrack.watch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One append-only entry of a Watch session's virtual capital ledger. */
@Entity
@Table(name = "watch_capital_entry")
public class WatchCapitalEntryEntity {

    public enum Type {
        START,
        BET_SETTLED,
        COMMISSION,
        ADJUSTMENT
    }

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private Type entryType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false)
    private BigDecimal balanceAfter;

    @Column(name = "bet_id")
    private UUID betId;

    @Column(name = "market_id")
    private String marketId;

    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WatchCapitalEntryEntity() {
    }

    public WatchCapitalEntryEntity(UUID sessionId, int seq, Type entryType, BigDecimal amount, BigDecimal balanceAfter,
            UUID betId, String marketId, String description, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.sessionId = sessionId;
        this.seq = seq;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.betId = betId;
        this.marketId = marketId;
        this.description = description == null || description.length() <= 300 ? description : description.substring(0, 300);
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public int getSeq() { return seq; }
    public Type getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public UUID getBetId() { return betId; }
    public String getMarketId() { return marketId; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
}
