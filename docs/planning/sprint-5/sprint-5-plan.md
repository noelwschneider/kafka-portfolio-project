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
