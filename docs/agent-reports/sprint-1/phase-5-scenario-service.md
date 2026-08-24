# Phase 5 — Scenario Service

**Scope:** build `services/scenario-service/` (port 8085) end to end per
`docs/openapi/scenario-service.yaml` and `docs/scenarios.md`: the 7 in-scope demo scenarios, run
persistence/timeline/SSE, `/demo/reset`, and the cross-service event projection that resolves
`docs/db-ownership.md`'s open "Event Explorer's backing store has no owner yet" item.

---

## 1. Service structure

```
services/scenario-service/
  pom.xml                                        new root-aggregator module, added to root pom.xml
  src/main/resources/application.yml              port 8085, downstream service URLs, scenario tuning
  src/main/resources/db/migration/
    V1__scenario_runs.sql                         scenario_runs, scenario_run_timeline (frozen shape)
    V2__events.sql                                events (Phase 5 addition, see §3)
  src/main/java/com/orderfulfillment/scenario/
    ScenarioServiceApplication.java
    config/            ServiceUrlsProperties, ScenarioProperties, RestClientConfig, AsyncConfig
    domain/             JPA entities + repositories for scenario_runs, scenario_run_timeline, events
    catalog/             ScenarioCatalog — single source GET /demo/scenarios is served from
    dto/                 API response records
    clients/             thin RestClient wrappers: OrderServiceClient, ConsumerControlClient,
                          PaymentServiceClient, InventoryServiceClient
    runtime/             RunRegistry (409 guard + correlationId->runId), TimelineRecorder,
                          RunEventHub (SSE), OrderStatusWatcher, ScenarioRunMapper
    projection/          EventProjectionConsumer, EventQueryService
    scenarios/           ScenarioRunner + 7 implementations, ScenarioExecutionService,
                          ScenarioRunExecutor, ScenarioRunQueryService, RunIdGenerator
    admin/               DemoResetService
    web/                 ScenarioController, ScenarioRunController, DemoAdminController, EventController
  src/test/java/...      AbstractIntegrationTest (Testcontainers Kafka+Postgres + WireMock) + 8 test classes
```

Depends on `services/common` for the envelope/codec/publisher, `KafkaTopics`, `ApiError` /
`GlobalExceptionHandler` / `ApiException` family, and `CorrelationIdHolder`/`CorrelationIdFilter`.
Nothing in `services/common` or any sibling service was modified.

---

## 2. Each in-scope scenario

All 7 (`high-volume` excluded per `docs/scenarios.md` — see §5) share one shape:
`ScenarioExecutionService.start()` validates the name against `ScenarioCatalog`, claims the
`scenarioName -> runId` slot in `RunRegistry` (single `putIfAbsent`, so two racing calls provably
conflict), persists a `RUNNING` `scenario_runs` row, then hands off to `ScenarioRunExecutor` (a
separate `@Async`-proxied bean — self-invocation from the same class would have silently run
synchronously) which runs the matching `ScenarioRunner` inside `CorrelationIdHolder.runInScope`, so
every downstream HTTP call and Kafka publish the runner makes carries the run's correlationId
(`RestClientConfig`'s interceptor sets `X-Correlation-Id` from the holder on every outbound request).

| Scenario | Implementation | Verified |
|---|---|---|
| `standard-order` | Sets `DEFAULT_SUCCESS`, creates 2×SKU-001, polls to `FULFILLED`. | Integration test (WireMock) + **live full-stack run**, §4. |
| `out-of-stock` | Creates 5×SKU-004 (2 seeded), polls to `REJECTED_OUT_OF_STOCK`. | Integration test. |
| `payment-failure` | Arms `REJECT` before order creation, creates 1×SKU-001, polls to `PAYMENT_FAILED`, clears the override in a `finally`. | Integration test asserts both the terminal state and the arm-before/clear-after ordering. |
| `duplicate-event` | Creates an order, polls the `events` projection for its own `OrderCreated` row, then republishes that exact envelope (same `eventId`/key/payload) to `orders.events` via `KafkaTemplate` directly. | Integration test asserts two genuine Kafka records with the identical `eventId`. |
| `consumer-outage` | Pauses Inventory Service's real `order-created` listener via `POST /demo/consumers/order-created/pause`, creates an order, sleeps `consumer-outage-pause-ms` (4s default), resumes, then polls to `FULFILLED`. | Exercised in the live run's neighborhood (manual `/demo/consumers` check); not separately in the automated suite — see §6 gaps. |
| `poison-message` | Publishes a **well-formed** `OrderCreated` envelope (valid `eventVersion`) whose payload is missing the required `items` field, directly to `orders.events`, keyed by a synthetic order id never created via `POST /api/orders`. | Integration test asserts the record on the wire; DLQ landing verified in the live run's neighborhood only (Inventory Service isn't present in the isolated suite — §6). |
| `inventory-contention` | Two genuinely concurrent `POST /api/orders` for 2×SKU-004 (2 seeded) on separate threads, each explicitly re-entering `CorrelationIdHolder.runInScope` (a `ThreadLocal`, so pool threads start empty), then watches both orders to their terminal state. | Not covered by an automated test (see §6); logic reviewed against Inventory Service's documented optimistic-locking mechanism, which is what actually decides the winner/loser. |

**`poison-message`'s mechanism, precisely.** The payload is missing `items`, not an unsupported
`eventVersion`. An unsupported version would be classified non-retryable by
`ConsumerErrorHandlerFactory` and dead-letter on the *first* delivery — which technically satisfies
"lands in the DLQ" but not `docs/scenarios.md`'s actual description, "applies bounded retries with
backoff, exhausts them". With `items` missing, Inventory Service's consumer decodes the envelope fine
(`eventVersion` is valid) and then throws a `NullPointerException` when it dereferences the missing
list — an exception outside `ConsumerErrorHandlerFactory`'s non-retryable list, so it is genuinely
retried three times with backoff (~3.5s) before landing in `inventory.dlq`. Chosen deliberately over
the version-bump shortcut for this reason.

**409 guard.** `ScenarioConflictIntegrationTest` starts `standard-order` against a WireMock stub with
a 3-second fixed delay, fires a second call while the first is provably still in flight, and asserts
`409 SCENARIO_ALREADY_RUNNING` — not a race that usually happens to conflict.

---

## 3. The Event Explorer gap — how it was resolved

Per the task brief, this was resolved through the coordination protocol, not worked around locally:

1. **`docs/db-ownership.md`** — added the `events` table to §1's ownership table and a full definition
   under §3 "Scenario Service", and updated the §4 "Event Explorer's backing store has no owner yet"
   note to point at the resolution instead of leaving it open.
2. **`docs/openapi/scenario-service.yaml`** — added `GET /demo/events` (query by `eventType`,
   `aggregateId`, `correlationId`, `producer`, `topic`, `deadLettered`, paginated) plus the
   `EventRecord`/`EventRecordPage` schemas. No existing path or schema in the file was touched.
3. **`docs/CHANGELOG-contracts.md`** — new entry dated today, same shape as Phase 4's one contract
   addition (what changed, why, who's affected — answer: nobody downstream, since nothing existing
   changed).
4. No other frozen-contract file was edited.

**Consumer:** `EventProjectionConsumer` has two `@KafkaListener` methods, both in the
`scenario-service-projection` consumer group (a group name shared by no domain consumer, so this
projection never competes for partitions/offsets and never affects real delivery) — one subscribed to
the four domain topics, one to the four DLQ topics. It is idempotent against Kafka's own at-least-once
redelivery by checking `existsByTopicAndPartitionAndOffset` before inserting (that tuple uniquely
identifies one physical record; `V2__events.sql`'s `UNIQUE (topic, partition, offset)` backs it in the
database too) — this was found the hard way: the first version of this consumer used a
self-invoked-and-therefore-inert `@Transactional` on a private `project()` method, so a downstream
timeline-write failure could leave the `events` row committed while the whole operation was reported
as failed, and Kafka's default redelivery then hit the unique constraint on retry. Fixed by moving
`@Transactional` onto the actual `@KafkaListener` methods (the real proxied entry points) and adding
the existence check as a second, independent safety net.

**Honesty boundary — the "consumed" phase.** Per the task brief's own framing, two honest options were
available: omit a consumed-phase entry entirely, or find a genuinely honest way to infer consumption
without claiming Kafka-level metadata. **Chosen: omit it.** `EventRecordEntity`/`EventRecord` carry
only `topic`/`partition`/`offset`/`eventId`/`correlationId`/`aggregateId`/`producer`/`occurredAt`/
`deadLettered`/`payload` — everything a direct Kafka consumer of the *published* record can genuinely
observe. No `consumer`, `durationMs`, or `retryCount` field exists anywhere in the schema or the
entity; there was no attempt to poll another service's own state as a substitute, because the only
thing worth inferring that way (Inventory/Payment/Fulfillment's per-consumer `processed_events` row)
is exactly the data `docs/db-ownership.md`'s one-owner rule forbids reading cross-schema, and any
substitute observation (e.g., "order status changed, so presumably something consumed it") would be a
guess dressed as a fact for a field the schema's own doc string says must be *absent*, not approximated.

---

## 4. Live full-stack verification

All five services plus the docker-compose Postgres/Kafka were started for real
(`mvn -pl services/<x> spring-boot:run`, `JAVA_HOME` pointed at Temurin 21) and a real
`standard-order` run was driven end to end:

```
POST /demo/scenarios/standard-order → 202, run-102, correlationId c3a35dda-...

GET /demo/scenario-runs/run-102 (after ~1.2s):
  status: COMPLETED, orderId: order-20002, elapsedMs: 1222
  timeline:
    1  HTTP   PUT /demo/payment-behavior         statusCode 200
    2  HTTP   POST /api/orders                   statusCode 201, orderId order-20002
    3  EVENT  OrderCreated       orders.events      partition 1 offset 0  producer order-service
    4  EVENT  InventoryReserved  inventory.events   partition 1 offset 0  producer inventory-service
    5  EVENT  PaymentRequested   orders.events       partition 1 offset 1  producer order-service
    6  STATE_CHANGE  Order PENDING
    7  EVENT  PaymentAuthorized  payments.events    partition 1 offset 0  producer payment-service
    8  STATE_CHANGE  Order FULFILLMENT_PENDING
    9  EVENT  ShipmentCreated    fulfillment.events partition 1 offset 0  producer fulfillment-service
    10 STATE_CHANGE  Order FULFILLED
```

`GET /demo/scenarios` and `GET /demo/events?aggregateId=order-20002` were also exercised live and
returned the expected catalog and the same 4 domain events with real partition/offset/payload data.

One real environment issue surfaced and is worth recording: the shared Postgres volume already held
one leftover `orders` row (`order-20001`) from earlier work in this environment, and Order Service's
`IdGenerator` (an in-memory counter that always starts at `20000` on process boot) collided with it on
the very first attempt (`500`, unique-constraint violation on `order_items`). This is a pre-existing
property of `services/common`'s `IdGenerator` (out of this workstream's scope to fix — it isn't
Scenario Service's file), not a Scenario Service bug; the run itself correctly reported `FAILED` with
the real downstream error rather than swallowing it, and the very next run succeeded (the counter had
already advanced past the collision). Flagging it here since it is the same class of bug this report's
`RunIdGenerator` fix (below) addresses for Scenario Service's own ids, and would recur for Order
Service after any restart in an environment with retained history.

---

## 5. `/demo/reset` — history retention decision

**Decision: reset does not delete `scenario_runs`, `scenario_run_timeline`, or `events`.** Only
inventory quantities, paused consumers, and Payment Service's behavior override are restored/cleared —
exactly what the OpenAPI doc's `ResetResult` reports.

**Why.** `docs/scenarios.md`'s own text argues for keeping history ("the Event Explorer and run
history are the demo's evidence trail"), and nothing about correctness requires clearing it:
`scenario_runs.id` is a monotonic, DB-seeded sequence (see §7 below) that never repeats, so old rows
cannot collide with new ones, and a stale `RUNNING` row can't exist post-reset because reset itself
refuses to run while any run is `RUNNING` (`RESET_CONFLICT`, 409 — verified by
`DemoResetIntegrationTest.resetIsRejectedWhileAScenarioIsRunning`). Deleting history on every reset
would mean a reviewer who resets between two scenarios loses the ability to compare their timelines,
which is the opposite of what an evidence trail is for.

---

## 6. Judgment calls and gaps (reported, not silently fixed)

- **`SCENARIO_UNAVAILABLE` status code.** The OpenAPI doc's `runScenario` operation only documents
  `202/404/409/500`, with no explicit code for "a known-but-unavailable scenario name" (`high-volume`
  is a valid `ScenarioName` enum member, just `available: false`). `404` felt wrong (the name isn't
  *unknown*), so this returns **`409`** with `code: SCENARIO_UNAVAILABLE`, grouped with the other
  "this can't run right now" case. Worth confirming against the frontend's actual handling before
  Phase 5's UI work finalizes its error-code switch.
- **Order status source: polling, not SSE.** `docs/agent-reports/phase-5-backend-prep.md` (a sibling
  workstream) has since landed `GET /api/orders/stream`, but `OrderStatusWatcher` still polls
  `GET /api/orders/{id}` on an interval, as the task brief explicitly allowed when SSE wasn't confirmed
  ready at the time this was built. **Follow-up, not done here:** switch `OrderStatusWatcher` to
  subscribe to Order Service's SSE stream instead of polling — verified live that the endpoint exists
  and works, just not wired in.
- **`inventory-contention` and `consumer-outage` have no dedicated automated integration test.**
  Both were implemented and manually spot-checked (consumer-outage's pause/resume calls exercised
  against the live stack in §4's session; contention's concurrency logic reviewed against Inventory
  Service's documented optimistic-locking mechanism) but the automated suite's 8 classes stop short of
  a dedicated test for either. Flagging as the clearest remaining test-coverage gap.
- **Automated-test isolation is WireMock, not the real four services**, following the same convention
  every other service's own test suite already uses (each service's suite starts only itself and
  simulates its neighbors — see `payment-service`'s `AbstractIntegrationTest` Javadoc). The one path
  this can't cover — Scenario Service driving the *real* four services together — is what §4's manual
  run exists to cover instead.
- **Real bug found and fixed during this work, worth calling out on its own:** `RunIdGenerator`
  originally reset to `run-101` on every process start (an in-memory `AtomicLong`), which is safe in
  isolation but collides with `scenario_runs` history that `/demo/reset` deliberately keeps across
  restarts (§5). Fixed by seeding the counter from `MAX(id)` in `scenario_runs` at startup
  (`@PostConstruct`). Surfaced by this service's own integration suite recreating the Spring context
  between test classes while sharing one Testcontainers Postgres underneath — the same failure mode,
  at smaller scale, that a real service restart in an environment with retained history would hit.

---

## 7. Orchestration-plan gap (not mine to fix)

Per the task brief: `docs/planning/execution-plan.md` §2's model/effort table has no row for "build
Scenario Service". This work ran at Sonnet/Medium by analogy to the table's closest comparable rows
("Order Service (post-extraction) | Sonnet | Medium" and "Payment + Fulfillment Service | Sonnet |
Medium"). Recommend adding an explicit row for Scenario Service to that table — it is not a smaller
task than those two; it drives all four other services' real APIs, owns a second projection table, and
carries the coordination-protocol contract change this report documents.

---

## 8. Verification summary

- `mvn -pl services/common,services/scenario-service -am compile` — clean.
- `mvn -pl services/scenario-service -am test` — **12/12 passing**: `StandardOrderScenarioIntegrationTest`,
  `OutOfStockScenarioIntegrationTest`, `PaymentFailureScenarioIntegrationTest`,
  `DuplicateEventScenarioIntegrationTest`, `PoisonMessageScenarioIntegrationTest`,
  `ScenarioConflictIntegrationTest` (3 cases: 409, unknown-name 404, high-volume unavailable),
  `DemoResetIntegrationTest` (2 cases: full reset, 409-while-running), `EventProjectionIntegrationTest`
  (2 cases: domain record projected, DLQ record projected as dead-lettered).
- `mvn install -DskipTests` at the repo root — clean, confirms the new module doesn't break the
  reactor.
- Live full-stack `standard-order` run against all five real services + real Postgres/Kafka — §4.
- `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` used throughout, per the
  task's JDK 26/Mockito note.
