package com.stellartrack.automation.persistence;

import com.stellartrack.configuration.domain.OperationalState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One automation session of a configuration (Start → Stop), pinned to a configuration version. */
@Entity
@Table(name = "automation_run")
public class AutomationRunEntity {

    @Id
    private UUID id;

    @Column(name = "configuration_id", nullable = false)
    private UUID configurationId;

    @Column(name = "configuration_version", nullable = false)
    private int configurationVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperationalState state;

    @Column(name = "started_by", nullable = false)
    private String startedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "stop_reason")
    private String stopReason;

    @Column(name = "entries_submitted", nullable = false)
    private int entriesSubmitted;

    @Column(name = "last_tick_at")
    private Instant lastTickAt;

    @Column(name = "last_error")
    private String lastError;

    protected AutomationRunEntity() {
    }

    public static AutomationRunEntity start(UUID configurationId, int version, String actor, Instant now) {
        AutomationRunEntity run = new AutomationRunEntity();
        run.id = UUID.randomUUID();
        run.configurationId = configurationId;
        run.configurationVersion = version;
        run.state = OperationalState.RUNNING;
        run.startedBy = actor;
        run.startedAt = now;
        return run;
    }

    public void ticked(Instant now, String pausedReason) {
        this.lastTickAt = now;
        this.lastError = truncate(pausedReason);
    }

    public void entrySubmitted() {
        entriesSubmitted++;
    }

    public void stop(OperationalState finalState, String reason, Instant now) {
        this.state = finalState;
        this.stopReason = truncate(reason);
        this.stoppedAt = now;
    }

    private static String truncate(String value) {
        return value == null ? null : value.length() > 500 ? value.substring(0, 500) : value;
    }

    public UUID getId() { return id; }
    public UUID getConfigurationId() { return configurationId; }
    public int getConfigurationVersion() { return configurationVersion; }
    public OperationalState getState() { return state; }
    public String getStartedBy() { return startedBy; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getStoppedAt() { return stoppedAt; }
    public String getStopReason() { return stopReason; }
    public int getEntriesSubmitted() { return entriesSubmitted; }
    public Instant getLastTickAt() { return lastTickAt; }
    public String getLastError() { return lastError; }
}
