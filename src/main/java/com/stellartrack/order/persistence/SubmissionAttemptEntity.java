package com.stellartrack.order.persistence;

import com.stellartrack.order.port.PlacementResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One call to placeOrders for an intent, with the provider's statuses and instruction-level error codes. */
@Entity
@Table(name = "submission_attempt")
public class SubmissionAttemptEntity {

    @Id
    private UUID id;

    @Column(name = "intent_id", nullable = false)
    private UUID intentId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    private PlacementResult.Outcome outcome;

    @Column(name = "provider_status")
    private String providerStatus;

    @Column(name = "provider_error_code")
    private String providerErrorCode;

    @Column(name = "instruction_status")
    private String instructionStatus;

    @Column(name = "instruction_error_code")
    private String instructionErrorCode;

    @Column(name = "bet_id")
    private String betId;

    private String detail;

    protected SubmissionAttemptEntity() {
    }

    public SubmissionAttemptEntity(UUID intentId, int attemptNo, Instant startedAt) {
        this.id = UUID.randomUUID();
        this.intentId = intentId;
        this.attemptNo = attemptNo;
        this.startedAt = startedAt;
    }

    public void finish(PlacementResult result, Instant now) {
        this.finishedAt = now;
        this.outcome = result.outcome();
        this.providerStatus = result.providerStatus();
        this.providerErrorCode = result.providerErrorCode();
        this.instructionStatus = result.instructionStatus();
        this.instructionErrorCode = result.instructionErrorCode();
        this.betId = result.betId();
        this.detail = result.detail() == null ? null
                : result.detail().length() > 1000 ? result.detail().substring(0, 1000) : result.detail();
    }

    public UUID getId() { return id; }
    public UUID getIntentId() { return intentId; }
    public int getAttemptNo() { return attemptNo; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public PlacementResult.Outcome getOutcome() { return outcome; }
    public String getProviderStatus() { return providerStatus; }
    public String getProviderErrorCode() { return providerErrorCode; }
    public String getInstructionStatus() { return instructionStatus; }
    public String getInstructionErrorCode() { return instructionErrorCode; }
    public String getBetId() { return betId; }
    public String getDetail() { return detail; }
}
