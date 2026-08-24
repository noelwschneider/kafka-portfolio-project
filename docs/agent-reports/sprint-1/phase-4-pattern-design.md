# Phase 4 Report — Reliability Pattern Design (sequential step)

**Date:** 2026-08-18
**Scope:** the "design once" step of Phase 4 per `docs/planning/execution-plan.md` §4 — the
`processed_events` idempotency check, the retry/backoff policy and DLQ routing, designed once,
documented in `docs/reliability-pattern.md`, built as shared code in `services/common/`, and applied
end to end to Inventory Service as the reference implementation and fan-out template.
**Also closes:** the two gaps `docs/agent-reports/phase-3-inventory-concurrency.md` §7 deferred here,
and the `/demo/consumers` endpoints frozen in Phase 0 and unimplemented since
(`docs/agent-reports/phase-1.md` §3.8).

**Result: 23 tests, 0 failures**, green on two consecutive full-suite runs (13 before this step, 23
after), plus live-stack verification of Scenarios 4, 5 and 6 against real Docker infrastructure.

**Frozen-contract edits:** exactly one, the pre-authorised `db-ownership.md` addition of §7.2 below,
made through `docs/planning/execution-plan.md` §5's coordination protocol and broadcast in the new
`docs/CHANGELOG-contracts.md`. No file under `docs/planning/`, `docs/openapi/`, `docs/events/`,
`docs/adr/`, `docs/order-state-machine.md`, `docs/scenarios.md` or `docs/architecture-diagram.md`
was touched. No Order/Payment/Fulfillment Service code was touched.

---

## 1. What was built, at a glance

| Where | What |
|---|---|
| `docs/reliability-pattern.md` | **New.** The operational reference: ledger usage, exception classification, Spring Kafka wiring with numbers and reasons, pause/resume mechanism, honest limits, and a 10-point fan-out checklist. |
| `services/common/.../idempotency/` | **New.** `ProcessedEventKey`, `ProcessedEventLedger` — the whole idempotency mechanism, one class. |
| `services/common/.../kafka/` | **New.** `ConsumerErrorHandlerFactory`, `DeliveryAttemptTracker`, `DlqHeaders`, `ConsumerControl`, `ConsumerState`. Extended: `KafkaTopics` (4 DLQ constants), `KafkaTopicConfig` (4 DLQ topics). |
| `services/inventory-service/` | Reference implementation: 2 migrations, idempotency in both consumers, 1 error-handler bean, 1 demo controller, 4 new test classes. |
| `docs/db-ownership.md` | The one narrow contract addition (Gap 2). |
| `docs/CHANGELOG-contracts.md` | **New.** The broadcast mechanism §5 rule 3 asks for, with its first entry. |

---

## 2. Design decisions in `docs/reliability-pattern.md`, and why

ADR-005 is detailed and frozen, so this step operationalized it rather than re-deciding it. Four
places needed a real decision, because ADR-005 stops short of them.

### 2.1 The ledger *insert* is the authority, not the ledger *read*

ADR-005 says "check whether present; if so, acknowledge; otherwise apply and record". Taken
literally that is a check-then-act race, and Inventory Service runs **three listener threads per
topic** (`concurrency: 3`, set by the concurrency workstream), so two deliveries of the same event
genuinely can both read "absent".

The implementation therefore splits the two roles explicitly:

- `isProcessed()` is a **cheap early-out** — it saves the second delivery from redoing work and
  produces the visible "consumed twice, applied once" log line. Never load bearing.
- `recordProcessed()` — `INSERT … ON CONFLICT DO NOTHING`, inside the business transaction — is the
  **guard**. A concurrent duplicate blocks on the uncommitted row and then sees zero rows affected.

This is a strengthening of ADR-005, not a departure: the ADR's actual requirement is that the ledger
row and the business change commit together, which this satisfies exactly.

### 2.2 The claim goes in the innermost transactional method

`recordProcessed()` is annotated `@Transactional(propagation = MANDATORY)`, so calling it outside a
transaction fails loudly. That mechanically enforces ADR-005's "no side effect may escape the
transaction boundary" rule instead of leaving it to reviewer vigilance in four services.

Inventory Service makes the placement non-obvious and therefore worth documenting: `reserve` retries
optimistic-lock conflicts 25 times, each attempt its own `REQUIRES_NEW` transaction. The claim had
to go inside `InventoryReservationExecutor.attemptReserve` — the transaction itself — not around the
retry loop. A pleasant consequence: an attempt that loses the CAS rolls its ledger row back too, so
a reservation that takes seven attempts still leaves exactly one ledger row, written by the attempt
that committed.

### 2.3 Exception classification, made concrete — and an honest finding

`backend-design.md` §8.2's categories are generic. Making them concrete for this codebase surfaced
something worth stating plainly in the doc:

> In this codebase, almost every failure a record's own *content* can cause is non-retryable. A
> record that fails deterministically is by definition not worth retrying.

Non-retryable (dead-lettered on the first delivery): `UnsupportedEventVersionException` (required by
`event-catalog.md` §5), `tools.jackson.core.JacksonException`,
`org.springframework.dao.NonTransientDataAccessException` — Spring's own name for "this will fail
the same way next time" — and `IllegalArgumentException`. Retryable: everything else, notably
`ObjectOptimisticLockingFailureException`, which is a `TransientDataAccessException` and so is
deliberately *not* caught by the `NonTransientDataAccessException` entry.

**Unrecognised exceptions default to retryable.** Justification: a wrongly-retried permanent failure
costs 3.5 seconds and still reaches the DLQ with its metadata intact; a wrongly-non-retried
transient failure discards real work.

The consequence for Scenario 6 is that its poison records show `x-delivery-attempts: 1`, and the
tests assert that number. Reporting the configured maximum instead would have been a lie about what
happened — and asserting `1` is precisely what makes the classification *testable*: a service that
retried these anyway fails the test. Bounded retries are proven separately on the retryable arm
(§5.3).

### 2.4 Backoff numbers, justified rather than round

`ExponentialBackOff`, 3 retries, 500 ms initial, ×2, 2 s cap, no jitter — four deliveries spaced
0.5 / 1 / 2 s, ~3.5 s total. Three constraints drove it: retrying **blocks the partition**, so a
much larger budget turns one poison record into a partition outage; the retryable class is genuinely
transient and clears in milliseconds-to-seconds; and Scenario 6 asks a reviewer to *watch* the
retries, so ~3.5 s is long enough to see and short enough to sit through. Jitter is off because the
retry timing is part of what the scenario demonstrates.

### 2.5 `pause()`, not `stop()` — a deliberate deviation from the brief's wording

The brief said "container stop/start". I used `pause()`/`resume()` instead, and the reasoning is in
`docs/reliability-pattern.md` §6:

`stop()` leaves the consumer group, triggering a rebalance out and another back in. In the
multi-instance deployment Phase 10 will actually demonstrate, that hands the "paused" instance's
partitions to a running one, which quietly processes the backlog — the opposite of what Scenario 5
shows. `pause()` keeps the consumer in the group with its partitions assigned and simply stops
delivering. It also matches both frozen contracts' own vocabulary: `docs/scenarios.md` says "a
genuine Spring Kafka listener-container **pause**", and the OpenAPI `ConsumerState` field is
`paused`. The live run in §6.3 confirms the consumer stayed a group member while paused.

Two smaller decisions in the same area: the pause call **blocks until the pause is actually
effective** (bounded 10 s), because a pause takes effect on the next poll and returning early would
let Scenario 5 publish into the gap; and `paused` is read back from `isContainerPaused()` rather
than from any remembered flag, so there is nothing that can get out of step with reality. If the
wait times out, the *observed* state is returned and a warning is logged — never an optimistic
`true`.

---

## 3. What is shared in `services/common/`, and why that shape

The drift risk `execution-plan.md` §1.2 calls out is real here: this pattern is about to be copied
three more times. The rule applied was **anything that must be identical in all four services lives
in `common/`; only the per-service inputs stay local.**

| Class | Why it is shared | What a fan-out service still writes |
|---|---|---|
| `ProcessedEventLedger` | The entire idempotency mechanism, two SQL statements. | A Flyway migration and one YAML line. |
| `ProcessedEventKey` | The `(eventId, consumerName)` pair. | Its own constants for the names. |
| `ConsumerErrorHandlerFactory` | Backoff, classifier, recoverer, headers — the policy itself. | One `@Bean` naming its DLQ topic. |
| `DeliveryAttemptTracker`, `DlqHeaders` | DLQ metadata must mean one thing across services for a single inspector UI to read it. | Nothing. |
| `ConsumerControl`, `ConsumerState` | The pause/resume machinery and the response shape, which all four OpenAPI documents define identically. | A ~30-line `@RestController` that only delegates. |
| `KafkaTopics`, `KafkaTopicConfig` | The four `.dlq` topic names and their creation. | Nothing. |

### 3.1 Why the ledger is JDBC, not JPA

This was the main judgment call. A JPA route (`@MappedSuperclass` + `@Embeddable` id + a subclass
entity + a repository interface, per service) would need ~30 lines of near-identical boilerplate in
each of the four services, and `common` could then hold only an *interface* — because the concrete
entity and repository types are per-service. That is four copies of exactly the thing most likely to
drift.

Two SQL statements against `JdbcClient` put the **whole implementation** in `common`, leaving a
fan-out service with a migration and one line of configuration. `JdbcClient` joins the ambient
transaction (Spring's `JpaTransactionManager` exposes its connection via `DataSourceUtils`), so the
atomicity requirement is met. As a bonus, `INSERT … ON CONFLICT DO NOTHING` is atomic in a way a
JPA `findById`-then-`save` is not (§2.1).

The schema differs per service, so the qualified table name is a property
(`orderfulfillment.reliability.processed-events-table`), validated against a restrictive identifier
regex since it is interpolated into SQL. It has an unqualified default purely so the three services
that have not yet done their fan-out still start; they never call the bean.

Trade-off accepted honestly: this is hand-written SQL in a project that otherwise uses JPA, and it
is Postgres-specific (`ON CONFLICT`). Both are fine here — the table is frozen and will never gain a
column or be queried by a relationship, and Postgres is a pinned decision.

### 3.2 Why the demo controller is *not* shared

`ConsumerControl` (the logic) is shared; `DemoConsumerController` is not, because a
`@RestController` has to live in the service's own scanned package and because each service's
OpenAPI document owns its own path surface. The controller is deliberately trivial — three
one-line delegations — so there is nothing in it to drift.

### 3.3 Rule 16 ("avoid hidden magic in shared libraries")

Everything shared is an ordinary Spring bean, wired explicitly: no auto-configuration, no
`spring.factories`, no annotation processing, no conditional bean magic. A service opts into the
error handler by declaring one `@Bean`; it opts into the ledger by calling it.

---

## 4. Inventory Service reference implementation

| File | Change |
|---|---|
| `db/migration/V3__reserved_within_available.sql` | **New** — Gap 2's CHECK constraint. |
| `db/migration/V4__processed_events.sql` | **New** — the ledger, matching `db-ownership.md` §2 exactly. |
| `InventoryConsumers.java` | **New** — the two listener ids and the two `consumer_name`s, with a note on why the namespaces are separate. |
| `InventoryKafkaReliabilityConfig.java` | **New** — one `DefaultErrorHandler` bean → `inventory.dlq`. |
| `DemoConsumerController.java` | **New** — `/demo/consumers` GET + pause + resume, per the frozen OpenAPI shape. |
| `InventoryOrderEventsConsumer.java` | `id = "order-created"`; filter → early-out → keyed reserve → skip publish on duplicate. |
| `InventoryPaymentEventsConsumer.java` | Same, `id = "payment-rejected"`, `inventory.payment-rejected`. |
| `InventoryReservationExecutor.java` | Ledger claim as the first statement of both `REQUIRES_NEW` transactions. |
| `InventoryService.java` | Event-key overloads of `reserve`/`release`; retry-exhaustion comment now names the DLQ destination. |
| `ReservationResult.java`, `ReleaseResult.java` | A `duplicate` outcome, distinct from "failed". |
| `InventoryServiceApplication.java` | `excludeFilters = TypeExcludeFilter` — see §4.1. |
| `application.yml` | `orderfulfillment.reliability.processed-events-table`. |
| 4 new + 3 amended test classes | §5. |

**Why `duplicate` is a distinct outcome and not just "failed":** a failed reservation is a real
business answer the order is waiting for, so it publishes `InventoryReservationFailed`. A duplicate
means an earlier delivery already produced and published that answer. Collapsing the two would
publish the outcome twice — the downstream half of the duplicate side effect Scenario 4 exists to
rule out, and the reason one of the Scenario 4 tests asserts on the outcome topic rather than only
on the database.

### 4.1 A latent defect found and fixed in passing

The first full test run showed `retry-probe` — a test-only listener defined in one test class's
`@TestConfiguration` — appearing in *every* test's `GET /demo/consumers`. Cause:
`InventoryServiceApplication` declares `@ComponentScan` by hand (it must, to pick up
`com.orderfulfillment.common`), and a hand-written `@ComponentScan` silently discards
`@SpringBootApplication`'s `TypeExcludeFilter` — the filter whose job is to keep
`@TestConfiguration`/`@TestComponent` classes on the test classpath out of contexts that did not ask
for them. Fixed by restoring the filter explicitly.

Worth flagging to the fan-out: **all four services use the same hand-written `@ComponentScan`
pattern**, so all four have this latent behaviour. It is harmless until a service adds a test
fixture bean, at which point it silently pollutes every other test's context. Order, Payment and
Fulfillment should apply the same one-line fix. (Not done here — other services' code is out of
bounds for this step.)

---

## 5. How each scenario's exit criteria was verified

`implementation-phases.md`'s Phase 4 exit criterion is "each advertised failure scenario is backed
by an automated integration test". All of the below run against real Testcontainers Kafka
(`apache/kafka:4.0.0`) and Postgres (`postgres:16-alpine`), with no mocking of the mechanism under
test and **no hardcoded sleeps** — every wait is a real predicate via Awaitility or a bounded
poll-until-found loop.

### 5.1 Scenario 4 — Duplicate Event Delivery — `InventoryDuplicateEventIntegrationTest` (2 tests)

The duplicate is a genuine republish: one envelope is serialized **once** and sent **twice**, so the
second delivery is byte-identical including its `eventId`, the identity `event-catalog.md` §1 says a
redelivery reuses. Assertions are read back from the database, not inferred from return values.

- `republishingTheSameOrderCreatedEventReservesOnlyOnce` — after the second delivery: exactly one
  `inventory_reservations` row, `reserved_quantity` advanced exactly once, and exactly one
  `processed_events` row for `(eventId, "inventory.order-created")`. The negative is asserted with
  `await().during(5s)` so the condition must hold *continuously* — a single sample could pass merely
  by looking before the consumer got there.
- `theDuplicateDoesNotRepublishTheOutcomeEvent` — exactly one `InventoryReserved` on
  `inventory.events` for that order, counted over a 15-second drain of the topic.

### 5.2 Scenario 5 — Consumer Outage and Recovery — `InventoryConsumerOutageIntegrationTest` (3 tests)

Driven through the **real HTTP demo endpoints**, not by reaching into the registry from the test —
deliberately, since those endpoints are what actually ships and a test that bypassed them would not
notice, say, a pause that returns before it has taken effect.

- `listConsumersReportsBothListenersRunning` — the frozen `ConsumerState` shape: exactly the two
  listeners, correct topics, correct group, all running.
- `aPausedListenerHoldsItsBacklogAndDrainsItOnResume` — pause (asserted both in the POST response
  and by re-reading `GET /demo/consumers`), publish, assert **for five continuous seconds** that no
  reservation and no inventory change occurred, resume, then assert the backlog drains to exactly
  the right reservation.
- `pauseAndResumeAreIdempotentAndUnknownListenersAre404` — the idempotency the OpenAPI description
  promises, and the `404`.

An unconditional `@AfterEach` resume keeps a mid-test failure from leaving the listener paused and
breaking every subsequent test — the ordering-dependence trap
`phase-3-inventory-concurrency.md` §5.3 documents.

### 5.3 Scenario 6 — Poison Message / DLQ — two classes, 3 tests

**`InventoryPoisonMessageIntegrationTest` (2 tests)** — both poison forms `docs/scenarios.md` names,
published as real bytes to `orders.events`:

- an envelope with `eventVersion: 99` → `UnsupportedEventVersionException`;
- a valid envelope whose `payload` is a bare string → `JacksonException` (a different code path:
  past `decode()`, failing in `payloadAs()`).

Each asserts the record lands on `inventory.dlq` with **the original key and the original bytes**
(so a corrected replay is possible) and with every piece of metadata a future inspector UI needs:
original topic, partition, offset, consumer group, root-cause class and message, stack trace,
dead-lettering timestamp, `x-failure-retryable: false`, and `x-delivery-attempts: 1` — the honest
count, which is also the assertion that proves the non-retryable classification is real.

**`KafkaRetryAndDlqIntegrationTest` (1 test)** — the retryable arm, which no *record* can
demonstrate (§2.3). A listener on its own topic raises
`ObjectOptimisticLockingFailureException` — **exactly the exception Gap 1 is about** — while the
machinery under test is the unmodified production configuration. Asserts: exactly 4 deliveries and
no more; `x-delivery-attempts: 4`; `x-failure-retryable: true`; the root-cause class; and that the
elapsed span between first and last delivery is **≥ 3 s**, proving real backoff rather than a hot
loop (asserted as a lower bound only — an upper bound would be asserting on the machine's
scheduler).

### 5.4 Scenario 7 — not redone

Already proven by `phase-3-inventory-concurrency.md`; its 5 tests still pass unchanged.

### 5.5 Running them

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
mvn -DskipTests install
mvn -pl services/inventory-service test
```

```
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Green on two consecutive full-suite runs, specifically to confirm order-independence rather than
passing by ordering luck. (Note: a stale incremental-compile artifact produced a spurious
"cannot access IdGenerator" / "Unresolved compilation problems" failure mid-session; `mvn clean`
resolves it. Worth knowing before debugging a phantom.)

**The other three services were re-verified too**, because the new `services/common/` beans are
component-scanned by all four and a missing `JdbcClient` or `KafkaTemplate` would have broken
startup. `mvn clean install` across the whole monorepo: **44 tests, 0 failures** — order-service 13,
inventory-service 23, payment-service 7, fulfillment-service 1, `BUILD SUCCESS`. None of those three
services' files was modified; they simply start correctly with the shared beans present and unused.

---

## 6. Manual live-stack verification

Not Testcontainers: real `docker compose` infrastructure with a volume reset first
(`docker compose down -v`), and Inventory Service via `mvn spring-boot:run`. Scenario 6 was the
required one; Scenarios 4 and 5 and the new constraint were checked while the stack was up.

All four migrations applied to real Postgres, and all four DLQ topics were created for real:

```
Successfully applied 4 migrations to schema "inventory_service", now at version v4
__consumer_offsets  fulfillment.dlq  fulfillment.events  inventory.dlq  inventory.events
orders.dlq  orders.events  payments.dlq  payments.events
```

```json
GET /demo/consumers ->
[{"name":"order-created","topic":"orders.events","groupId":"inventory-service","paused":false},
 {"name":"payment-rejected","topic":"payments.events","groupId":"inventory-service","paused":false}]
```

### 6.1 Scenario 6, live (the required check)

A poison `OrderCreated` with `eventVersion: 99` published to `orders.events` with
`kafka-console-producer.sh`. Service log:

```
ERROR ... ConsumerErrorHandlerFactory : Dead-lettering orders.events-1@0 to inventory.dlq
        after 1 delivery attempt(s) (non-retryable failure)
Caused by: com.orderfulfillment.common.kafka.UnsupportedEventVersionException:
        Unsupported eventVersion 99 for eventType OrderCreated
```

Read back off `inventory.dlq` with `kafka-console-consumer.sh --property print.headers=true`:

```
x-delivery-attempts               = 1
x-failure-retryable               = false
x-failure-class                   = com.orderfulfillment.common.kafka.UnsupportedEventVersionException
x-failure-message                 = Unsupported eventVersion 99 for eventType OrderCreated
x-dead-lettered-at                = 2026-08-18T20:24:05.732138Z
kafka_dlt-original-topic          = orders.events
kafka_dlt-original-consumer-group = inventory-service
key                               = order-live-poison-1
value                             = <the original record, byte for byte>
```

Scenario 6's success condition holds against the real running stack, with the error inspectable and
the retry count carried honestly.

### 6.2 Scenario 4, live

The same envelope published twice to `orders.events` (5 × SKU-003):

```
reservedQuantity: 5      version: 1        <- one write, not two
inventory_reservations:  1 row (order-live-dup-1, SKU-003, 5, RESERVED)
processed_events:        1 row (c9c49eef-…, inventory.order-created)
INFO ... Skipping duplicate delivery of OrderCreated c9c49eef-… for order-live-dup-1
```

`version` advancing `0 → 1` is the direct evidence that the second delivery never touched the row.
This run also incidentally proves the poison record of §6.1 did not block its topic — a later good
record processed normally.

### 6.3 Scenario 5, live

```
POST /demo/consumers/order-created/pause -> {"name":"order-created",...,"paused":true}
publish OrderCreated (2 × SKU-002)
SKU-002 while paused: reservedQuantity 0, version 0        <- nothing processed
consumer group: orders.events-0  LOG-END-OFFSET 1, CURRENT-OFFSET -, consumer-id present
POST /demo/consumers/order-created/resume -> {"paused":false}
SKU-002 after resume: reservedQuantity 2, version 1        <- backlog drained
```

The `consumer-id` still being listed while paused is the concrete evidence for §2.5's argument: the
consumer stayed a member of its group rather than leaving it, so nothing was rebalanced away.

### 6.4 The new CHECK constraint, live

```sql
UPDATE inventory_service.inventory_items SET reserved_quantity = 999 WHERE sku='SKU-002';
ERROR:  new row for relation "inventory_items" violates check constraint
        "inventory_items_reserved_within_available"
```

---

## 7. How Gap 1 and Gap 2 were resolved

### 7.1 Gap 1 — retry exhaustion now has a contract-legal destination

**Resolution: routing, with no contract change.** `ObjectOptimisticLockingFailureException` is a
`TransientDataAccessException`, so it classifies retryable. When it escapes `InventoryService.reserve`
after 25 optimistic-lock attempts, the record is redelivered up to three more times with
0.5 / 1 / 2 s backoff — each redelivery a *fresh* 25-attempt loop against fresh state, at a moment
far enough away that contention has almost certainly cleared — and if it still fails it lands on
`inventory.dlq` with full metadata.

**The reasoning holds, confirmed now that the mechanism exists.** `event-catalog.md` §2 already
freezes the `.dlq` topics as the generic destination for *"Records that exhausted retries, plus
failure metadata"*, published by *"the failing consumer"*. That is precisely this case. So **no
`reason` enum value was added and no frozen file was changed.** Every step is safe because the
losing attempt's transaction — ledger row included — rolled back, so a redelivery re-reads fresh
state and cannot double-write. The path is real, not theoretical:
`KafkaRetryAndDlqIntegrationTest` drives that exact exception class through the production error
handler and asserts it reaches `inventory.dlq` with `x-delivery-attempts: 4`.

**The order-side consequence, stated plainly as required.** An order whose reservation is
dead-lettered still never receives `InventoryReserved` or `InventoryReservationFailed`, so **it
stays in `PENDING`**. Phase 4 changed that from *silently lost* (logged, seeked past, gone) to
*preserved and inspectable* (a DLQ record with the original bytes and the failure metadata, replayable
after correction), which is the reliability half of the problem. **Order Service gets no defined
behaviour for this from me**, and that is deliberate: "what should happen to an order whose
reservation never resolves" is a state-machine question about `docs/order-state-machine.md`, not a
reliability-pattern one, and it is out of this step's scope. It is flagged in
`docs/reliability-pattern.md` §5 for whoever owns that contract. The plausible options are a timeout
transition, an operator-driven replay of the dead-lettered record, or an explicit "stuck" state;
none of them belong to this step, and inventing one unilaterally would repeat exactly the
frozen-contract violation §7.1 of the previous report declined to commit.

### 7.2 Gap 2 — database-level backstop for the core invariant

Resolved by actually amending the contract, through `execution-plan.md` §5's own process. Three
artifacts:

**1. `docs/db-ownership.md`** — the `inventory_items` block gains one line, plus a one-line
rationale paragraph immediately below the table:

```diff
 version             bigint NOT NULL      -- JPA @Version, optimistic locking
 updated_at          timestamptz NOT NULL
+CHECK (reserved_quantity <= available_quantity)
```

> `CHECK (reserved_quantity <= available_quantity)` **states Scenario 7's invariant directly**,
> rather than leaving the two per-column checks to imply it — added in Phase 4 because a real
> application-level bug wrote `reserved_quantity = 4` against `available_quantity = 2` and the
> database accepted it (`docs/agent-reports/phase-3-inventory-concurrency.md` §4, §7.2; migration
> `V3__reserved_within_available.sql`; broadcast in `docs/CHANGELOG-contracts.md`).

Nothing else in that file was touched.

**2. `V3__reserved_within_available.sql`** — a real migration, applied to the live schema in §6
(`Migrating schema "inventory_service" to version "3 - reserved within available"`) and enforcing for
real (§6.4).

**3. `docs/CHANGELOG-contracts.md`** — created, with the first entry: what changed, why, and who
must re-check. The short answer for other workstreams is **nobody**: no other service reads or
writes `inventory_items` (the one-owner rule), and no event payload or API shape changed. The only
people affected are anyone writing to that table in a seed script or fixture, for whom an
over-reservation now fails outright instead of succeeding silently.

---

## 8. What the fan-out step needs to replicate

Order, Payment and Fulfillment Service each need the following. `docs/reliability-pattern.md` §8 is
the same list with more detail; between it and this report, a fan-out agent should not need to
re-read ADR-005, `backend-design.md` §8, or this file's earlier sections.

1. **Migration** — `processed_events` in your schema, copying Inventory's
   `V4__processed_events.sql` verbatim. Shape is frozen; do not vary it.
2. **One YAML line** — `orderfulfillment.reliability.processed-events-table:
   <your_schema>.processed_events`.
3. **A constants class like `InventoryConsumers`** — for each listener, its `@KafkaListener` `id`
   (matching your OpenAPI `/demo/consumers` example) and its `processed_events.consumer_name`
   (`<service>.<event>`). These are two different namespaces; keep both, keep them in step, never
   conflate them, and never derive either from anything that varies between restarts.
4. **`id = "..."` on every `@KafkaListener`.** Without it the demo endpoints have no stable name.
5. **Idempotency in each listener**, in this order: filter to the event types you handle → ledger
   `isProcessed()` early-out → pass the `ProcessedEventKey` *down into the method that owns the
   business transaction* → that method calls `recordProcessed()` as its **first statement** and
   aborts if it returns `false`. Give your result type a `duplicate` outcome distinct from
   "failed", so a duplicate publishes nothing while a genuine failure still publishes its outcome
   event. **Filter before you claim** — do not write ledger rows for events you ignore.
6. **One `@Configuration` with one `DefaultErrorHandler` bean** from
   `ConsumerErrorHandlerFactory.create(KafkaTopics.<YOUR>_DLQ)`. Do not re-tune backoff or the
   classifier locally; if your service genuinely needs different numbers, change them in `common/`
   for everyone and say why.
7. **Confirm your defence-in-depth constraint is really in the schema**, per ADR-005:
   `payment_attempts.idempotency_key UNIQUE`, `shipments.order_id UNIQUE`.
8. **A thin `/demo/consumers` controller** delegating to `ConsumerControl` — copy
   `DemoConsumerController`, no logic of your own.
9. **Three integration tests**, real containers, no mocks of the mechanism, no sleeps: duplicate
   (identical republish → one side effect, one ledger row, no second outcome event); outage (pause
   through the real HTTP endpoint → assert nothing happens *for a continuous interval*, not one
   sample → resume → backlog drains); poison (unprocessable record → your `.dlq` with original bytes
   and honest `x-delivery-attempts`).
10. **Apply §4.1's `TypeExcludeFilter` fix** to your `@ComponentScan`, before you add any
    `@TestConfiguration` bean.
11. **Do not re-implement** the ledger, error handler, backoff, DLQ headers or pause mechanism
    locally. If `services/common/` does not fit your service, that is a finding to report, not
    something to work around (`execution-plan.md` §5 rule 4).

**Things you may be tempted to get wrong**, all of which cost time here:

- Claiming the event in the listener or in a wrapper transaction instead of in the innermost
  transactional method. It compiles, it passes a naive test, and it breaks the guarantee.
- Asserting a retry count of 4 on a poison record. Poison records are non-retryable; the honest
  number is 1, and asserting 1 is what proves the classification.
- Asserting on `kafka_dlt-exception-fqcn`. That is always
  `ListenerExecutionFailedException`; use `x-failure-class` for the root cause.
- Proving a negative with a single sample. Use `await().during(...)`.

---

## 9. Judgment calls and honest limits

1. **`pause()` over `stop()`**, against the brief's wording — §2.5. Both frozen contracts say
   "pause", and `stop()` would defeat the scenario under multi-instance scaling.
2. **JDBC ledger over JPA** — §3.1. Trade-off: hand-written, Postgres-specific SQL in an otherwise
   JPA codebase, accepted because it removes four copies of the highest-drift-risk code.
3. **Two DLQ test classes rather than one.** Scenario 6's own records cannot demonstrate bounded
   retries, because they are non-retryable by contract. Merging them would have forced either a
   dishonest assertion or a silent gap in coverage of the retryable arm.
4. **A test-defined failing listener for the retryable test.** It is the *stimulus*, not a mock of
   the mechanism: the error handler, backoff, classifier, recoverer and tracker are all the
   unmodified production beans, and the exception it raises is exactly Gap 1's.
5. **Fixed the `TypeExcludeFilter` leak rather than routing around it** (§4.1). Working around it
   would have left a real defect in shipping code, and the fan-out is about to hit it three more
   times.
6. **Added `x-failure-class` / `x-failure-message` headers** beyond Spring's defaults, because
   Spring's `kafka_dlt-exception-*` headers report the framework wrapper, which is identical for
   every failure and tells a UI nothing.

**Limits of what this step proves.**

- The retryable arm's exhaustion is proven with a synthetic stimulus, not by a naturally-occurring
  25-conflict pileup. Producing that from outside is not achievable deterministically; the
  in-process conflict path itself is proven by `phase-3-inventory-concurrency.md` §5.1.
- The DLQ has no replay path yet. The records carry the original key and bytes so replay is
  *possible*, and `backend-design.md` §8.3 lists "optionally retry/replay" as a UI affordance; it is
  not built, and this report does not claim it is.
- The ledger grows monotonically; no retention policy exists yet (ADR-005 accepts this at demo
  volume).
- The dual-write window is untouched — a crash between commit and publish still loses an event.
  That is ADR-006 / Phase 6, and it is stated as an open limit in `docs/reliability-pattern.md` §7
  rather than glossed over.
- **This is not exactly-once**, and nothing written in this step says or implies that it is.
