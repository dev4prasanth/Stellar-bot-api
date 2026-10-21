package com.stellartrack.audit.api;

import com.stellartrack.audit.domain.AuditAction;
import com.stellartrack.audit.domain.AuditActorType;
import com.stellartrack.audit.domain.AuditOutcome;
import com.stellartrack.audit.persistence.AuditEventEntity;
import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        long id,
        Instant occurredAt,
        AuditActorType actorType,
        String actorId,
        AuditAction action,
        String entityType,
        String entityId,
        AuditOutcome outcome,
        String correlationId,
        UUID configurationId,
        Integer configurationVersion,
        String details) {

    static AuditEventResponse from(AuditEventEntity e) {
        return new AuditEventResponse(e.getId(), e.getOccurredAt(), e.getActorType(), e.getActorId(), e.getAction(),
                e.getEntityType(), e.getEntityId(), e.getOutcome(), e.getCorrelationId(), e.getConfigurationId(),
                e.getConfigurationVersion(), e.getDetails());
    }
}
