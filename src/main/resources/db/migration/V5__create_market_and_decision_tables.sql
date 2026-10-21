-- Betfair market/runner reference data cached for decisions, reporting and post-race correlation,
-- plus recommendations, immutable decision snapshots, approval decisions and post-outcome evaluations.

CREATE TABLE market (
    market_id            VARCHAR(20)  PRIMARY KEY,
    event_id             VARCHAR(20)  NOT NULL,
    event_name           VARCHAR(255),
    venue                VARCHAR(120),
    country_code         VARCHAR(5),
    market_name          VARCHAR(255),
    market_type          VARCHAR(10)  NOT NULL,
    market_start_time    TIMESTAMPTZ  NOT NULL,
    number_of_winners    INTEGER,
    race_type            VARCHAR(60),
    status               VARCHAR(20),
    in_play              BOOLEAN      NOT NULL DEFAULT FALSE,
    catalogue_synced_at  TIMESTAMPTZ  NOT NULL,
    status_synced_at     TIMESTAMPTZ,
    result_captured_at   TIMESTAMPTZ,
    CONSTRAINT ck_market_type CHECK (market_type IN ('WIN', 'PLACE'))
);

CREATE INDEX ix_market_start_time ON market (market_start_time);
CREATE INDEX ix_market_unresolved ON market (market_start_time) WHERE result_captured_at IS NULL;

CREATE TABLE market_runner (
    market_id          VARCHAR(20)   NOT NULL REFERENCES market (market_id),
    selection_id       BIGINT        NOT NULL,
    runner_name        VARCHAR(255)  NOT NULL,
    sort_priority      INTEGER,
    handicap           NUMERIC(10, 2) NOT NULL DEFAULT 0,
    status             VARCHAR(20),
    adjustment_factor  NUMERIC(10, 4),
    removal_date       TIMESTAMPTZ,
    metadata           JSONB         NOT NULL DEFAULT '{}'::jsonb,
    updated_at         TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (market_id, selection_id)
);

CREATE TABLE recommendation (
    id                   UUID PRIMARY KEY,
    configuration_id     UUID REFERENCES bot_configuration (id),
    configuration_version INTEGER,
    mode                 VARCHAR(10)   NOT NULL,
    selection_source     VARCHAR(30)   NOT NULL,
    market_id            VARCHAR(20)   NOT NULL REFERENCES market (market_id),
    selection_id         BIGINT        NOT NULL,
    side                 VARCHAR(4)    NOT NULL,
    market_type          VARCHAR(10)   NOT NULL,
    status               VARCHAR(20)   NOT NULL,
    requires_approval    BOOLEAN       NOT NULL,
    strategy_code        VARCHAR(60)   NOT NULL,
    strategy_version     VARCHAR(30)   NOT NULL,
    proposed_price       NUMERIC(10, 2),
    proposed_price_at    TIMESTAMPTZ,
    proposed_stake       NUMERIC(19, 2),
    proposed_liability   NUMERIC(19, 2),
    staking_method       VARCHAR(30),
    no_bet_reason        VARCHAR(500),
    created_at           TIMESTAMPTZ   NOT NULL,
    decided_at           TIMESTAMPTZ,
    expires_at           TIMESTAMPTZ,
    row_version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_recommendation_mode CHECK (mode IN ('MANUAL', 'AUTOMATIC')),
    CONSTRAINT ck_recommendation_source CHECK (selection_source IN ('ADMIN_SELECTED', 'MARKET_CRITERIA', 'PREDICTION')),
    CONSTRAINT ck_recommendation_side CHECK (side IN ('BACK', 'LAY')),
    CONSTRAINT ck_recommendation_market_type CHECK (market_type IN ('WIN', 'PLACE')),
    CONSTRAINT ck_recommendation_status CHECK (status IN (
        'SKIPPED', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'EXPIRED', 'ACCEPTED')),
    CONSTRAINT ck_recommendation_skip_reason CHECK (status <> 'SKIPPED' OR no_bet_reason IS NOT NULL)
);

CREATE INDEX ix_recommendation_created ON recommendation (created_at DESC);
CREATE INDEX ix_recommendation_status ON recommendation (status);
CREATE INDEX ix_recommendation_market ON recommendation (market_id, selection_id);
CREATE INDEX ix_recommendation_configuration ON recommendation (configuration_id, created_at DESC);

-- Only one open proposal for the same runner/side may await review at a time.
CREATE UNIQUE INDEX uq_recommendation_pending_entry
    ON recommendation (market_id, selection_id, side) WHERE status = 'PENDING_APPROVAL';

-- Immutable as-of-decision record. Post-race information must never be written here.
CREATE TABLE decision_snapshot (
    id                       UUID PRIMARY KEY,
    recommendation_id        UUID          NOT NULL REFERENCES recommendation (id),
    snapshot_schema_version  INTEGER       NOT NULL,
    captured_at              TIMESTAMPTZ   NOT NULL,
    strategy_code            VARCHAR(60)   NOT NULL,
    strategy_version         VARCHAR(30)   NOT NULL,
    formula_reference        VARCHAR(255),
    layer_refs               JSONB         NOT NULL DEFAULT '[]'::jsonb,
    inputs                   JSONB         NOT NULL DEFAULT '[]'::jsonb,
    intermediate             JSONB         NOT NULL DEFAULT '[]'::jsonb,
    gates                    JSONB         NOT NULL DEFAULT '[]'::jsonb,
    flags                    JSONB         NOT NULL DEFAULT '[]'::jsonb,
    final_score              NUMERIC(24, 10),
    result                   VARCHAR(20)   NOT NULL,
    reasons                  JSONB         NOT NULL DEFAULT '[]'::jsonb,
    staking                  JSONB,
    market_state             JSONB         NOT NULL,
    CONSTRAINT uq_decision_snapshot_recommendation UNIQUE (recommendation_id)
);

CREATE TRIGGER trg_decision_snapshot_immutable
    BEFORE UPDATE OR DELETE ON decision_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_row_modification();

CREATE TABLE approval_decision (
    id                 UUID PRIMARY KEY,
    recommendation_id  UUID          NOT NULL REFERENCES recommendation (id),
    decision           VARCHAR(10)   NOT NULL,
    admin_id           UUID          NOT NULL REFERENCES admin_user (id),
    decided_at         TIMESTAMPTZ   NOT NULL,
    reason_code        VARCHAR(40),
    note               VARCHAR(1000),
    proposal_snapshot  JSONB         NOT NULL,
    CONSTRAINT uq_approval_decision_recommendation UNIQUE (recommendation_id),
    CONSTRAINT ck_approval_decision_value CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_approval_decision_reason CHECK (decision <> 'REJECTED' OR reason_code IS NOT NULL)
);

CREATE INDEX ix_approval_decision_decided ON approval_decision (decided_at DESC);

CREATE TRIGGER trg_approval_decision_immutable
    BEFORE UPDATE OR DELETE ON approval_decision
    FOR EACH ROW EXECUTE FUNCTION reject_row_modification();

-- Post-race selection-outcome analysis. Hypothetical values here never feed realized P&L.
CREATE TABLE decision_outcome_evaluation (
    id                    UUID PRIMARY KEY,
    recommendation_id     UUID          NOT NULL REFERENCES recommendation (id),
    rule_version          VARCHAR(30)   NOT NULL,
    evaluated_at          TIMESTAMPTZ   NOT NULL,
    classification        VARCHAR(20)   NOT NULL,
    runner_result         VARCHAR(20),
    dead_heat             BOOLEAN       NOT NULL DEFAULT FALSE,
    evidence              JSONB         NOT NULL,
    hypothetical_profit   NUMERIC(19, 2),
    hypothetical_basis    JSONB,
    CONSTRAINT uq_decision_outcome_evaluation UNIQUE (recommendation_id, rule_version),
    CONSTRAINT ck_decision_outcome_classification CHECK (
        classification IN ('FAVORABLE', 'UNFAVORABLE', 'VOID', 'NOT_EVALUABLE'))
);
