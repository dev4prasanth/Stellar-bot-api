-- Account funds snapshots and synchronized Betfair account statement items.
-- Internal capital allocations live in configuration settings; they are limits, not separate wallets.

CREATE TABLE account_funds_snapshot (
    id                    UUID PRIMARY KEY,
    captured_at           TIMESTAMPTZ    NOT NULL,
    currency_code         VARCHAR(3),
    available_to_bet      NUMERIC(19, 2) NOT NULL,
    exposure              NUMERIC(19, 2) NOT NULL,
    retained_commission   NUMERIC(19, 2),
    exposure_limit        NUMERIC(19, 2),
    discount_rate         NUMERIC(8, 4),
    wallet                VARCHAR(20)
);

CREATE INDEX ix_account_funds_snapshot_captured ON account_funds_snapshot (captured_at DESC);

CREATE TABLE account_statement_item (
    ref_id           VARCHAR(60)    PRIMARY KEY,
    item_date        TIMESTAMPTZ    NOT NULL,
    amount           NUMERIC(19, 2) NOT NULL,
    balance          NUMERIC(19, 2),
    item_class       VARCHAR(40),
    category         VARCHAR(25)    NOT NULL,
    item_class_data  JSONB          NOT NULL DEFAULT '{}'::jsonb,
    legacy_data      JSONB          NOT NULL DEFAULT '{}'::jsonb,
    synced_at        TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_statement_category CHECK (category IN ('DEPOSIT_WITHDRAWAL', 'EXCHANGE', 'OTHER'))
);

CREATE INDEX ix_account_statement_item_date ON account_statement_item (item_date DESC);
