# Phase 0 Report — Frozen Contracts

**Date:** 2026-08-17
**Scope:** `docs/planning/implementation-phases.md`'s Phase 0 (Design Contract) and
`docs/planning/execution-plan.md` §4's Phase 0 row.
**Output:** documentation and schema only. No application code, no Kubernetes manifests, and no JSON
Schema for event payloads (`execution-plan.md` §7 places `docs/events/schemas/*.json` at Phase 2).

No file under `docs/planning/` was modified. Inconsistencies found in those docs are reported in §4
below rather than edited.

---

## 1. Files created (18)

### OpenAPI 3.1 specs

| File | Paths | Operations |
|---|---|---|
| `docs/openapi/order-service.yaml` | 3 | 4 |
| `docs/openapi/inventory-service.yaml` | 5 | 6 |
| `docs/openapi/payment-service.yaml` | 2 | 4 |
| `docs/openapi/fulfillment-service.yaml` | 4 | 4 |
| `docs/openapi/scenario-service.yaml` | 6 | 6 |

### Contracts

- `docs/events/event-catalog.md` — envelope, topic/key strategy, 8 events, versioning rule, and the
  events deliberately excluded from v1
- `docs/order-state-machine.md` — 9 states, 9 transitions, reachability and event-coverage proofs
- `docs/db-ownership.md` — 14 tables across 5 owners, column definitions, boundary notes
- `docs/scenarios.md` — the 8 demo scenarios with endpoints, real backend actions, success conditions
- `docs/architecture-diagram.md` — system overview flowchart plus happy-path, inventory-failure, and
  payment-failure sequence diagrams

### ADRs

- `ADR-001-kafka-for-asynchronous-order-lifecycle-events.md`
- `ADR-002-separate-demo-and-business-apis.md`
- `ADR-003-sse-rather-than-websockets-for-live-updates.md`
- `ADR-004-postgresql-per-service-ownership-boundaries.md`
- `ADR-005-idempotent-consumers-for-duplicate-delivery.md`
- `ADR-006-transactional-outbox-for-db-kafka-consistency.md`
- `ADR-007-kubernetes-only-after-local-boundaries-stabilize.md`

ADR-006 and ADR-007 carry `Status: Accepted — not yet implemented`, so no ADR can be read as a claim
about current behavior.

### This report

- `docs/agent-reports/phase-0.md`

---

## 2. The four required consistency checks

All four pass. Each was verified by script (`ruby`, parsing the YAML and Markdown rather than
eyeballing) — full output reproducible from the commands in §6.

### Check 1 — every event name referenced in an OpenAPI spec appears in `event-catalog.md` — **PASS**

14 event references across the 5 specs. Extraction took every `Order|Inventory|Payment|Shipment|
Fulfillment`-prefixed CamelCase token in each spec and subtracted that spec's own `components.schemas`
keys, so schema names like `PaymentAttempt` and `OrderStatus` are excluded and genuine event
references are not.

- 13 references resolve to events **defined** in the catalog.
- 1 reference — `OrderShipped` in `fulfillment-service.yaml` — appears in the catalog's §4 table of
  events **deliberately excluded** from v1, not as a defined event. The spec mentions it only to
  explain why `ShipmentStatus` has a single value. It satisfies the check as stated (it appears in the
  catalog); flagging the distinction so it is not mistaken for a defined event.

### Check 2 — every status-changing event in `event-catalog.md` is reflected in `order-state-machine.md` — **PASS**

The catalog defines 8 events. Their declared order-status effect was parsed from each entry:

- **6 status-changing** — `InventoryReserved`, `InventoryReservationFailed`, `PaymentRequested`,
  `PaymentAuthorized`, `PaymentRejected`, `ShipmentCreated`. All 6 appear in the state machine's
  numbered transition table.
- **2 with no status effect** — `OrderCreated` (published while entering `PENDING`) and
  `InventoryReleased` (inventory-side compensation only). Both are listed explicitly in the state
  machine's event-coverage table so their absence from the transition table cannot be mistaken for an
  oversight.

The state machine's own two checks also hold: all 9 states are reachable, and all 4 terminal states
are reachable from a non-terminal state.

### Check 3 — every table in `db-ownership.md` is owned by exactly one service — **PASS**

14 table rows, 5 distinct owners, no table with two owners.

One base name appears 4 times: `processed_events`. Those are four **distinct physical tables**, one per
service schema, with identical DDL — required rather than incidental, because the deduplication insert
must commit in the same local transaction as the business change it guards. `docs/db-ownership.md` §2
documents this, including why a single shared table would be wrong.

### Check 4 — every scenario in `scenarios.md` has a matching endpoint in `scenario-service.yaml` — **PASS**

8 scenario names in `scenarios.md`, 8 values in the spec's `ScenarioName` enum, exact match in both
directions, with the templated path `/demo/scenarios/{scenarioName}` present.

### Additional verification

- **All 5 specs parse and are structurally valid**: `openapi: 3.1.0`, required `info`/`paths`
  /`components` present, every one of the local `$ref`s resolves to an existing node, and every
  operation has both `responses` and an `operationId`. This was a structural check with a real YAML
  parser, **not** a full OpenAPI-compliance validation — no schema validator (`openapi-spec-validator`,
  `spectral`) is installed on this machine and Phase 0 added no dependencies. Worth running one in CI
  when Phase 7 sets up pipelines.
- **Order status enum is identical** between `order-service.yaml`'s `OrderStatus` and
  `order-state-machine.md`'s state table (9 values, exact match).
- **No overclaimed guarantees**: the only occurrences of "exactly-once" outside `docs/planning/` and
  `docs/_old/` are 5 explicit denials or rejected-alternative discussions
  (`docs/planning/agent-guidance.md` rule 18).
- **`/api` and `/demo` never mix**: no spec places both prefixes under one tag, and Scenario Service is
  entirely `/demo` (rule 9).
- **All 5 Mermaid blocks** use diagram types GitHub renders natively (`flowchart TB`,
  `stateDiagram-v2`, 3 × `sequenceDiagram`), with balanced fences. Rendering was not visually
  confirmed — no Mermaid renderer is available here — so a glance at the two files on GitHub is worth
  doing.

---

## 3. Judgment calls

Ambiguities the planning docs do not resolve, and what was frozen instead. Items 1–4 were confirmed
with you before writing; 5–19 were resolved while writing and are reported here rather than left
silent.

### Confirmed with you

1. **Out-of-stock terminal state name → `REJECTED_OUT_OF_STOCK`.** The planning docs contradict
   themselves (see §4.1). Chose the spelling used by 2 of the 3 references, including the one the UI
   and the Scenario 2 test assert against.
2. **Contract scope → sketched endpoints + per-service `/demo` control hooks + SSE endpoints.** SSE
   endpoints and event names are frozen; per-message payload schemas are deferred to Phase 2 with the
   event schemas. The Event Explorer query API is **not** frozen (see §5.1).
3. **Prices → Order Service seeded SKU price map.** No price column added to Inventory; no synchronous
   cross-service call to price an order. Prices invented as demo constants: SKU-001 129.00,
   SKU-002 189.00, SKU-003 14.50, SKU-004 249.00. Consequence, stated in `db-ownership.md`: product
   data is split, with `display_name` in Inventory and `unit_price` in Order Service.
4. **`InventoryReleased` added to the v1 catalog**; `OrderShipped` and a hypothetical
   `FulfillmentRequested` excluded, with reasons recorded in `event-catalog.md` §4.

### Resolved while writing

5. **Kafka topic assignment rule.** The docs list domain-oriented topics but do not say which topic a
   cross-domain event belongs to. Frozen: **a service publishes only to its own domain topic**, which
   puts `PaymentRequested` (a payment-domain event published by Order Service) on `orders.events`. The
   alternative would make Payment Service consume its own output on `payments.events`, forcing every
   listener there to filter its own records.
6. **`PaymentRequested`'s publisher.** `backend-design.md`'s lifecycle diagram says "Order Service /
   Payment workflow". Frozen as **Order Service**, consuming `InventoryReserved` — the reading that
   makes `PAYMENT_PENDING` reachable.
7. **Two transitions have no causing event.** `INVENTORY_RESERVED → PAYMENT_PENDING` and
   `PAID → FULFILLMENT_PENDING` are documented as **internal** Order Service transitions rather than
   given invented events. The second means one event (`PaymentAuthorized`) drives two consecutive
   transitions in one handler, so `PAID` is short-lived and rarely observed by the UI. See §4.4.
8. **`FAILED`'s entry condition was supplied, not formalized.** The state list includes `FAILED` with
   no transitions into it. Frozen as reachable from any non-terminal state on a non-retryable failure
   or DLQ exhaustion. No catalogued domain event causes it — it is driven by consumer error handling.
9. **`OrderCreated` carries no prices.** Its only consumer is Inventory Service, which has no use for
   them, so the payload stays exactly as the docs' example shows it and `amount` travels on
   `PaymentRequested` instead.
10. **`payment_attempts.idempotency_key` = the `eventId` of the triggering `PaymentRequested`.** The
    data model has both `order_id` and `idempotency_key` without saying how they differ. This makes the
    key stable across redelivery while still allowing a future second attempt for the same order.
11. **Payment behavior override is un-scoped during Scenario 3.** The override must be armed before
    `POST /api/orders` returns an order id, so scoping it to that order would race the consumer. It
    therefore applies to all payment requests for the run's duration; order-id scoping exists on the
    endpoint for targeted manual use. A concurrently created order during a Scenario 3 run would also
    be rejected — acceptable for a single-reviewer demo, and noted in `scenarios.md`.
12. **Endpoints added beyond the sketches.** `GET /demo/scenarios` (the scenario cards need their
    metadata, and serving it stops the UI drifting from `scenarios.md`), `GET /demo/scenario-runs`
    (the Overview page shows recent runs), `GET /demo/consumers` and pause/resume on Inventory and
    Fulfillment (Scenario 5 cannot be real without them), and `GET`/`PUT`/`DELETE
    /demo/payment-behavior` (Scenario 3 likewise). All under `/demo`.
13. **`GET /api/payments/{orderId}` and `GET /api/shipments/{orderId}` were included.** The docs list
    no Payment REST surface and describe Fulfillment's as optional ("if useful"). Both are read-only
    and let the UI and tests verify outcomes without reading another service's database.
14. **Demo control plane is synchronous REST**, despite Phase 3's guidance against synchronous
    service-to-service calls. Justified as control plane rather than workflow: no order transition
    depends on it, and a listener cannot be paused from outside its own process. Documented in
    `scenario-service.yaml` and ADR-002.
15. **OpenAPI 3.1.0** chosen; `execution-plan.md` §7 says only "OpenAPI (YAML)". 3.1 aligns with JSON
    Schema 2020-12, which matters because Phase 2 adds JSON Schema for event payloads. Nullable fields
    therefore use `type: [string, 'null']`, not 3.0's `nullable: true`.
16. **Error codes were invented** — a minimal per-service set (`VALIDATION_ERROR`, `INVALID_ORDER`,
    `ORDER_NOT_FOUND`, `SKU_NOT_FOUND`, `INVENTORY_CONFLICT`, `PAYMENT_NOT_FOUND`,
    `SHIPMENT_NOT_FOUND`, `CONSUMER_NOT_FOUND`, `SCENARIO_NOT_FOUND`, `SCENARIO_RUN_NOT_FOUND`,
    `SCENARIO_ALREADY_RUNNING`, `SCENARIO_UNAVAILABLE`, `RESET_CONFLICT`, `DEMO_DISABLED`,
    `INTERNAL_ERROR`). The API Error Model defines the envelope and one example code
    (`INVALID_ORDER`), not a vocabulary.
17. **Identifier formats and ports.** String ids with prefixes, following the docs' `order-21873`
    example: `resv-`, `pay-`, `shp-`, `run-`. Local ports 8081–8085 are placeholders in `servers`.
18. **`ScenarioRunStatus.FAILED` means the harness failed**, not the scenario's subject. A scenario
    whose subject fails as designed — Scenario 2's rejection, Scenario 6's dead-lettered record — is
    `COMPLETED`, because it demonstrated what it advertised.
19. **Scenario 7 uses SKU-004 (stock 2) with two concurrent orders of 2.** See §4.5 — the two source
    docs suggest different fixtures for the same scenario.

---

## 4. Inconsistencies and gaps found in `docs/planning/`

Reported, not edited, per the brief and `.claude/CLAUDE.md`.

### 4.1 Contradictory out-of-stock state name (real inconsistency)

- `backend-design.md`'s Suggested Order States list: `OUT_OF_STOCK`
- `backend-design.md`'s failed-inventory flow diagram: `order status = REJECTED_OUT_OF_STOCK`
- `frontend-design.md`'s Scenario 2 expected terminal state: `REJECTED_OUT_OF_STOCK`

Two spellings of one state across three references, two of them in the same file. Frozen as
`REJECTED_OUT_OF_STOCK` (§3.1). **Recommended fix:** correct the states list in `backend-design.md`.

### 4.2 No price exists anywhere in the data model (gap)

`orders.total_amount` and `order_items.unit_price` are specified, and `payment_attempts.amount`
requires an amount to authorize — but no table holds a price. `inventory_items` has `display_name`
and quantities only, and the seed data lists SKUs and stock without prices. Resolved per §3.3.

### 4.3 Scenario runs have no persistence (gap)

`backend-design.md` specifies `GET /demo/scenario-runs/{runId}`, and `frontend-design.md`'s Scenario
Run Detail page specifies a stored timeline with per-event metadata — but the PostgreSQL data model
defines no scenario tables. Phase 0 added `scenario_runs` and `scenario_run_timeline` under Scenario
Service (`db-ownership.md`), as the minimum those endpoints require.

### 4.4 Two state transitions have no causing event (gap)

The draft state machine defines `INVENTORY_RESERVED → PAYMENT_PENDING` and
`PAID → FULFILLMENT_PENDING` but names no event for either, and no event in the catalog fits — for the
second, Fulfillment Service consumes `PaymentAuthorized` directly, so Order Service never sends it a
request. Handled as internal transitions (§3.7). This is a genuine design wrinkle rather than a typo:
`PAID` and `FULFILLMENT_PENDING` are arguably one state, and if a later phase wants a clean
event-per-transition machine, the options are to merge them or introduce `FulfillmentRequested` and
change what Fulfillment Service subscribes to.

### 4.5 Scenario 7's fixture differs between docs (minor inconsistency)

`frontend-design.md` illustrates inventory contention with "Available stock = 5, Order A requests 4,
Order B requests 4" — which matches SKU-002. `backend-design.md`'s Seed Data section says SKU-004's
stock of 2 exists specifically so "Scenario 7 (Inventory Contention) is trivial to trigger with two
concurrent small orders". Both work and demonstrate the same thing. Frozen on SKU-004, since that
rationale is explicit about the intent; noted in `scenarios.md`.

### 4.6 "Shared reliability tables where needed" heading (self-resolving, worth a note)

That heading in `backend-design.md`'s data model reads as though one `processed_events` table serves all
services; the next sentence requires per-service tables. Not a contradiction, but the heading is the
part an agent skimming for table ownership will see. Resolved explicitly in `db-ownership.md` §2.

### 4.7 `execution-plan.md` §3's contract-file list is incomplete (minor)

§3 enumerates "the only cross-boundary reads" as `docs/openapi/*.yaml`,
`docs/events/event-catalog.md`, `docs/events/schemas/*.json`, `docs/order-state-machine.md`, and
`docs/db-ownership.md` — omitting `docs/scenarios.md`, `docs/architecture-diagram.md`, and
`docs/adr/`, which §4's Phase 0 output row and `.claude/CLAUDE.md` both treat as frozen contracts. An
agent following §3 literally would not read `scenarios.md`, which the frontend and scenario
workstreams need. **Recommended fix:** add those three to §3's list.

---

## 5. Deliberately left open

Not oversights — decisions that belong to a later phase, recorded so they are not lost.

1. **Event Explorer's backing store and query API.** `frontend-design.md` needs cross-service event
   querying with filters (type, order, correlation id, service, topic, DLQ status), described as "a
   lightweight event projection/audit store" with no owner named. Nothing in the data model matches:
   `order_status_history` covers only order status, `scenario_run_timeline` only scenario events. Left
   unfrozen because the choice depends on Phase 2 decisions (whether a projection consumer subscribes
   to all topics; whether it belongs to Order Service or a separate read-model owner). Whoever builds
   it adds the table to `db-ownership.md` and the endpoint to the relevant spec via the coordination
   protocol.
2. **SSE per-message payload schemas** — Phase 2, with `docs/events/schemas/*.json`. Endpoints,
   content type, and event names are frozen now.
3. **SSE fan-out across replicas.** With multiple Order Service pods, a transition seen by one must
   reach clients streaming from another. Flagged in ADR-003 with the likely approach (every replica
   consumes the lifecycle topic and serves its own connections); Phase 5 must handle it or the stream
   will silently miss updates.
4. **Whether `POST /demo/reset` deletes historical orders and runs** — Phase 4/5. The argument for
   keeping them is that run history is the demo's evidence trail.
5. **Scenario 8's burst size** — Phase 10, tuned against real measurements. Seeded SKU-003 stock of 100
   bounds it at 100 single-unit orders before a reset.
6. **`processed_events` retention.** The ledger grows monotonically; pruning is safe once records pass
   Kafka's retention. Not urgent at demo volume.
7. **Consumer-group naming, partition counts, retry/backoff parameters, and DLQ payload shape** —
   Phase 4's reliability pattern (`execution-plan.md` §4 assigns it its own design step). Phase 0
   fixed only topic names, keys, and DLQ topic names.

---

## 6. Reproducing the verification

From the repo root, with `ruby` (system Ruby is sufficient — no gems needed):

```bash
ruby -ryaml -e 'Dir.glob("docs/openapi/*.yaml").each { |f| d = YAML.safe_load(File.read(f, encoding: "UTF-8")); puts "#{f}: openapi=#{d["openapi"]} paths=#{d["paths"].size}" }'
```

The four consistency checks were run from a script that parses the catalog's event sections, the state
machine's transition table, the ownership table, and the scenario enum, then compares them. Its output
is summarized in §2; the script itself was a scratch file and is not checked in, since the checks are
worth re-running as a CI step against the real specs rather than preserved as a one-off.

**Suggested for CI (Phase 7):** a real OpenAPI validator over `docs/openapi/*.yaml`, plus the four
cross-document checks, so contract drift fails a build instead of being discovered by an agent
mid-implementation.
