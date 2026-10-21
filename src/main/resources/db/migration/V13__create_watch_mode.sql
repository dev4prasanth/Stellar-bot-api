-- Watch Mode: a third trading mode next to MANUAL and AUTOMATIC. While WATCH is active no real bet can be placed;
-- running configurations place SIMULATED bets against a virtual capital instead. Every Watch record lives in its
-- own watch_* tables: nothing here is ever written to the order, settlement or capital tables, and no live report
-- or view reads the watch_* tables.

-- 1. Live control: WATCH is a valid global trading mode (order_intent.trading_mode stays MANUAL/AUTOMATIC: Watch
--    never creates real orders).
ALTER TABLE global_control DROP CONSTRAINT ck_global_control_trading_mode;
ALTER TABLE global_control
    ADD CONSTRAINT ck_global_control_trading_mode CHECK (trading_mode IN ('MANUAL', 'AUTOMATIC', 'WATCH'));

-- 2. Commission base rate (percent) from the Betfair market description, used to settle simulated bets with the
--    market's real commission terms.
ALTER TABLE market ADD COLUMN market_base_rate NUMERIC(6, 3);

-- 3. Watch worker lease (Live automation uses 'automation-worker').
INSERT INTO execution_lease (name) VALUES ('watch-worker');

-- 4. Sessions. One Watch period, from Start Watch to Stop Watch.
CREATE TABLE watch_session (
    id                        UUID PRIMARY KEY,
    name                      VARCHAR(120)  NOT NULL,
    status                    VARCHAR(10)   NOT NULL,
    started_at                TIMESTAMPTZ   NOT NULL,
    started_by                VARCHAR(100)  NOT NULL,
    stopped_at                TIMESTAMPTZ,
    stopped_by                VARCHAR(100),
    stop_reason               VARCHAR(500),
    previous_trading_mode     VARCHAR(10)   NOT NULL,
    resumed_trading_mode      VARCHAR(10),
    capital_source            VARCHAR(15)   NOT NULL,
    starting_capital          NUMERIC(14, 2) NOT NULL,
    currency_code             VARCHAR(3),
    real_funds_snapshot_id    UUID REFERENCES account_funds_snapshot (id),
    commission_discount_rate  NUMERIC(8, 4) NOT NULL DEFAULT 0,
    default_commission_rate   NUMERIC(6, 3) NOT NULL,
    fill_model_version        VARCHAR(20)   NOT NULL,
    slippage_ticks            INTEGER       NOT NULL,
    settlement_rule_version   VARCHAR(30)   NOT NULL,
    last_tick_at              TIMESTAMPTZ,
    last_error                VARCHAR(1000),
    notes                     VARCHAR(1000),
    CONSTRAINT ck_watch_session_status CHECK (status IN ('ACTIVE', 'STOPPED', 'FAILED')),
    CONSTRAINT ck_watch_session_previous_mode CHECK (previous_trading_mode IN ('MANUAL', 'AUTOMATIC')),
    CONSTRAINT ck_watch_session_resumed_mode CHECK (resumed_trading_mode IS NULL OR resumed_trading_mode IN ('MANUAL', 'AUTOMATIC')),
    CONSTRAINT ck_watch_session_capital_source CHECK (capital_source IN ('CUSTOM', 'REAL_BALANCE')),
    CONSTRAINT ck_watch_session_capital_positive CHECK (starting_capital > 0),
    CONSTRAINT ck_watch_session_slippage CHECK (slippage_ticks >= 0)
);

-- At most one active session (the global mode is a single switch).
CREATE UNIQUE INDEX ux_watch_session_active ON watch_session (status) WHERE status = 'ACTIVE';
CREATE INDEX ix_watch_session_started ON watch_session (started_at DESC);

-- 5. Configurations included in a session, pinned to the version current at start.
CREATE TABLE watch_session_configuration (
    id                      UUID PRIMARY KEY,
    session_id              UUID          NOT NULL REFERENCES watch_session (id),
    configuration_id        UUID          NOT NULL REFERENCES bot_configuration (id),
    configuration_version   INTEGER       NOT NULL,
    configuration_name      VARCHAR(200)  NOT NULL,
    selection_source        VARCHAR(30)   NOT NULL,
    strategy_code           VARCHAR(100),
    side                    VARCHAR(4)    NOT NULL,
    market_type             VARCHAR(5)    NOT NULL,
    staking_method          VARCHAR(40)   NOT NULL,
    state                   VARCHAR(10)   NOT NULL,
    state_reason            VARCHAR(500),
    notice                  VARCHAR(500),
    decisions               INTEGER       NOT NULL DEFAULT 0,
    bets_placed             INTEGER       NOT NULL DEFAULT 0,
    last_tick_at            TIMESTAMPTZ,
    CONSTRAINT uq_watch_session_configuration UNIQUE (session_id, configuration_id),
    CONSTRAINT ck_watch_session_configuration_state CHECK (state IN ('RUNNING', 'PAUSED', 'STOPPED'))
);

-- 6. Every evaluated runner (bet or no bet), with the same decision snapshot a live recommendation keeps.
CREATE TABLE watch_decision (
    id                          UUID PRIMARY KEY,
    session_id                  UUID          NOT NULL REFERENCES watch_session (id),
    session_configuration_id    UUID          NOT NULL REFERENCES watch_session_configuration (id),
    market_id                   VARCHAR(30)   NOT NULL,
    selection_id                BIGINT        NOT NULL,
    side                        VARCHAR(4)    NOT NULL,
    market_type                 VARCHAR(5)    NOT NULL,
    outcome                     VARCHAR(15)   NOT NULL,
    reasons                     VARCHAR(2000),
    snapshot                    JSONB         NOT NULL,
    proposed_price              NUMERIC(8, 2),
    proposed_stake              NUMERIC(14, 2),
    proposed_liability          NUMERIC(14, 2),
    created_at                  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_watch_decision UNIQUE (session_configuration_id, market_id, selection_id),
    CONSTRAINT ck_watch_decision_outcome CHECK (outcome IN ('BET_PLACED', 'SKIPPED', 'RISK_REJECTED'))
);

CREATE INDEX ix_watch_decision_session_created ON watch_decision (session_id, created_at DESC);

-- 7. Simulated bets.
--    matching_status: UNMATCHED (pending), PARTIALLY_MATCHED, MATCHED, LAPSED (never/partly matched, rest lapsed),
--    VOIDED (runner removed before any match).
--    result_status:   OPEN, NOT_MATCHED (nothing matched), WON, LOST, VOID, UNRESOLVED (result could not be settled
--    by the rules — never guessed).
CREATE TABLE watch_bet (
    id                          UUID PRIMARY KEY,
    session_id                  UUID          NOT NULL REFERENCES watch_session (id),
    session_configuration_id    UUID          NOT NULL REFERENCES watch_session_configuration (id),
    decision_id                 UUID          NOT NULL UNIQUE REFERENCES watch_decision (id),
    configuration_id            UUID          NOT NULL,
    configuration_version       INTEGER       NOT NULL,
    market_id                   VARCHAR(30)   NOT NULL,
    selection_id                BIGINT        NOT NULL,
    side                        VARCHAR(4)    NOT NULL,
    market_type                 VARCHAR(5)    NOT NULL,
    persistence_type            VARCHAR(20)   NOT NULL,
    requested_price             NUMERIC(8, 2) NOT NULL,
    requested_size              NUMERIC(14, 2) NOT NULL,
    limit_price                 NUMERIC(8, 2) NOT NULL,
    slippage_ticks              INTEGER       NOT NULL,
    size_matched                NUMERIC(14, 2) NOT NULL DEFAULT 0,
    average_price_matched       NUMERIC(10, 4),
    size_remaining              NUMERIC(14, 2) NOT NULL,
    size_lapsed                 NUMERIC(14, 2) NOT NULL DEFAULT 0,
    size_voided                 NUMERIC(14, 2) NOT NULL DEFAULT 0,
    max_liability               NUMERIC(14, 2) NOT NULL,
    matching_status             VARCHAR(20)   NOT NULL,
    result_status               VARCHAR(15)   NOT NULL,
    data_delay_ms               BIGINT,
    book_at_placement           JSONB         NOT NULL,
    lapse_reason                VARCHAR(300),
    placed_at                   TIMESTAMPTZ   NOT NULL,
    matched_at                  TIMESTAMPTZ,
    closed_at                   TIMESTAMPTZ,
    settled_at                  TIMESTAMPTZ,
    CONSTRAINT ck_watch_bet_matching CHECK (matching_status IN ('UNMATCHED', 'PARTIALLY_MATCHED', 'MATCHED', 'LAPSED', 'VOIDED')),
    CONSTRAINT ck_watch_bet_result CHECK (result_status IN ('OPEN', 'NOT_MATCHED', 'WON', 'LOST', 'VOID', 'UNRESOLVED')),
    CONSTRAINT ck_watch_bet_side CHECK (side IN ('BACK', 'LAY')),
    CONSTRAINT ck_watch_bet_sizes CHECK (requested_size > 0 AND size_matched >= 0 AND size_remaining >= 0)
);

CREATE INDEX ix_watch_bet_session_placed ON watch_bet (session_id, placed_at DESC);
CREATE INDEX ix_watch_bet_open ON watch_bet (market_id) WHERE closed_at IS NULL;
CREATE INDEX ix_watch_bet_unsettled ON watch_bet (market_id) WHERE settled_at IS NULL;

-- 8. Each simulated fill: the matching audit trail.
CREATE TABLE watch_bet_fill (
    id           UUID PRIMARY KEY,
    bet_id       UUID          NOT NULL REFERENCES watch_bet (id),
    seq          INTEGER       NOT NULL,
    price        NUMERIC(8, 2) NOT NULL,
    size         NUMERIC(14, 2) NOT NULL,
    reason       VARCHAR(20)   NOT NULL,
    book_as_of   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_watch_bet_fill UNIQUE (bet_id, seq),
    CONSTRAINT ck_watch_bet_fill_reason CHECK (reason IN ('IMMEDIATE', 'PRICE_CROSSED'))
);

-- 9. Settlement of a simulated bet, versioned by rule version (a re-settlement keeps the previous row).
CREATE TABLE watch_bet_settlement (
    id                       UUID PRIMARY KEY,
    bet_id                   UUID          NOT NULL REFERENCES watch_bet (id),
    rule_version             VARCHAR(30)   NOT NULL,
    is_current               BOOLEAN       NOT NULL,
    result                   VARCHAR(15)   NOT NULL,
    runner_status            VARCHAR(20),
    reduction_factor         NUMERIC(8, 4),
    dead_heat_divisor        INTEGER,
    settled_price            NUMERIC(10, 4),
    gross_profit             NUMERIC(14, 2) NOT NULL,
    commission               NUMERIC(14, 2) NOT NULL,
    net_profit               NUMERIC(14, 2) NOT NULL,
    best_case_gross_profit   NUMERIC(14, 2) NOT NULL,
    best_case_commission     NUMERIC(14, 2) NOT NULL,
    best_case_net_profit     NUMERIC(14, 2) NOT NULL,
    evidence                 JSONB         NOT NULL,
    settled_at               TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_watch_bet_settlement_result CHECK (result IN ('WON', 'LOST', 'VOID', 'NOT_MATCHED', 'UNRESOLVED'))
);

CREATE UNIQUE INDEX ux_watch_bet_settlement_current ON watch_bet_settlement (bet_id) WHERE is_current;

-- 10. Commission per session and market: Betfair charges commission on net market winnings, not per bet.
CREATE TABLE watch_market_commission (
    session_id                  UUID          NOT NULL REFERENCES watch_session (id),
    market_id                   VARCHAR(30)   NOT NULL,
    base_rate                   NUMERIC(6, 3) NOT NULL,
    discount_rate               NUMERIC(8, 4) NOT NULL,
    net_market_profit           NUMERIC(14, 2) NOT NULL,
    commission                  NUMERIC(14, 2) NOT NULL,
    best_case_net_market_profit NUMERIC(14, 2) NOT NULL,
    best_case_commission        NUMERIC(14, 2) NOT NULL,
    created_at                  TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (session_id, market_id)
);

-- 11. Append-only virtual capital ledger.
CREATE TABLE watch_capital_entry (
    id             UUID PRIMARY KEY,
    session_id     UUID          NOT NULL REFERENCES watch_session (id),
    seq            INTEGER       NOT NULL,
    entry_type     VARCHAR(15)   NOT NULL,
    amount         NUMERIC(14, 2) NOT NULL,
    balance_after  NUMERIC(14, 2) NOT NULL,
    bet_id         UUID REFERENCES watch_bet (id),
    market_id      VARCHAR(30),
    description    VARCHAR(300),
    created_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_watch_capital_entry UNIQUE (session_id, seq),
    CONSTRAINT ck_watch_capital_entry_type CHECK (entry_type IN ('START', 'BET_SETTLED', 'COMMISSION', 'ADJUSTMENT'))
);

-- 12. Reporting view: one row per simulated bet with its current settlement and race context. display_status is
--     the single definition of the status shown for a Watch bet (screens, filters and exports all read it here).
CREATE VIEW v_watch_bet AS
SELECT b.id                            AS bet_id,
       b.session_id,
       b.session_configuration_id,
       b.configuration_id,
       b.configuration_version,
       sc.configuration_name,
       sc.strategy_code,
       sc.staking_method,
       b.market_id,
       m.market_name,
       m.venue,
       m.market_start_time,
       b.selection_id,
       r.runner_name,
       b.side,
       b.market_type,
       b.persistence_type,
       b.requested_price,
       b.requested_size,
       b.limit_price,
       b.slippage_ticks,
       b.size_matched,
       b.average_price_matched,
       b.size_lapsed,
       b.size_voided,
       b.matching_status,
       b.result_status,
       CASE
           WHEN b.result_status = 'OPEN' AND b.matching_status = 'UNMATCHED' THEN 'PENDING'
           WHEN b.result_status = 'OPEN' AND b.matching_status = 'PARTIALLY_MATCHED' THEN 'PARTIALLY_MATCHED'
           WHEN b.result_status = 'OPEN' AND b.matching_status = 'VOIDED' THEN 'VOID'
           WHEN b.result_status = 'OPEN' AND b.size_matched > 0 THEN 'MATCHED'
           WHEN b.result_status = 'OPEN' THEN 'UNMATCHED'
           WHEN b.result_status = 'NOT_MATCHED' THEN 'UNMATCHED'
           ELSE b.result_status
       END                              AS display_status,
       b.lapse_reason,
       b.data_delay_ms,
       b.placed_at,
       b.matched_at,
       b.settled_at,
       s.settled_price,
       s.reduction_factor,
       s.dead_heat_divisor,
       s.gross_profit,
       s.commission,
       s.net_profit,
       s.best_case_gross_profit,
       s.best_case_net_profit,
       (d.snapshot -> 'marketState' ->> 'priceRank')::INTEGER AS price_rank,
       (d.snapshot ->> 'finalScore')::NUMERIC                AS final_score
FROM watch_bet b
JOIN watch_session_configuration sc ON sc.id = b.session_configuration_id
JOIN watch_decision d ON d.id = b.decision_id
LEFT JOIN market m ON m.market_id = b.market_id
LEFT JOIN market_runner r ON r.market_id = b.market_id AND r.selection_id = b.selection_id
LEFT JOIN watch_bet_settlement s ON s.bet_id = b.id AND s.is_current;
