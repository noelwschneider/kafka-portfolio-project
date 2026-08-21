# Contract Changelog

Changes to the **frozen** Phase 0 contracts — `docs/openapi/`, `docs/events/`,
`docs/order-state-machine.md`, `docs/db-ownership.md`, `docs/scenarios.md`, `docs/adr/`,
`docs/architecture-diagram.md` — after they were frozen.

Why this file exists: `docs/planning/sprint-1/execution-plan.md` §5 rule 3 — *"Contract changes after fan-out
has begun require a brief broadcast (e.g., a note in `docs/CHANGELOG-contracts.md`) so other
in-flight workstreams know to re-check their assumptions before merging."* An entry here is the
broadcast. If you are picking up in-flight work, read the entries dated after your branch point.

Newest first. Each entry states what changed, why, who is affected, and what they must do.

---

## 2026-08-21 — `adr/ADR-005`, `adr/ADR-009`, `reliability-pattern.md`: retention added for `processed_events` and `deferred_transitions`

**Changed by:** Sprint 2 goal 2, item 4 (Correctness & Reliability Cleanup).

**What changed.** ADR-005's "Accepted costs" bullet and `reliability-pattern.md` §2.4 point 4 both
said a `processed_events` retention policy was "needed eventually" — it now exists.
`ProcessedEventRetentionScheduler` (new, in `services/common`) purges rows older than 7 days once a
day, active in every service that sets `orderfulfillment.reliability.processed-events-table` (Order,
Inventory, Payment, Fulfillment — not Scenario Service, which has no such table).
`DeferredTransitionRetentionScheduler` (new, Order Service only) does the same for resolved
(`APPLIED`/`ABANDONED`) `deferred_transitions` rows, documented as a similarly-unbounded table in
ADR-009's "Accepted costs" section; a `PENDING` row is never purged by age. No schema change — both
purge existing columns (`processed_at`, `resolved_at`) that were already there.

**Why.** Both tables grow monotonically by design (idempotency ledger, out-of-order-transition park)
with no existing cleanup, and both ADRs flagged it as future work rather than closing it. 7 days was
chosen to match Kafka's own default topic retention (`log.retention.hours=168`) — a ledger row (or a
resolved deferred-transition row, which exists only because its event was already durably applied or
abandoned) can never legitimately be needed once its originating event could no longer be
redelivered from Kafka.

**Who is affected.**

- **Order, Inventory, Payment, Fulfillment Service** — implemented. New `orderfulfillment.retention.*`
  properties (`processed-events-days`, `deferred-transitions-days` [order-service only],
  `check-interval-ms`), all with sensible defaults — no config change required to pick this up.
- **Everyone else** — no action. No table, column, event, or API shape changed.

---

## 2026-08-21 — `order-state-machine.md`, `adr/ADR-009`: transition 9 (→ FAILED) implemented

**Changed by:** Sprint 2 goal 2, item 2 (Correctness & Reliability Cleanup).

**What changed.** `docs/order-state-machine.md`'s transition 9 section gets an "Implementation,
Sprint 2 goal 2" note pointing at the concrete mechanism; ADR-009's "Accepted costs" bullet that
named this gap is updated to say it is closed. No transition-table shape changed — transition 9's
predecessor set ("any non-terminal state") was already frozen and already encoded in
`OrderTransitions`; what was missing was any caller that ever requested it.

**Why.** ADR-009's own "Accepted costs" section flagged this as the known remaining gap: a
dead-lettered event left its order stuck at whatever status it last reached, with no record that
anything had failed. Sprint 2's pre-sprint planning picked it up as one of the "open gaps" worth
closing.

**Who is affected.**

- **Order Service** — implemented: `OrderDeadLetterConsumer` (new, listens on this service's own
  `orders.dlq`) and `OrderPersistence#markFailed`. No new event, topic, or API shape — `FAILED` was
  already in the frozen state list and `GET /api/orders/{id}` already reports whatever status is
  current; this only adds a real path that can reach it. `markFailed` also re-drains the order's
  `deferred_transitions` after writing FAILED, so a transition parked behind the now-dead-lettered
  event is marked `ABANDONED` instead of sitting `PENDING` forever.
- **Everyone else** — no action. No payload, topic, or schema changed.
- **Anyone building a DLQ-inspector UI or alerting on `orders.dlq` volume** — a dead-lettered record
  now has an observable, honest side effect (the order's status) rather than a silent one; nothing
  about the DLQ record's own shape changed.

---

## 2026-08-21 — `db-ownership.md`, `adr/ADR-006`, `architecture-diagram.md`: outbox extended to Inventory, Payment, Fulfillment Service

**Changed by:** Sprint 2 goal 2, item 1 (Correctness & Reliability Cleanup).

**What changed.** `outbox_events` is no longer Order-Service-only. Inventory, Payment and
Fulfillment Service each got their own `outbox_events` table — identical DDL to Order Service's, in
their own schema — plus their own `OutboxRecorder` / `OutboxDispatcher` / `OutboxPublisher` trio.
`docs/db-ownership.md` §1/§2/§3 now lists three additional `outbox_events` rows (one per schema) and
says all four services publish through the outbox; ADR-006's status line and Consequences section
carry a correction recording the change; `docs/architecture-diagram.md` §1's schema boxes and §5's
delivery-properties bullet no longer describe a three-service dual-write gap.

**Why.** ADR-006 always treated the gap for these three services as a known, documented limitation
rather than a permanent one — Sprint 2's pre-sprint planning flagged it as one of the "open gaps"
worth closing. No new event types, no new topics, no payload changes: this closes the same
publish-after-commit failure window Order Service already closed in Phase 6, using the same pattern.

**Who is affected.**

- **Inventory, Payment, Fulfillment Service** — implemented. New Flyway migrations
  (`V6__outbox_events.sql` for Inventory, `V4__outbox_events.sql` for Payment and Fulfillment).
  `InventoryReservationExecutor`, `PaymentService#authorize`, and `FulfillmentService#createShipment`
  now record their outbound event to the outbox inside the same transaction as the business change;
  their Kafka consumers no longer call `EventPublisher.publish` directly.
- **Everyone else** — no action. No event payload, topic, or API shape changed; consumers of
  `inventory.events` / `payments.events` / `fulfillment.events` see the same wire format as before,
  just with a stronger durability guarantee behind it.

## 2026-08-20 — `openapi/inventory-service.yaml`: new `POST /demo/inventory/{sku}/restore`

**Changed by:** fix for the reset defect found live in
`docs/agent-reports/sprint-2/deployment-execution-report.md` §6.

**What changed.** A new `/demo` path, `POST /demo/inventory/{sku}/restore`, with a new
`RestoreInventoryRequest` schema (`{availableQuantity}`). It sets `availableQuantity` to the given
value **and** zeroes `reservedQuantity` in the same write, bypassing the
`availableQuantity >= reservedQuantity` guard that `PUT /api/inventory/{sku}` enforces. No existing
path or schema changed.

**Why.** `reservedQuantity` was structurally unreachable from the existing `PUT` — it only ever sets
`availableQuantity` and rejects a value below the current reservation count. Reservations are never
released on the successful-fulfillment path, so on a long-running demo `reservedQuantity` drifts
upward without bound and free stock (`availableQuantity - reservedQuantity`) trends to zero
permanently; `POST /demo/reset` could not fix it through the existing contract because there was no
write path that could ever bring `reservedQuantity` back down. This new endpoint gives Scenario
Service's reset an atomic way to clear both fields together.

**Who is affected.**

- **Inventory Service** — implemented: `InventoryService#restoreForDemo`, `DemoInventoryController`.
  Deliberately kept out of the production ingress allowlist, same as `/demo/consumers` — called only
  by Scenario Service over cluster-internal DNS.
- **Scenario Service** — `InventoryServiceClient` and `DemoResetService#restoreInventory` now call
  this endpoint instead of `PUT /api/inventory/{sku}`.
- **Everyone else — no action.** No event payload, topic, or existing API path changed.

---

## 2026-08-20 — new `docs/adr/ADR-009` + `db-ownership.md`: `deferred_transitions` table (Order Service status race fix)

**Changed by:** post-Phase-10 correctness fix for the defect found live in
`docs/agent-reports/phase-10-scaling-demo.md` §4.

**What changed.**

- New ADR: `docs/adr/ADR-009-out-of-order-status-transitions.md`.
- `docs/db-ownership.md` §1 and §3: a new table, `deferred_transitions`, owned by Order Service in
  the `order_service` schema (`V5__deferred_transitions.sql`). No existing table, column, or
  constraint changed.
- **`docs/order-state-machine.md` is unchanged and required no change.** Its §3 transition table was
  already correct; the defect was that Order Service did not enforce it. §3 is now encoded in code
  (`OrderTransitions`) rather than existing only as prose.
- **No event payload, topic, consumer group, or API path changed.**

**Why.** Order status is written by three independently-consumed topics with no ordering guarantee
between them. The deliberate `payments.events` fan-out (`docs/events/event-catalog.md` §3) let
Fulfillment Service publish `ShipmentCreated` up to 7+ seconds before Order Service processed the
`PaymentAuthorized` that caused it, so `FULFILLED` was written straight out of `PAYMENT_PENDING` and
then reverted by the late `PaymentAuthorized` — 34 of 60 orders affected at 2 replicas. Order Service
now checks every write against the transition table, drops anything that would move an order
backwards or off a terminal state, and parks a transition that arrives before its predecessor until
that predecessor is applied. Retry/backoff was rejected as the mechanism: the existing budget
(~3.5 s, `docs/reliability-pattern.md` §4.3) is shorter than the observed race, and it blocks the
partition for what is not a failure.

**Who is affected.**

- **Order Service** — implemented; new migration `V5`, new regression test
  `OrderOutOfOrderTransitionIntegrationTest`. Two existing tests in `OrderServiceIntegrationTest`
  asserted an invalid state-machine path and were corrected (see the ADR's Consequences).
- **Everyone else — no action on contracts.** But note the behavior change if you assert on Order
  Service status: an order whose events arrive out of order now converges to the correct terminal
  state *slightly later* rather than jumping ahead, and an invalid transition is now dropped at WARN
  instead of being written. Anything that relied on Order Service accepting a status write from an
  arbitrary current state (including tests that skip `InventoryReserved`) will need to drive the real
  sequence.

---

## 2026-08-18 — `db-ownership.md` + `openapi/scenario-service.yaml`: new `events` table and `GET /demo/events` (Event Explorer)

**Changed by:** Phase 5, Scenario Service build (`docs/agent-reports/phase-5-scenario-service.md`).

**What changed.**

- `docs/db-ownership.md` §1 and §3: a new table, `events`, owned by Scenario Service in the
  `scenario_service` schema. Shape and rationale are in §3 under "Scenario Service". The "Event
  Explorer's backing store has no owner yet" open item in §4 is marked resolved and points here.
- `docs/openapi/scenario-service.yaml`: a new path, `GET /demo/events`, with two new schemas,
  `EventRecord` and `EventRecordPage`. No existing path, schema, or field in the document changed.

**Why.** Building the 8 demo scenarios' honest timelines requires Scenario Service to consume all
four domain topics (plus their DLQs) directly — that's the only way to genuinely know a record's
`topic`/`partition`/`offset`/`eventId`. Once that consumer exists, it is also the natural single owner
of the general-purpose event projection the Event Explorer needs, rather than standing up a second
consumer of the same four topics purely for that purpose. This was flagged as an open item at Phase 0
specifically waiting on "whether a projection consumer subscribes to all topics" being decided — it
now has been.

**Who is affected.**

- **Scenario Service** — already implemented: `V2__events.sql`, `EventProjectionConsumer`,
  `EventQueryService`, `GET /demo/events`.
- **Everyone else — no action.** No existing event payload, topic, table, or API path changed. The
  projection is read-only and reached only through the new endpoint; no other service's schema is
  queried (`docs/db-ownership.md`'s one-owner rule holds).
- **Frontend (Phase 5 concurrent workstream)** — the Event Explorer page can now be built against a
  real endpoint instead of a placeholder.

**Honesty note.** `EventRecord` deliberately has no "consumed" phase, `durationMs`, or `retryCount` —
those live inside each service's own `processed_events` row, which Scenario Service may not read
across schemas. See the Phase 5 report for the two alternatives considered and why this one was kept
minimal rather than fabricating a consumption phase.

---

## 2026-08-18 — `db-ownership.md`: `inventory_items` gains `CHECK (reserved_quantity <= available_quantity)`

**Changed by:** Phase 4 pattern-design step (`docs/agent-reports/phase-4-pattern-design.md`).

**What changed.** The Inventory Service table definition in `docs/db-ownership.md` §3 now carries a
third CHECK constraint relating the two quantity columns, alongside the existing
`available_quantity >= 0` and `reserved_quantity >= 0`. Implemented by
`services/inventory-service/src/main/resources/db/migration/V3__reserved_within_available.sql`.

**Why.** The project's headline invariant — "total reserved inventory never exceeds available
inventory" (`docs/scenarios.md`, Scenario 7) — was enforced only in application code. The two
existing per-column checks do not imply it, and
`docs/agent-reports/phase-3-inventory-concurrency.md` §4 found a real bug that wrote
`reserved_quantity = 4` against `available_quantity = 2`, which the database accepted. §7.2 of that
report recommended this constraint and flagged it rather than making the change, because it touches
a frozen contract. This is that change, made through the coordination protocol.

Optimistic locking on `version` remains the primary mechanism; this is the backstop that converts a
future oversell from silent stock corruption into a loud, immediate constraint violation.

**Who is affected.**

- **Inventory Service** — already applied (migration + tests green).
- **Everyone else** — no action. No other service reads or writes `inventory_items`
  (`docs/db-ownership.md`'s one-owner rule), and no event payload or API response changed.
- **Anyone writing to `inventory_items` in a seed script, fixture, or demo** — an insert or update
  that leaves `reserved_quantity` above `available_quantity` now fails outright instead of
  succeeding. `V2__seed_data.sql` seeds `reserved_quantity = 0` and is unaffected. Note that
  `PUT /api/inventory/{sku}` already rejected lowering `availableQuantity` below the reserved count
  with a `409 INVENTORY_CONFLICT`, so its behaviour is unchanged; the constraint only changes what
  happens if that application check is ever wrong.

**Not changed.** No event payload, no enum, no topic, no API shape. In particular
`InventoryReservationFailed.reason` remains frozen to `INSUFFICIENT_STOCK | UNKNOWN_SKU` — the
related Gap 1 from the same report was resolved by DLQ routing, which needed no contract change.
See `docs/reliability-pattern.md`.
