-- Sprint 2 goal 2: transactional outbox for Fulfillment Service (ADR-006's originally-deferred
-- gap). Same shape as Order Service's outbox_events (V4__outbox_events.sql), one copy per service
-- per db-ownership.md §2. payload holds the full event envelope, already serialized. No topic
-- column: Fulfillment Service publishes every event type it produces to fulfillment.events.

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
