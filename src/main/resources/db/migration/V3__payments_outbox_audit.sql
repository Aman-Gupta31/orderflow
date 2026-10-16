CREATE TABLE payments (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id         BIGINT         NOT NULL,
    idempotency_key  VARCHAR(255)   NOT NULL,
    status           VARCHAR(32)    NOT NULL,
    amount           DECIMAL(12, 2) NOT NULL,
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key)
);

-- Generic idempotent-request cache: the full HTTP response is stored once per
-- idempotency key and replayed verbatim on retry, instead of reprocessing.
-- request_hash guards against the same key being reused for a genuinely
-- different request body.
CREATE TABLE idempotency_records (
    idempotency_key  VARCHAR(255)  PRIMARY KEY,
    request_hash     VARCHAR(64)   NOT NULL,
    response_status  INT           NOT NULL,
    response_body    MEDIUMTEXT    NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Transactional outbox: a row is inserted here in the SAME database
-- transaction as the business change it describes (order created/cancelled,
-- payment succeeded/failed), so the two can never diverge - either both
-- commit or both roll back. A separate poller (OutboxRelay) reads unpublished
-- rows and publishes them to Kafka, marking them published afterward.
CREATE TABLE outbox_events (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id        VARCHAR(36)   NOT NULL,
    aggregate_type  VARCHAR(64)   NOT NULL,
    aggregate_id    BIGINT        NOT NULL,
    event_type      VARCHAR(64)   NOT NULL,
    payload_json    MEDIUMTEXT    NOT NULL,
    published       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_outbox_events_event_id UNIQUE (event_id)
);

CREATE INDEX idx_outbox_events_unpublished ON outbox_events (published, id);

-- Consumer-side audit trail of every event actually processed, keyed by the
-- same event_id the producer generated. The unique constraint is what makes
-- consumption idempotent: a redelivered event (consumer restart, rebalance,
-- at-least-once retry) hits a duplicate-key error instead of being recorded
-- twice, and the consumer treats that as "already handled," not a failure.
CREATE TABLE event_audit_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id      VARCHAR(36)  NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    payload_json  MEDIUMTEXT   NOT NULL,
    consumed_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_event_audit_log_event_id UNIQUE (event_id)
);
