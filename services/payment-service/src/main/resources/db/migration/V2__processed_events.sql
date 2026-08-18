-- Payment Service's copy of the idempotency ledger. The shape is frozen in docs/db-ownership.md
-- §2 and the table is deliberately per-service, in this service's own schema, because the dedup
-- insert must commit in the same local transaction as the business change
-- (docs/adr/ADR-005-idempotent-consumers-for-duplicate-delivery.md).
--
-- Copied verbatim from services/inventory-service/src/main/resources/db/migration/V4__processed_events.sql
-- (docs/reliability-pattern.md §8, point 1) — same columns, same composite primary key.

CREATE TABLE processed_events (
    event_id      uuid NOT NULL,
    consumer_name text NOT NULL,
    processed_at  timestamptz NOT NULL,
    PRIMARY KEY (event_id, consumer_name)
);
