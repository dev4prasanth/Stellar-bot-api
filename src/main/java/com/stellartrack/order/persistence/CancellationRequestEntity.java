package com.stellartrack.order.persistence;

import com.stellartrack.order.port.CancellationResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cancellation_request")
public class CancellationRequestEntity {

    @Id
    private UUID id;

    @Column(name = "bet_id", nullable = false)
    private String betId;

    @Column(name = "size_reduction")
    private BigDecimal sizeReduction;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    private CancellationResult.Outcome outcome;

    @Column(name = "size_cancelled")
    private BigDecimal sizeCancelled;

    @Column(name = "error_code")
    private String errorCode;

    protected CancellationRequestEntity() {
    }

    public CancellationRequestEntity(String betId, BigDecimal sizeReduction, String requestedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.betId = betId;
        this.sizeReduction = sizeReduction;
        this.requestedBy = requestedBy;
        this.requestedAt = now;
    }

    public void complete(CancellationResult result, Instant now) {
        this.outcome = result.outcome();
        this.sizeCancelled = result.sizeCancelled();
        this.errorCode = result.errorCode();
        this.completedAt = now;
    }

    public UUID getId() { return id; }
    public String getBetId() { return betId; }
    public BigDecimal getSizeReduction() { return sizeReduction; }
    public String getRequestedBy() { return requestedBy; }
    public CancellationResult.Outcome getOutcome() { return outcome; }
    public BigDecimal getSizeCancelled() { return sizeCancelled; }
    public String getErrorCode() { return errorCode; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
