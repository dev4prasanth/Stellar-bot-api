-- RabbitMQ ingestion receipts for Layer 1 (prediction), Layer 2 (planetary state) and Layer 3 (prediction).
-- Contract-specific columns are intentionally absent: the layer payload formats have not been supplied.
-- Validated records are stored in layer_source_record with a controlled JSONB normalized payload until the
-- contracts are approved; typed columns are then added in a new migration.

CREATE TABLE message_receipt (
    id                 UUID PRIMARY KEY,
    layer              VARCHAR(10)   NOT NULL,
    queue_name         VARCHAR(255),
    exchange_name      VARCHAR(255),
    routing_key        VARCHAR(255),
    broker_message_id  VARCHAR(255),
    redelivered        BOOLEAN       NOT NULL DEFAULT FALSE,
    content_type       VARCHAR(100),
    payload_bytes      INTEGER       NOT NULL,
    payload_sha256     VARCHAR(64)    NOT NULL,
    payload_raw        BYTEA,
    contract_version   VARCHAR(40),
    source_message_id  VARCHAR(255),
    dedup_key          VARCHAR(512),
    source_event_at    TIMESTAMPTZ,
    received_at        TIMESTAMPTZ   NOT NULL,
    processed_at       TIMESTAMPTZ,
    processing_ms      INTEGER,
    attempt            INTEGER       NOT NULL DEFAULT 1,
    status             VARCHAR(20)   NOT NULL,
    error_class        VARCHAR(40),
    error_detail       VARCHAR(1000),
    correlation_id     VARCHAR(64),
    reprocessed_from   UUID REFERENCES message_receipt (id),
    CONSTRAINT ck_message_receipt_layer CHECK (layer IN ('LAYER1', 'LAYER2', 'LAYER3')),
    CONSTRAINT ck_message_receipt_status CHECK (status IN (
        'RECEIVED', 'ACCEPTED', 'DUPLICATE', 'STALE', 'INVALID', 'RETRYING', 'QUARANTINED',
        'CONTRACT_PENDING', 'REPROCESSED')),
    CONSTRAINT ck_message_receipt_payload_bytes CHECK (payload_bytes >= 0),
    CONSTRAINT ck_message_receipt_attempt CHECK (attempt >= 1)
);

CREATE INDEX ix_message_receipt_layer_received ON message_receipt (layer, received_at DESC);
CREATE INDEX ix_message_receipt_status ON message_receipt (status);
CREATE INDEX ix_message_receipt_layer_accepted ON message_receipt (layer, processed_at DESC) WHERE status = 'ACCEPTED';

-- Contract-defined deduplication: at most one accepted receipt per layer and source identity.
CREATE UNIQUE INDEX uq_message_receipt_accepted_dedup
    ON message_receipt (layer, dedup_key) WHERE status = 'ACCEPTED' AND dedup_key IS NOT NULL;

CREATE TABLE layer_source_record (
    id                  UUID PRIMARY KEY,
    layer               VARCHAR(10)  NOT NULL,
    receipt_id          UUID         NOT NULL REFERENCES message_receipt (id),
    dedup_key           VARCHAR(512) NOT NULL,
    contract_version    VARCHAR(40)  NOT NULL,
    source_event_at     TIMESTAMPTZ,
    normalized_payload  JSONB        NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_layer_source_record_layer CHECK (layer IN ('LAYER1', 'LAYER2', 'LAYER3')),
    CONSTRAINT uq_layer_source_record_identity UNIQUE (layer, dedup_key)
);

CREATE INDEX ix_layer_source_record_event ON layer_source_record (layer, source_event_at DESC);

-- Contract-approved mapping from a validated layer record to Betfair market/runner identity.
CREATE TABLE layer_record_market_link (
    record_id      UUID        NOT NULL REFERENCES layer_source_record (id),
    market_id      VARCHAR(20) NOT NULL,
    selection_id   BIGINT      NOT NULL,
    mapping_rule   VARCHAR(80) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (record_id, market_id, selection_id)
);

CREATE INDEX ix_layer_record_market_link_market ON layer_record_market_link (market_id, selection_id);
