package com.stellartrack.audit.application;

import com.stellartrack.audit.domain.AuditRecord;

/** Write port for audit events, depended on by every module that performs auditable actions. */
public interface AuditTrail {

    /** Records the event inside the caller's transaction: it is persisted only if the business change commits. */
    void record(AuditRecord record);

    /** Records the event in its own transaction so it survives a rollback (failures, denials, security events). */
    void recordIndependently(AuditRecord record);
}
