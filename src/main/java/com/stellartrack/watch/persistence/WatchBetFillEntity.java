package com.stellartrack.watch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One simulated fill of a Watch bet: the matching audit trail. */
@Entity
@Table(name = "watch_bet_fill")
public class WatchBetFillEntity {

    public static final String IMMEDIATE = "IMMEDIATE";
    public static final String PRICE_CROSSED = "PRICE_CROSSED";

    @Id
    private UUID id;

    @Column(name = "bet_id", nullable = false)
    private UUID betId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private BigDecimal size;

    @Column(nullable = false)
    private String reason;

    @Column(name = "book_as_of")
    private Instant bookAsOf;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WatchBetFillEntity() {
    }

    public WatchBetFillEntity(UUID betId, int seq, BigDecimal price, BigDecimal size, String reason, Instant bookAsOf,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.betId = betId;
        this.seq = seq;
        this.price = price;
        this.size = size;
        this.reason = reason;
        this.bookAsOf = bookAsOf;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getBetId() { return betId; }
    public int getSeq() { return seq; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getSize() { return size; }
    public String getReason() { return reason; }
    public Instant getBookAsOf() { return bookAsOf; }
    public Instant getCreatedAt() { return createdAt; }
}
