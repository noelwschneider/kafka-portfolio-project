# Contract Changelog

Changes to the **frozen** Phase 0 contracts — `docs/openapi/`, `docs/events/`,
`docs/order-state-machine.md`, `docs/db-ownership.md`, `docs/scenarios.md`, `docs/adr/`,
`docs/architecture-diagram.md` — after they were frozen.

Why this file exists: `docs/planning/execution-plan.md` §5 rule 3 — *"Contract changes after fan-out
has begun require a brief broadcast (e.g., a note in `docs/CHANGELOG-contracts.md`) so other
in-flight workstreams know to re-check their assumptions before merging."* An entry here is the
broadcast. If you are picking up in-flight work, read the entries dated after your branch point.

Newest first. Each entry states what changed, why, who is affected, and what they must do.

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
