-- ADR-009: out-of-order status transitions are parked here instead of being applied out of order or
-- dropped. Order Service's status is driven by three independently-consumed topics
-- (inventory.events, payments.events, fulfillment.events) with no ordering guarantee between them,
-- and the payments.events fan-out (docs/events/event-catalog.md §3) means Fulfillment Service can
-- publish ShipmentCreated before Order Service has processed the PaymentAuthorized that caused it.
--
-- A row here means: "this event was consumed and durably accounted for, but its predecessor
-- transition has not been applied yet." OrderPersistence re-offers PENDING rows after every status
-- change and applies them the moment the transition table allows it. Rows are never re-consumed from
-- Kafka — the processed_events claim for the event commits together with the row below, so the
-- deferral is exactly as durable as applying the transition would have been.
--
-- status: PENDING   — waiting for its predecessor
--         APPLIED   — drained; the corresponding order_status_history row exists
--         ABANDONED — the order reached a terminal state this transition can never follow

CREATE TABLE deferred_transitions (
    id              bigserial PRIMARY KEY,
    order_id        text NOT NULL REFERENCES orders(id),
    target_status   text NOT NULL,
    source_event_id uuid NULL,
    status          text NOT NULL,
    deferred_at     timestamptz NOT NULL,
    resolved_at     timestamptz NULL
);

CREATE INDEX idx_deferred_transitions_order_status ON deferred_transitions (order_id, status);
