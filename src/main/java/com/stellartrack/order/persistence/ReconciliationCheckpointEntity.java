package com.stellartrack.order.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Last attempt/success of a named reconciliation job, surfaced in the operations view. */
@Entity
@Table(name = "reconciliation_checkpoint")
public class ReconciliationCheckpointEntity {

    @Id
    private String name;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "cursor_value")
    private String cursorValue;

    protected ReconciliationCheckpointEntity() {
    }

    public ReconciliationCheckpointEntity(String name) {
        this.name = name;
    }

    public void attempted(Instant now) {
        this.lastAttemptAt = now;
    }

    public void succeeded(Instant now) {
        this.lastSuccessAt = now;
        this.lastError = null;
    }

    public void failed(String error) {
        this.lastError = error == null ? null : error.length() > 1000 ? error.substring(0, 1000) : error;
    }

    public String getName() { return name; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public String getLastError() { return lastError; }
}
