-- Named bot configurations with immutable, versioned settings snapshots.
-- Settings are validated by the application against a typed model before being stored as JSONB;
-- a configuration runs a single pinned version (active_version) while edits create new versions.

CREATE TABLE bot_configuration (
    id                 UUID PRIMARY KEY,
    name               VARCHAR(120) NOT NULL,
    operational_state  VARCHAR(20)  NOT NULL DEFAULT 'STOPPED',
    state_reason       VARCHAR(500),
    current_version    INTEGER      NOT NULL,
    active_version     INTEGER,
    archived_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    row_version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_bot_configuration_state CHECK (
        operational_state IN ('STOPPED', 'STARTING', 'RUNNING', 'STOPPING', 'ERROR')),
    CONSTRAINT ck_bot_configuration_archived_stopped CHECK (archived_at IS NULL OR operational_state = 'STOPPED'),
    CONSTRAINT ck_bot_configuration_version CHECK (current_version >= 1)
);

CREATE UNIQUE INDEX uq_bot_configuration_name_live ON bot_configuration (lower(name)) WHERE archived_at IS NULL;

CREATE TABLE bot_configuration_version (
    id                       UUID PRIMARY KEY,
    configuration_id         UUID         NOT NULL REFERENCES bot_configuration (id),
    version                  INTEGER      NOT NULL,
    settings_schema_version  INTEGER      NOT NULL,
    settings                 JSONB        NOT NULL,
    change_note              VARCHAR(500),
    created_by               VARCHAR(100) NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_bot_configuration_version UNIQUE (configuration_id, version)
);

CREATE TRIGGER trg_bot_configuration_version_immutable
    BEFORE UPDATE OR DELETE ON bot_configuration_version
    FOR EACH ROW EXECUTE FUNCTION reject_row_modification();

-- Declaratively imported staking plans (CSV/XLSX). Uploaded files are parsed as data only.
CREATE TABLE staking_plan (
    id                 UUID PRIMARY KEY,
    name               VARCHAR(120) NOT NULL,
    source_filename    VARCHAR(255) NOT NULL,
    source_sha256      VARCHAR(64)   NOT NULL,
    schema_version     INTEGER      NOT NULL,
    steps              JSONB        NOT NULL,
    step_count         INTEGER      NOT NULL,
    created_by         VARCHAR(100) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_staking_plan_name UNIQUE (name),
    CONSTRAINT ck_staking_plan_steps CHECK (step_count > 0)
);
