-- Phase 5 addition, added through the coordination protocol (docs/planning/agent-guidance.md's
-- Agent Coordination Rules; see docs/CHANGELOG-contracts.md's Phase 5 entry and
-- docs/agent-reports/phase-5-scenario-service.md). Resolves the "Event Explorer's backing store has
-- no owner yet" gap left open in docs/db-ownership.md §4: Scenario Service already has to consume all
-- four domain topics to build honest scenario-run timelines, so it is the natural owner of the
-- general-purpose event projection too, rather than standing up a second consumer of the same topics.
--
-- This is a read-model projection, not a copy of any other service's table: every column is something
-- Scenario Service can observe directly by consuming the record itself (envelope fields + Kafka
-- record coordinates), never anything read from another service's schema.
CREATE TABLE events (
    id              bigserial PRIMARY KEY,
    event_id        uuid NOT NULL,
    event_type      text NOT NULL,
    event_version   integer NOT NULL,
    occurred_at     timestamptz NOT NULL,
    correlation_id  uuid NOT NULL,
    aggregate_id    text NOT NULL,       -- orderId, per event-catalog.md §1
    topic           text NOT NULL,
    "partition"     integer NOT NULL,
    "offset"        bigint NOT NULL,
    producer        text NOT NULL,       -- publishing service, from the topic-ownership table
    dead_lettered   boolean NOT NULL DEFAULT false,
    payload         jsonb NOT NULL,
    recorded_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (topic, "partition", "offset")
);

CREATE INDEX idx_events_event_type ON events (event_type);
CREATE INDEX idx_events_aggregate_id ON events (aggregate_id);
CREATE INDEX idx_events_correlation_id ON events (correlation_id);
CREATE INDEX idx_events_producer ON events (producer);
CREATE INDEX idx_events_topic ON events (topic);
CREATE INDEX idx_events_dead_lettered ON events (dead_lettered);
CREATE INDEX idx_events_occurred_at ON events (occurred_at DESC);
