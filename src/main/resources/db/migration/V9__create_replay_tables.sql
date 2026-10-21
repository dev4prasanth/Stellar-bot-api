-- Historical Replay jobs. Replay is technically isolated from order submission: no table here
-- references order_intent, and replay results are never written to recommendation/order tables.

CREATE TABLE replay_job (
    id                     UUID PRIMARY KEY,
    original_filename      VARCHAR(255)  NOT NULL,
    content_type           VARCHAR(100),
    size_bytes             BIGINT        NOT NULL,
    sha256                 VARCHAR(64)    NOT NULL,
    storage_key            VARCHAR(255)  NOT NULL,
    status                 VARCHAR(20)   NOT NULL,
    status_reason          VARCHAR(1000),
    configuration_id       UUID REFERENCES bot_configuration (id),
    configuration_version  INTEGER,
    engine_version         VARCHAR(30),
    total_rows             INTEGER,
    processed_rows         INTEGER       NOT NULL DEFAULT 0,
    errors                 JSONB         NOT NULL DEFAULT '[]'::jsonb,
    result                 JSONB,
    created_by             VARCHAR(100)  NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL,
    started_at             TIMESTAMPTZ,
    finished_at            TIMESTAMPTZ,
    CONSTRAINT ck_replay_job_status CHECK (status IN (
        'UPLOADED', 'VALIDATING', 'VALIDATION_FAILED', 'BLOCKED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_replay_job_size CHECK (size_bytes > 0)
);

CREATE INDEX ix_replay_job_created ON replay_job (created_at DESC);
