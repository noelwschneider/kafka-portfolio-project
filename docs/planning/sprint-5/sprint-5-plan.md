# Sprint 5 Plan

- **Input:** four items surfaced incidentally during Sprint 4's frontend bug hunt (issue #25's
  investigation), filed separately at the time as outside that sprint's theme, plus one pre-existing
  backlog gap.
- **Theme:** backend correctness and reliability, not the frontend or the developer's own workflow. A
  deliberate pivot from Sprint 4's UX pass back to whether the system's core delivery guarantees
  actually hold — and to closing the gap between the project's Day-1 CI decision and what exists
  today.

## Goals

1. **[#29](https://github.com/noelwschneider/kafka-portfolio-project/issues/29) Check whether real
   domain services' idempotency ledgers share the same broker-reset-fragile dedup key as
   EventProjectionConsumer** — Scenario Service's event-projection consumer dedupes on physical
   `(topic, partition, offset)` Kafka coordinates, which collide after a broker reset (see #27).
   Unchecked: whether Order/Inventory/Payment's own idempotent-consumer ledgers (their
   `processed_events` tables, per `sprint-1/backend-design.md`'s idempotency pattern) key on the same
   fragile coordinates or on the stable `eventId`. If they share the pattern, a broker reset could
   silently compromise a real business-logic guarantee, not just demo/observability data — directly
   relevant to `engineering-rules.md`'s rule against claiming stronger delivery guarantees than the
   implementation provides. Investigation only; no fix implied until the answer is known.
2. **[#27](https://github.com/noelwschneider/kafka-portfolio-project/issues/27) Fix the event
   projection dedup key** — already root-caused: Kafka has no persistent volume in
   `docker-compose.yml`, so a stack rebuild resets offsets to 0 and new low-offset records collide
   with stale pre-reset rows in `scenario_service.events`, which get silently dropped as
   already-projected. Breaks the `duplicate-event` demo scenario intermittently and empties Order
   Detail's event timeline for affected orders. Two independent candidate fixes are already written
   up: (a) give Kafka a persistent volume, matching Postgres; (b) key the dedup check on `eventId`
   instead of physical offset (the `UNIQUE (topic, partition, offset)` constraint in `V2__events.sql`
   would need to change). Scope depends on #29's answer — if real consumers share the same fragile
   pattern, the fix should cover more than just Scenario Service.
3. **[#28](https://github.com/noelwschneider/kafka-portfolio-project/issues/28) Investigate:
   `reserved_quantity` not zeroing after order reaches FULFILLED** — observed while diagnosing #25:
   `reserved_quantity` stayed at its post-reservation value after fulfillment instead of returning to
   0, for two different SKUs. The documented invariant (`reserved_quantity <= available_quantity`,
   backed by `docs/db-ownership.md`'s CHECK constraint) held in every case checked, so this wasn't
   root-caused. Needs a focused look at Inventory Service's fulfillment-consumption path
   (`ShipmentCreated` / the fulfillment-side inventory consumer) to determine whether zeroing on
   fulfillment is the intended design or `reserved_quantity` represents something else.
4. **[#35](https://github.com/noelwschneider/kafka-portfolio-project/issues/35) Real CI (test
   execution + path filters)** — the Day-1 pinned-stack decision
   (`project-overview.md` §0) was GitHub Actions with per-service path filters running tests. What
   exists today is an image-build-only workflow (`workflow_dispatch`, no test execution, no path
   filtering) — a real gap against the project's own founding decisions. Highest unblocking value on
   the backlog: a real CI pipeline is what would catch a #27/#29-class regression automatically
   instead of relying on incidental discovery during unrelated review, and every future sprint
   benefits from it existing.

## Sequencing

**Investigate before fixing; CI can run in parallel.**

```
#29 (investigate domain ledgers) ──► #27 (fix dedup key, scoped by #29's answer)

#28 (investigate reserved_quantity) — independent, same bug-hunt origin

#35 (real CI) — independent, no dependency on the other three
```

1. **#29 first.** Its answer determines whether #27's fix is Scenario-Service-only or needs to extend
   to Order/Inventory/Payment's own idempotency ledgers. Doing #27 before #29 risks fixing the wrong
   scope.
2. **#27 next**, scoped by what #29 found.
3. **#28** has no dependency on #29/#27 — different subsystem (Inventory's fulfillment-consumption
   path, not the event-projection dedup key) — and can run in parallel with the #29→#27 chain.
4. **#35** has no code dependency on the other three and can run at any point, though landing it
   before #27's fix ships means the fix itself gets the CI coverage the project has been missing.

## Explicitly not in scope

**Inventory: release reservations on FULFILLED (Option B)** — draft backlog item (Tier 2, not yet a
GitHub Issue), the real saga-behavior fix for converting a reservation on fulfillment, deliberately
deferred in Sprint 2 for its own design pass since it touches the compensation path shared with
payment-failure. Thematically coherent with this sprint but bigger and riskier than a bug hunt should
absorb; held for a sprint where it can get focused design attention on its own.

Frontend and workflow backlog items carried from Sprint 4 (Orders table row-clickability/formatting
#20, per-column filtering #21, frontend test harness #34, per-SKU pricing #32, pagination #33, Kafka
health indicator #30, per-component health detail #31) and the implementer-preset workflow items
(worktree isolation, `acceptEdits` mode, semantic verification gate) are left for a sprint whose theme
they actually match — none carry urgency that would justify breaking this sprint's backend-reliability
focus.

## Dependencies

No dependency on any other sprint's work. Within the sprint, see the sequencing diagram above — #29
gates #27's scope; #28 and #35 are independent.

## Planning docs this sprint needs

No new design docs (backend/frontend/high-level) or execution-plan.md — this sprint investigates and
fixes existing behavior rather than introducing new contracts or architecture. Per-item work should
delegate to the `investigator` preset for #29 and #28 (diagnosis-first), and the `implementer` preset
for #27 (once scoped) and #35, per `docs/workflow/agent-workflow.md`.

## Closing state

All four goals shipped and are independently verified against a running stack, not just accepted on
the implementing agent's own report.

- **#29** — confirmed the real domain services (Order, Inventory, Payment, Fulfillment) do not share
  EventProjectionConsumer's broker-reset-fragile pattern. All four key their idempotency ledgers on
  `(eventId, consumerName)` via the shared `services/common` `ProcessedEventKey`, never on physical
  Kafka coordinates — confirmed by file:line audit of every `processed_events` migration and every
  domain consumer, plus a live replay test. This scoped #27's fix to Scenario Service only.
- **#27** — fixed with two changes: a persistent volume for the `kafka` service in `docker-compose.yml`
  mounted at `/var/lib/kafka/data` with `KAFKA_LOG_DIRS` set to match (the image's own default log path
  of `/tmp/kraft-combined-logs` turned out not to be where this image's `KafkaDockerWrapper` actually
  writes without that env var, and isn't writable by the broker's uid without it either — both found
  and fixed in a second pass after independent verification caught the first attempt's volume silently
  not persisting anything); and a new Flyway migration changing the scenario-service events table's
  dedupe constraint from `(topic, partition, offset)` to a composite
  `(topic, partition, offset, event_id)` — not `eventId` alone, which was tried and found to violate
  the frozen Duplicate Event Delivery scenario contract (`docs/scenarios.md` Scenario 4 deliberately
  republishes the same `eventId` at a new offset and requires both rows to appear). Verified
  independently: reproduced the original offset-collision failure, confirmed the schema fix closes it
  under repeated real broker resets, confirmed Scenario 4's legitimate duplicate still persists as two
  rows, and confirmed a real `docker compose down`/`up --build` cycle now preserves Kafka's topic
  offsets rather than resetting them. `docs/db-ownership.md`'s frozen `events` table constraint and
  `docs/CHANGELOG-contracts.md` are updated to match — the implementing agent changed the constraint
  without following the coordination protocol on the first pass; caught and fixed at sprint review,
  which also hardened `.claude/hooks/require-agent-report.py` so a migration touching a documented
  table can no longer land without an explicit answer in the report.
- **#28** — root-caused as a known, already-accepted design gap, not a new defect: Inventory Service
  never consumes `ShipmentCreated` (or any fulfillment-side event), so nothing on the success path ever
  releases a reservation; the compensation-side release (triggered by `PaymentRejected`) works
  correctly and was confirmed via a positive-control scenario run. The `reserved_quantity <=
  available_quantity` invariant holds throughout. Closing this for real means the "Option B"
  saga-behavior fix (still unscheduled, see Explicitly not in scope) — there is no smaller defect
  underneath.
- **#35** — `.github/workflows/ci.yml` added: one path-filtered Maven test job per backend service
  (gated on that service's own paths or `services/common`, since all five depend on it directly), plus
  a frontend lint+build job. Separate from the existing image-build workflow, which is untouched.
  Verified independently against the real Maven/npm commands, `actionlint`, and each service's actual
  `pom.xml` dependency declarations.

One new finding surfaced during #27's verification, not part of this sprint's original scope: the
Duplicate Event Delivery scenario's run *timeline* doesn't display its second event entry, because
`RunRegistry.finish()` clears the correlation-to-run mapping before the async republish is consumed.
The underlying events project correctly (confirmed via direct query); only the timeline display is
affected. This confirms and extends an existing backlog draft item rather than being new — updated in
place on the board — and remains unscheduled.

`README.md`'s CI/CD section, previously stating no workflow ran tests on push/PR, is updated to
reflect `ci.yml`'s existence.
