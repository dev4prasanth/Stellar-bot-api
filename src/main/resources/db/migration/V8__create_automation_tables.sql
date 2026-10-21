-- Automatic Mode control state: global Stop All switch, per-configuration runs and the
-- single-owner execution lease that prevents multiple workers from placing the same entry.

CREATE TABLE global_control (
    id               SMALLINT PRIMARY KEY,
    stop_all         BOOLEAN       NOT NULL DEFAULT FALSE,
    stop_all_reason  VARCHAR(500),
    changed_by       VARCHAR(100),
    changed_at       TIMESTAMPTZ,
    CONSTRAINT ck_global_control_singleton CHECK (id = 1)
);

INSERT INTO global_control (id, stop_all) VALUES (1, FALSE);

CREATE TABLE automation_run (
    id                     UUID PRIMARY KEY,
    configuration_id       UUID          NOT NULL REFERENCES bot_configuration (id),
    configuration_version  INTEGER       NOT NULL,
    state                  VARCHAR(20)   NOT NULL,
    started_by             VARCHAR(100)  NOT NULL,
    started_at             TIMESTAMPTZ   NOT NULL,
    stopped_at             TIMESTAMPTZ,
    stop_reason            VARCHAR(500),
    entries_submitted      INTEGER       NOT NULL DEFAULT 0,
    last_tick_at           TIMESTAMPTZ,
    last_error             VARCHAR(1000),
    CONSTRAINT ck_automation_run_state CHECK (state IN ('STARTING', 'RUNNING', 'STOPPING', 'STOPPED', 'ERROR'))
);

CREATE UNIQUE INDEX uq_automation_run_active ON automation_run (configuration_id) WHERE stopped_at IS NULL;
CREATE INDEX ix_automation_run_started ON automation_run (started_at DESC);

CREATE TABLE execution_lease (
    name         VARCHAR(60)  PRIMARY KEY,
    owner_id     VARCHAR(120),
    acquired_at  TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ
);

INSERT INTO execution_lease (name) VALUES ('automation-worker');
