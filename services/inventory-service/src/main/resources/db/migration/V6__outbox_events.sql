-- Sprint 2 goal 2: transactional outbox for Inventory Service (ADR-006's originally-deferred gap
-- — "Inventory, Payment and Fulfillment Service still ship [publish-after-commit]"). Same shape as
-- Order Service's outbox_events (V4__outbox_events.sql), one copy per service per db-ownership.md
-- §2 ("each service gets its own copy in its own schema, with identical DDL").
--
-- payload holds the full event envelope (docs/events/event-catalog.md §1), already serialized.
-- No topic column: Inventory Service publishes every event type it produces to inventory.events.

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
