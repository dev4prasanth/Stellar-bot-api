-- Order lifecycle: intention (persisted before contacting Betfair) -> submission attempts ->
-- Betfair orders (matched/unmatched quantities) -> cancellations -> settlements (with corrections).

CREATE TABLE order_intent (
    id                     UUID PRIMARY KEY,
    recommendation_id      UUID REFERENCES recommendation (id),
    configuration_id       UUID REFERENCES bot_configuration (id),
    configuration_version  INTEGER,
    mode                   VARCHAR(10)    NOT NULL,
    purpose                VARCHAR(15)    NOT NULL,
    linked_intent_id       UUID REFERENCES order_intent (id),
    market_id              VARCHAR(20)    NOT NULL REFERENCES market (market_id),
    selection_id           BIGINT         NOT NULL,
    handicap               NUMERIC(10, 2) NOT NULL DEFAULT 0,
    side                   VARCHAR(4)     NOT NULL,
    market_type            VARCHAR(10)    NOT NULL,
    requested_price        NUMERIC(10, 2) NOT NULL,
    requested_size         NUMERIC(19, 2) NOT NULL,
    persistence_type       VARCHAR(20)    NOT NULL,
    staking                JSONB          NOT NULL,
    idempotency_key        VARCHAR(64)     NOT NULL,
    customer_ref           VARCHAR(32)    NOT NULL,
    customer_order_ref     VARCHAR(32)    NOT NULL,
    status                 VARCHAR(25)    NOT NULL,
    status_reason          VARCHAR(500),
    created_by             VARCHAR(100)   NOT NULL,
    created_at             TIMESTAMPTZ    NOT NULL,
    updated_at             TIMESTAMPTZ    NOT NULL,
    row_version            BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_order_intent_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_order_intent_customer_ref UNIQUE (customer_ref),
    CONSTRAINT uq_order_intent_customer_order_ref UNIQUE (customer_order_ref),
    CONSTRAINT ck_order_intent_mode CHECK (mode IN ('MANUAL', 'AUTOMATIC')),
    CONSTRAINT ck_order_intent_purpose CHECK (purpose IN ('ENTRY', 'HEDGE', 'CLOSE_OUT')),
    CONSTRAINT ck_order_intent_link CHECK (purpose = 'ENTRY' OR linked_intent_id IS NOT NULL),
    CONSTRAINT ck_order_intent_side CHECK (side IN ('BACK', 'LAY')),
    CONSTRAINT ck_order_intent_market_type CHECK (market_type IN ('WIN', 'PLACE')),
    CONSTRAINT ck_order_intent_persistence CHECK (persistence_type IN ('LAPSE', 'PERSIST', 'MARKET_ON_CLOSE')),
    CONSTRAINT ck_order_intent_price CHECK (requested_price > 1 AND requested_price <= 1000),
    CONSTRAINT ck_order_intent_size CHECK (requested_size > 0),
    CONSTRAINT ck_order_intent_status CHECK (status IN (
        'PENDING_SUBMISSION', 'SUBMITTING', 'PLACED', 'EXCHANGE_REJECTED', 'UNKNOWN_OUTCOME',
        'TECHNICAL_FAILURE', 'NOT_FOUND_AFTER_RECONCILIATION'))
);

CREATE INDEX ix_order_intent_created ON order_intent (created_at DESC);
CREATE INDEX ix_order_intent_status ON order_intent (status);
CREATE INDEX ix_order_intent_configuration ON order_intent (configuration_id, created_at DESC);

-- Cross-mode, cross-configuration duplicate-entry prevention. Linked HEDGE/CLOSE_OUT orders are exempt.
CREATE UNIQUE INDEX uq_order_intent_live_entry
    ON order_intent (market_id, selection_id, side)
    WHERE purpose = 'ENTRY' AND status IN ('PENDING_SUBMISSION', 'SUBMITTING', 'PLACED', 'UNKNOWN_OUTCOME');

CREATE TABLE submission_attempt (
    id                      UUID PRIMARY KEY,
    intent_id               UUID          NOT NULL REFERENCES order_intent (id),
    attempt_no              INTEGER       NOT NULL,
    started_at              TIMESTAMPTZ   NOT NULL,
    finished_at             TIMESTAMPTZ,
    outcome                 VARCHAR(25),
    provider_status         VARCHAR(30),
    provider_error_code     VARCHAR(60),
    instruction_status      VARCHAR(30),
    instruction_error_code  VARCHAR(60),
    bet_id                  VARCHAR(30),
    detail                  VARCHAR(1000),
    CONSTRAINT uq_submission_attempt UNIQUE (intent_id, attempt_no),
    CONSTRAINT ck_submission_attempt_outcome CHECK (outcome IS NULL OR outcome IN (
        'PLACED', 'EXCHANGE_REJECTED', 'UNKNOWN_OUTCOME', 'TECHNICAL_FAILURE'))
);

CREATE TABLE bet_order (
    bet_id                 VARCHAR(30)    PRIMARY KEY,
    intent_id              UUID REFERENCES order_intent (id),
    market_id              VARCHAR(20)    NOT NULL,
    selection_id           BIGINT         NOT NULL,
    handicap               NUMERIC(10, 2) NOT NULL DEFAULT 0,
    side                   VARCHAR(4)     NOT NULL,
    order_type             VARCHAR(20),
    persistence_type       VARCHAR(20),
    price_requested        NUMERIC(10, 2),
    size_requested         NUMERIC(19, 2),
    average_price_matched  NUMERIC(10, 4),
    size_matched           NUMERIC(19, 2) NOT NULL DEFAULT 0,
    size_remaining         NUMERIC(19, 2) NOT NULL DEFAULT 0,
    size_lapsed            NUMERIC(19, 2) NOT NULL DEFAULT 0,
    size_cancelled         NUMERIC(19, 2) NOT NULL DEFAULT 0,
    size_voided            NUMERIC(19, 2) NOT NULL DEFAULT 0,
    status                 VARCHAR(25)    NOT NULL,
    placed_at              TIMESTAMPTZ,
    matched_at             TIMESTAMPTZ,
    customer_order_ref     VARCHAR(32),
    customer_strategy_ref  VARCHAR(15),
    last_source            VARCHAR(10)    NOT NULL,
    provider_updated_at    TIMESTAMPTZ    NOT NULL,
    created_at             TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_bet_order_intent UNIQUE (intent_id),
    CONSTRAINT ck_bet_order_side CHECK (side IN ('BACK', 'LAY')),
    CONSTRAINT ck_bet_order_status CHECK (status IN ('EXECUTABLE', 'EXECUTION_COMPLETE')),
    CONSTRAINT ck_bet_order_source CHECK (last_source IN ('PLACE', 'POLL', 'STREAM', 'CLEARED')),
    CONSTRAINT ck_bet_order_sizes CHECK (size_matched >= 0 AND size_remaining >= 0 AND size_lapsed >= 0
        AND size_cancelled >= 0 AND size_voided >= 0)
);

CREATE INDEX ix_bet_order_market ON bet_order (market_id);
CREATE INDEX ix_bet_order_open ON bet_order (market_id) WHERE status = 'EXECUTABLE';

CREATE TABLE cancellation_request (
    id                UUID PRIMARY KEY,
    bet_id            VARCHAR(30)    NOT NULL REFERENCES bet_order (bet_id),
    size_reduction    NUMERIC(19, 2),
    requested_by      VARCHAR(100)   NOT NULL,
    requested_at      TIMESTAMPTZ    NOT NULL,
    completed_at      TIMESTAMPTZ,
    outcome           VARCHAR(25),
    size_cancelled    NUMERIC(19, 2),
    error_code        VARCHAR(60),
    CONSTRAINT ck_cancellation_outcome CHECK (outcome IS NULL OR outcome IN (
        'SUCCESS', 'FAILURE', 'UNKNOWN_OUTCOME'))
);

-- Bet-level settlement facts from listClearedOrders. Betfair may report one betId under several statuses
-- (e.g. the matched part SETTLED and the unmatched part LAPSED), so a settlement unit is (bet_id, bet_status).
-- Corrections add a new revision for that unit and keep history.
CREATE TABLE settlement (
    id                UUID PRIMARY KEY,
    bet_id            VARCHAR(30)    NOT NULL REFERENCES bet_order (bet_id),
    revision          INTEGER        NOT NULL,
    is_current        BOOLEAN        NOT NULL,
    bet_status        VARCHAR(15)    NOT NULL,
    bet_outcome       VARCHAR(20),
    settled_at        TIMESTAMPTZ,
    price_requested   NUMERIC(10, 2),
    price_matched     NUMERIC(10, 4),
    price_reduced     BOOLEAN,
    size_settled      NUMERIC(19, 2),
    size_cancelled    NUMERIC(19, 2),
    profit            NUMERIC(19, 2),
    last_matched_at   TIMESTAMPTZ,
    fingerprint       VARCHAR(64)     NOT NULL,
    received_at       TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_settlement_revision UNIQUE (bet_id, bet_status, revision),
    CONSTRAINT ck_settlement_bet_status CHECK (bet_status IN ('SETTLED', 'VOIDED', 'LAPSED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uq_settlement_current ON settlement (bet_id, bet_status) WHERE is_current;
CREATE INDEX ix_settlement_settled_at ON settlement (settled_at) WHERE is_current;

-- Market-level settlement (commission is only reported by Betfair at market grain).
CREATE TABLE market_settlement (
    id            UUID PRIMARY KEY,
    market_id     VARCHAR(20)    NOT NULL,
    revision      INTEGER        NOT NULL,
    is_current    BOOLEAN        NOT NULL,
    settled_at    TIMESTAMPTZ,
    profit        NUMERIC(19, 2),
    commission    NUMERIC(19, 2),
    fingerprint   VARCHAR(64)     NOT NULL,
    received_at   TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_market_settlement_revision UNIQUE (market_id, revision)
);

CREATE UNIQUE INDEX uq_market_settlement_current ON market_settlement (market_id) WHERE is_current;

CREATE TABLE reconciliation_checkpoint (
    name              VARCHAR(60)   PRIMARY KEY,
    last_attempt_at   TIMESTAMPTZ,
    last_success_at   TIMESTAMPTZ,
    last_error        VARCHAR(1000),
    cursor_value      VARCHAR(255)
);
