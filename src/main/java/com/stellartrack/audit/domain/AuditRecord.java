package com.stellartrack.audit.domain;

import java.util.Map;
import java.util.UUID;

/**
 * Immutable description of one audit event. Built with the fluent helpers so call sites stay short:
 * {@code AuditRecord.admin(adminId, AuditAction.LOGIN).entity("admin_user", id).outcome(SUCCESS)}.
 */
public record AuditRecord(
        AuditActorType actorType,
        String actorId,
        AuditAction action,
        String entityType,
        String entityId,
        AuditOutcome outcome,
        UUID configurationId,
        Integer configurationVersion,
        Map<String, Object> details) {

    public static AuditRecord admin(String actorId, AuditAction action) {
        return new AuditRecord(AuditActorType.ADMIN, actorId, action, null, null, AuditOutcome.SUCCESS, null, null, Map.of());
    }

    public static AuditRecord system(AuditAction action) {
        return new AuditRecord(AuditActorType.SYSTEM, "system", action, null, null, AuditOutcome.SUCCESS, null, null, Map.of());
    }

    public static AuditRecord anonymous(AuditAction action) {
        return new AuditRecord(AuditActorType.ANONYMOUS, null, action, null, null, AuditOutcome.FAILURE, null, null, Map.of());
    }

    public AuditRecord entity(String type, Object id) {
        return new AuditRecord(actorType, actorId, action, type, id == null ? null : id.toString(), outcome,
                configurationId, configurationVersion, details);
    }

    public AuditRecord outcome(AuditOutcome value) {
        return new AuditRecord(actorType, actorId, action, entityType, entityId, value,
                configurationId, configurationVersion, details);
    }

    public AuditRecord configuration(UUID id, Integer version) {
        return new AuditRecord(actorType, actorId, action, entityType, entityId, outcome, id, version, details);
    }

    public AuditRecord details(Map<String, Object> value) {
        return new AuditRecord(actorType, actorId, action, entityType, entityId, outcome,
                configurationId, configurationVersion, value == null ? Map.of() : value);
    }
}
