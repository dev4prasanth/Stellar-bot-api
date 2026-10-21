-- Append-only audit trail. UPDATE and DELETE are rejected by trigger so application code
-- (or an operator using the application role) cannot edit history.

CREATE FUNCTION reject_row_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE audit_event (
    id                     BIGSERIAL PRIMARY KEY,
    occurred_at            TIMESTAMPTZ  NOT NULL,
    actor_type             VARCHAR(20)  NOT NULL,
    actor_id               VARCHAR(100),
    action                 VARCHAR(80)  NOT NULL,
    entity_type            VARCHAR(60),
    entity_id              VARCHAR(100),
    outcome                VARCHAR(20)  NOT NULL,
    correlation_id         VARCHAR(64),
    configuration_id       UUID,
    configuration_version  INTEGER,
    details                JSONB        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_audit_event_actor_type CHECK (actor_type IN ('ADMIN', 'SYSTEM', 'ANONYMOUS')),
    CONSTRAINT ck_audit_event_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED', 'INFO'))
);

CREATE INDEX ix_audit_event_occurred_at ON audit_event (occurred_at DESC);
CREATE INDEX ix_audit_event_entity ON audit_event (entity_type, entity_id);
CREATE INDEX ix_audit_event_action ON audit_event (action);
CREATE INDEX ix_audit_event_correlation ON audit_event (correlation_id);

CREATE TRIGGER trg_audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_row_modification();
