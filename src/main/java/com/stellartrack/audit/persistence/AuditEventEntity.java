package com.stellartrack.audit.persistence;

import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditActorType;
import com.stellartrack.audit.domain.AuditOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private AuditActorType actorType;

    @Column(name = "actor_id")
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private AuditAction action;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private AuditOutcome outcome;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "configuration_id")
    private UUID configurationId;

    @Column(name = "configuration_version")
    private Integer configurationVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false)
    private String details;

    protected AuditEventEntity() {
    }

    public AuditEventEntity(Instant occurredAt, AuditActorType actorType, String actorId, AuditAction action,
            String entityType, String entityId, AuditOutcome outcome, String correlationId, UUID configurationId,
            Integer configurationVersion, String details) {
        this.occurredAt = occurredAt;
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.outcome = outcome;
        this.correlationId = correlationId;
        this.configurationId = configurationId;
        this.configurationVersion = configurationVersion;
        this.details = details;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public AuditActorType getActorType() { return actorType; }
    public String getActorId() { return actorId; }
    public AuditAction getAction() { return action; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public AuditOutcome getOutcome() { return outcome; }
    public String getCorrelationId() { return correlationId; }
    public UUID getConfigurationId() { return configurationId; }
    public Integer getConfigurationVersion() { return configurationVersion; }
    public String getDetails() { return details; }
}
