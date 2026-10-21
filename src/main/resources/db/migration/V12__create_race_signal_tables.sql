-- Typed read model of the accepted Layer 1/2/3 messages (contracts l1-prediction-v1, l2-planetary-state-v1,
-- l3-ranking-v1). Each row holds the latest revision and traces back to its message_receipt and
-- layer_source_record. These rows are inputs for display and future approved rules only; nothing here places bets.

CREATE TABLE race_signal_card (
    card_id               VARCHAR(40)  PRIMARY KEY,
    venue_id              VARCHAR(20)  NOT NULL,
    race_date             DATE         NOT NULL,
    total_races           INTEGER,
    in_planets            JSONB,
    out_planets           JSONB,
    scenario              VARCHAR(20),
    current_out_streak    INTEGER,
    last_race_processed   INTEGER,
    context_updated_at    TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_race_signal_card_date ON race_signal_card (race_date, venue_id);

-- Layer 1 prediction for race N (race 1 is never predicted). revision counts accepted revisions.
CREATE TABLE race_signal_l1_prediction (
    card_id      VARCHAR(40)  NOT NULL REFERENCES race_signal_card (card_id),
    race_number  INTEGER      NOT NULL,
    revision     INTEGER      NOT NULL,
    runners      JSONB        NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL,
    receipt_id   UUID         NOT NULL REFERENCES message_receipt (id),
    record_id    UUID         NOT NULL REFERENCES layer_source_record (id),
    PRIMARY KEY (card_id, race_number),
    CONSTRAINT ck_race_signal_l1_race CHECK (race_number >= 2)
);

-- Layer 2 planetary state after race N's result (Layer 3 uses it to rank race N + 1).
CREATE TABLE race_signal_planetary_state (
    card_id               VARCHAR(40)  NOT NULL REFERENCES race_signal_card (card_id),
    race_number           INTEGER      NOT NULL,
    planet                VARCHAR(10)  NOT NULL,
    state_code            VARCHAR(40),
    state_name            VARCHAR(80),
    strength_score        INTEGER,
    authority_level       VARCHAR(20),
    momentum              VARCHAR(40),
    win_count             INTEGER,
    top4_count            INTEGER,
    absence_duration      INTEGER,
    races_since_last_win  INTEGER,
    triggering_rules      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    context_flags         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    position_trend        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    ruleset_version       VARCHAR(40),
    source_event_at       TIMESTAMPTZ  NOT NULL,
    received_at           TIMESTAMPTZ  NOT NULL,
    receipt_id            UUID         NOT NULL REFERENCES message_receipt (id),
    record_id             UUID         NOT NULL REFERENCES layer_source_record (id),
    PRIMARY KEY (card_id, race_number, planet),
    CONSTRAINT ck_race_signal_planet CHECK (planet IN ('MARS', 'VENUS', 'MERCURY', 'MOON', 'SUN')),
    CONSTRAINT ck_race_signal_strength CHECK (strength_score IS NULL OR strength_score BETWEEN 0 AND 100)
);

-- Layer 3 final ranking for race N, one row per prediction source.
CREATE TABLE race_signal_l3_ranking (
    card_id                VARCHAR(40)  NOT NULL REFERENCES race_signal_card (card_id),
    race_number            INTEGER      NOT NULL,
    source                 VARCHAR(20)  NOT NULL,
    rerank_policy_version  VARCHAR(40),
    generated_at           TIMESTAMPTZ  NOT NULL,
    rankings               JSONB        NOT NULL,
    nap_horse_number       INTEGER,
    received_at            TIMESTAMPTZ  NOT NULL,
    receipt_id             UUID         NOT NULL REFERENCES message_receipt (id),
    record_id              UUID         NOT NULL REFERENCES layer_source_record (id),
    PRIMARY KEY (card_id, race_number, source),
    CONSTRAINT ck_race_signal_l3_source CHECK (source IN ('LAYER1_RERANKED', 'STANDALONE'))
);
