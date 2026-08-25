-- Sprint 5, issue #27: EventProjectionConsumer deduped on (topic, partition, offset), which
-- identifies a physical Kafka record but is not a stable identity across a broker reset. Local
-- dev's kafka container had no persistent volume (see docker-compose.yml, now fixed), so a stack
-- rebuild reset every topic's offsets to 0 while scenario_service.events still held stale rows from
-- before the reset. A freshly-produced record at offset 0 then collided with a stale row already
-- occupying (topic, partition, offset) = (same topic, same partition, 0) and was silently dropped
-- as "already projected" (EventProjectionConsumer.project(), log.debug only) — breaking the
-- duplicate-event demo scenario and emptying Order Detail's event timeline for affected orders.
--
-- Deduping on event_id alone (the originally proposed fix) was tried and rejected: it broke live
-- against real data. docs/scenarios.md's Scenario 4 (Duplicate Event Delivery, a frozen contract)
-- deliberately republishes a record with the *same eventId* to a genuinely new Kafka offset, and
-- its documented "Observable proof" requires the timeline to show that event consumed twice — an
-- eventId-only unique constraint collides on exactly that legitimate case (confirmed live: eventId
-- 44f63ae9-614f-417d-94e7-93a39cf1de17 already existed twice in this environment's data, at
-- offsets 58 and 60 of the same topic/partition, from a prior run of that scenario).
--
-- The key insight both attempted fixes were reaching for: (topic, partition, offset) is a stable
-- identity for a physical record *within one broker epoch*, but a broker reset lets a new, unrelated
-- record reuse that same physical address. Anchoring the identity to the event actually observed at
-- that address — (topic, partition, offset, event_id) together — fixes both problems: a reset
-- produces a new record at a reused address but with a *different* event_id, so the tuple no longer
-- matches and the row is correctly (re)projected; a genuine redelivery of the same physical record
-- (rebalance replay, retry after a transient DB error) reproduces the identical tuple and is still
-- correctly treated as a no-op; and Scenario 4's legitimate republish lands at a distinct offset, so
-- it is never deduped against the original, exactly as the frozen contract requires.
ALTER TABLE events DROP CONSTRAINT events_topic_partition_offset_key;

ALTER TABLE events ADD CONSTRAINT events_topic_partition_offset_event_id_key
    UNIQUE (topic, "partition", "offset", event_id);
