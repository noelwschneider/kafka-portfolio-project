-- Inventory Service's copy of the idempotency ledger. The shape is frozen in docs/db-ownership.md
-- §2 and the table is deliberately per-service, in this service's own schema, because the dedup
-- insert must commit in the same local transaction as the business change
-- (docs/adr/ADR-005-idempotent-consumers-for-duplicate-delivery.md).
--
-- The composite primary key is what lets one event be processed once by each of several *different*
-- consumers; within this service, inventory.order-created and inventory.payment-rejected coexist
-- here without colliding.

CREATE TABLE processed_events (
    event_id      uuid NOT NULL,
    consumer_name text NOT NULL,
    processed_at  timestamptz NOT NULL,
    PRIMARY KEY (event_id, consumer_name)
);
