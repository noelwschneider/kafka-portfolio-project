-- Phase 6's transactional outbox, Order Service only
-- (docs/adr/ADR-006-transactional-outbox-for-db-kafka-consistency.md). The shape below is the one
-- frozen in docs/db-ownership.md §2 in Phase 0 — this migration fills in that reserved placeholder
-- rather than introducing a new contract.
--
-- payload holds the *full* event envelope (docs/events/event-catalog.md §1), already serialized, so
-- eventId/occurredAt/correlationId are fixed at the moment of the business transaction rather than
-- at the moment the background publisher happens to send. There is deliberately no topic column:
-- Order Service publishes every event type it produces to orders.events (event-catalog.md §2).
--
-- The (status, created_at) index backs the publisher's only query — the pending rows, oldest first.

CREATE TABLE outbox_events (
    id           bigserial PRIMARY KEY,
    aggregate_id text NOT NULL,
    event_type   text NOT NULL,
    payload      jsonb NOT NULL,
    created_at   timestamptz NOT NULL,
    published_at timestamptz NULL,
    status       text NOT NULL
);

CREATE INDEX idx_outbox_events_status_created ON outbox_events (status, created_at);
