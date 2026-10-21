package com.stellartrack.configuration.persistence;

import com.stellartrack.common.exception.ConflictException;
import com.stellartrack.configuration.domain.OperationalState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "bot_configuration")
public class BotConfigurationEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_state", nullable = false)
    private OperationalState operationalState;

    @Column(name = "state_reason")
    private String stateReason;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @Column(name = "active_version")
    private Integer activeVersion;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected BotConfigurationEntity() {
    }

    public static BotConfigurationEntity create(String name, Instant now) {
        BotConfigurationEntity e = new BotConfigurationEntity();
        e.id = UUID.randomUUID();
        e.name = name;
        e.operationalState = OperationalState.STOPPED;
        e.currentVersion = 1;
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public void requireEditable() {
        if (archivedAt != null) {
            throw new ConflictException("CONFIGURATION_ARCHIVED", "Archived configurations cannot be changed");
        }
        if (operationalState != OperationalState.STOPPED && operationalState != OperationalState.ERROR) {
            throw new ConflictException("CONFIGURATION_NOT_STOPPED",
                    "Stop the configuration before making material changes", Map.of("state", operationalState.name()));
        }
    }

    public int nextVersion(Instant now) {
        currentVersion++;
        updatedAt = now;
        return currentVersion;
    }

    public void rename(String newName, Instant now) {
        this.name = newName;
        this.updatedAt = now;
    }

    public void archive(Instant now) {
        requireEditable();
        operationalState = OperationalState.STOPPED;
        archivedAt = now;
        updatedAt = now;
    }

    /** Validated lifecycle transition; RUNNING pins the version being executed. */
    public void transition(OperationalState target, String reason, Instant now) {
        if (!operationalState.canTransitionTo(target)) {
            throw new ConflictException("INVALID_STATE_TRANSITION",
                    "Cannot move from " + operationalState + " to " + target);
        }
        if (target == OperationalState.STARTING) {
            activeVersion = currentVersion;
        }
        if (target == OperationalState.STOPPED) {
            activeVersion = null;
        }
        operationalState = target;
        stateReason = reason;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public OperationalState getOperationalState() { return operationalState; }
    public String getStateReason() { return stateReason; }
    public int getCurrentVersion() { return currentVersion; }
    public Integer getActiveVersion() { return activeVersion; }
    public Instant getArchivedAt() { return archivedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
