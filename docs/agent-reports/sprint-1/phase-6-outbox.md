# Phase 6 — Transactional Outbox (Order Service)

**Scope:** close the database/Kafka dual-write window in Order Service, per
`docs/adr/ADR-006-transactional-outbox-for-db-kafka-consistency.md` and the `outbox_events` table
frozen in `docs/db-ownership.md` §2. Inventory, Payment and Fulfillment Service are deliberately out
of scope and still publish after committing.

`docs/planning/implementation-phases.md`'s Phase 6 asks for three things to be documented — the
original failure mode, the new design, the tradeoffs. They are §1, §2 and §4 below.

---

## 1. The original failure mode

`OrderService.createOrder` did two writes that had to either both happen or neither, in two systems
with no shared transaction:

```java
OrderEntity order = persistence.createPendingOrder(...);   // commits (REQUIRES_NEW)
// ← process dies here and the order is stranded at PENDING forever
eventPublisher.publish(ORDERS_EVENTS, ORDER_CREATED, orderId, payload);  // no transaction at all
```

The order is created, `GET /api/orders/{id}` shows it, `POST /api/orders` already returned 201 to the
caller — and no consumer was ever told it exists. Nothing retries, because from the database's point
of view the work succeeded. That is ADR-006's context verbatim.

`OrderInventoryEventsConsumer.onInventoryReserved` had the same shape one step later:
`appendInventoryReservedTransition(...)` committed, then `PaymentRequested` was published. §3
explains why that second site turned out to be just as unrecoverable as the first, contrary to what
ADR-006's prose assumed.

## 2. The new design

| File | What it does |
|---|---|
| `services/order-service/src/main/resources/db/migration/V4__outbox_events.sql` | The frozen `outbox_events` shape, unchanged from `docs/db-ownership.md` §2 (no columns added — see §4 on retries). |
| `OutboxEventEntity` / `OutboxStatus` / `OutboxEventRepository` | JPA mapping alongside the other order entities; `payload` is `jsonb` mapped to `String` via `@JdbcTypeCode(SqlTypes.JSON)`, matching Scenario Service's `EventRecordEntity`. |
| `OutboxRecorder` | Builds the frozen envelope and inserts the row. `@Transactional(MANDATORY)` — calling it without a transaction is a bug that would recreate the very window this closes, so it fails at the call site (same enforcement `ProcessedEventLedger.recordProcessed` uses). |
| `OrderPersistence` | The two transactions that produce an event now record it: `createPendingOrder` → `OrderCreated`, `appendInventoryReservedTransition` → `PaymentRequested`. Business rows, `processed_events` claim and outbound event are one commit. |
| `OutboxPublisher` | `@Scheduled(fixedDelay = orderfulfillment.outbox.poll-interval-ms)`, default 50 ms. `@EnableScheduling` added to `OrderServiceApplication`. |
| `OutboxDispatcher` | The transactional batch: pending rows oldest-first, one blocking send each to `orders.events` keyed by `aggregate_id`, then `PUBLISHED` + `published_at`. Split from the poller so Spring's proxy applies, the same reason `OrderPersistence` is split from `OrderService`. |

Removed: the direct `eventPublisher.publish(...)` call in `OrderService.createOrder` (and its now
unused `EventPublisher` dependency), the one in `OrderInventoryEventsConsumer.onInventoryReserved`
(likewise), and `StatusTransitionResult.totalAmount`, which existed only so the caller could build
the `PaymentRequested` payload after the transaction committed — nothing needs to escape the
transaction any more.

Added to `services/common`: `EventPublisher.buildEnvelope(...)`, a purely additive extraction of the
envelope construction that `publish` already did, so the outbox is not a second place where the
frozen envelope of `docs/events/event-catalog.md` §1 is assembled (§4, judgment call 5).

The envelope — including `eventId`, `occurredAt` and `correlationId` — is stamped at
business-transaction time, not at send time. That is what makes a resend idempotent from the
consumers' point of view, and what lets `PaymentRequested.idempotencyKey` be its own final `eventId`.

## 3. The PaymentRequested question — verified, and ADR-006's rationale corrected

ADR-006 scoped the outbox to Order Service on the argument that other publishes are reactions to
consumed events and so "a redelivery can regenerate" them. **That argument does not hold anywhere in
this codebase, including for Order Service's own second publish site.** Verified by reading the
paths:

1. `OrderInventoryEventsConsumer.onInventoryReserved` early-returns on
   `processedEventLedger.isProcessed(eventKey)` before doing anything else.
2. The ledger row is claimed by `processedEventLedger.recordProcessed(eventKey)` *inside*
   `appendInventoryReservedTransition`'s `REQUIRES_NEW` transaction — ADR-005 requires exactly that,
   so the claim commits together with the status writes.
3. Therefore a crash after that commit but before the old post-commit publish left the ledger row
   present and the event absent. A redelivered `InventoryReserved` hits (1), is logged as a duplicate
   and returns. The order sits at `PAYMENT_PENDING` forever — indistinguishable, in kind, from the
   `OrderCreated` case sitting at `PENDING`.

So `PaymentRequested` was routed through the outbox too, recorded by the same transaction that
claims the ledger row. ADR-006's Decision section now carries an explicit correction block rather
than being quietly contradicted by the code.

The same structure exists in all three out-of-scope services — `InventoryOrderEventsConsumer`,
`InventoryPaymentEventsConsumer`, `PaymentOrderEventsConsumer`,
`FulfillmentPaymentEventsConsumer` all claim the ledger inside the business transaction and publish
after it. Their dual-write window is therefore **not** self-healing either. Not fixed here (Phase 6
is scoped to one service, and the pattern is now demonstrated), but it is stated plainly in
ADR-006, `docs/architecture-diagram.md` §5 and the frontend Architecture page rather than left as the
comfortable-but-false "redelivery regenerates it".

## 4. Tradeoffs, and the judgment calls behind them

Reported rather than silently decided, per this project's convention.

**1. `PaymentRequested` moved to the outbox as well.** §3. Cost: the outbox now covers two event
types, so Order Service has no remaining direct Kafka publish. Benefit: the second stranding window
is genuinely closed, not just the headline one. The alternative — implementing ADR-006 literally —
would have left a documented-nowhere hole one step down the state machine.

**2. Retry policy: retry indefinitely while young, `FAILED` once aged out.** The frozen schema has
no retry-count column and I did not add one. So the retry budget is expressed as age: a failed send
leaves the row `PENDING` and is retried every tick until the row is older than
`orderfulfillment.outbox.fail-after-ms` (default 5 min), at which point it is marked `FAILED`, logged
at ERROR, and skipped. Rationale: ADR-006 says `FAILED` rows "need someone to look at them", so
`FAILED` must be reachable; a broker restart shorter than the window then costs only latency, while a
row nothing can publish surfaces for a human instead of being retried forever. Tradeoff: age is a
cruder signal than an attempt count (a row that failed once at minute 4:59 gets one more chance than
one that failed fifty times), and the window is wall-clock, so a long outage fails out rows that a
counter-based policy might still have retried. Honest and simple beat clever here.

**3. A failed send stops the batch (head-of-line blocking), except for rows that just aged out.**
Skipping ahead would publish later events before earlier ones, and ADR-006 explicitly requires
insertion order to preserve the per-partition ordering ADR-001 relies on. The cost is global, not
per-aggregate: one unpublishable row holds up every other order's events until it ages out to
`FAILED`. Acceptable at demo scale and with a 5-minute cap; a real system would partition the outbox
per aggregate or accept `SKIP LOCKED` and weaker ordering.

**4. Pure polling at 50 ms; no notify-on-commit hook.** ADR-006 offers the hook as an optional
optimization to push latency below "tens of milliseconds". 50 ms is already inside that budget, and
the hook would add a second concurrent path into the dispatcher (an `AFTER_COMMIT` listener racing
the scheduled tick) for no demo-visible gain. Interval, batch size, send timeout and fail-after are
all properties in `application.yml`, so the tradeoff is adjustable without a code change.

**5. `buildEnvelope` added to the shared `EventPublisher` rather than duplicated.** The alternative
was six lines copied into Order Service to avoid touching `services/common`. Copying the frozen
envelope's construction is exactly the drift `docs/planning/execution-plan.md` §5 point 4 warns
about; the addition is purely additive (no signature or behavior change for the three services still
using `publish`), and the whole reactor builds clean.

**6. `jsonb` is not byte-preserving — the dispatcher re-serializes compactly.** Discovered by the
test suite, not by reading: PostgreSQL stores `jsonb` decomposed, so reading the column back yields
`{"eventId": "…"}` where the producer wrote `{"eventId":"…"}`, with keys reordered. Same document,
different text — which broke existing tests that string-match the wire format, and would have made
Order Service's records visibly unlike the other three services'. `OutboxDispatcher#wireForm` runs
the stored JSON through Jackson before sending. Values are untouched; only formatting is normalized.
The schema is frozen as `jsonb`, so this is a consequence of the contract, not a choice against it.

**7. Multi-instance safety via `PESSIMISTIC_WRITE`, not `SKIP LOCKED`.** The pending-rows query takes
`FOR UPDATE`, so a second instance blocks rather than interleaving sends and reordering the topic.
Tradeoff: no publisher parallelism, and the transaction is held across the Kafka sends. Correct for a
pattern whose stated guarantee is ordering.

**8. One existing test's mechanics changed; its assertion did not.**
`OrderServiceIntegrationTest.createOrderPersistsPendingAndPublishesOrderCreated` did a single
`KafkaTestUtils.getRecords` immediately after the HTTP call, which only worked because publication
used to be synchronous with the request. Publication is now asynchronous by design (that is the
latency ADR-006 budgets), so it now waits with `await()` and accumulates records across polls — the
same shape its sibling `PaymentRequested` test has always had. Same event, same key, same assertion.
No other test was touched.

**Unchanged tradeoffs, as ADR-006 predicted them:** publication latency gains a poll interval;
lost-event becomes duplicate-event (a crash between send and mark resends the row, absorbed by
ADR-005's idempotent consumers); still at-least-once, never exactly-once
(`docs/planning/agent-guidance.md` rule 18); one more table and one more background process to
operate.

## 5. Tests

New: `services/order-service/src/test/java/com/orderfulfillment/order/OrderOutboxIntegrationTest.java`
(Testcontainers, real PostgreSQL + real Kafka, via the existing `AbstractIntegrationTest`). It tests
the property that actually matters — atomicity — not just "an event eventually appears":

| Test | Proves |
|---|---|
| `createOrderCommitsTheOutboxRowWithTheOrderItself` | Direct JDBC read immediately after `POST /api/orders` returns finds exactly one `OrderCreated` row carrying the full envelope. No Kafka, no waiting: if the insert were outside the transaction there would be an instant where the order is visible and the row is not. |
| `thePollerPublishesTheOutboxRowToKafkaAndMarksItPublished` | A raw consumer sees the record on `orders.events`, keyed by orderId, with **the same `eventId` the transaction stored** (proving the dispatcher sends what was committed rather than rebuilding it), and the row flips to `PUBLISHED` with `published_at` set. |
| `inventoryReservedCommitsPaymentRequestedWithTheStatusTransition` | §3's case: the instant the order is visible as `PAYMENT_PENDING`, the `PaymentRequested` row is already there, and its `payload.idempotencyKey` equals its own `eventId`. Then it reaches `PUBLISHED`. |
| `anOutboxRowRollsBackWithItsTransaction` | A transaction that records an event and then throws leaves no row — the other half of atomicity. |
| `recordingAnEventOutsideATransactionIsRejected` | `MANDATORY` enforcement: no code path can quietly reopen the dual-write window. |

`mvn -pl services/order-service test` → **25 tests, 0 failures, 0 errors**, covering
`OrderServiceIntegrationTest`, `OrderStreamIntegrationTest`, `OrderDuplicateEventIntegrationTest`,
`OrderPoisonMessageIntegrationTest`, `OrderStatusTest`, `CreateOrderRequestValidationTest` and the
new outbox test. `mvn -DskipTests install` across the whole reactor is clean.

## 6. Live verification

Real stack, not just Testcontainers: `docker compose up -d` (Postgres + Kafka) then
`mvn -pl services/order-service spring-boot:run`.

- `POST /api/orders` → `order-20006`, `PENDING`.
- `order_service.outbox_events` → one `OrderCreated` row, `PUBLISHED`, `published_at` set.
- `kafka-console-consumer` on `orders.events` → the `OrderCreated` envelope, key `order-20006`,
  compact JSON identical in shape to what the other services produce.
- Hand-produced `InventoryReserved` onto `inventory.events` → order moved to `PAYMENT_PENDING`, a
  second outbox row (`PaymentRequested`) appeared and reached `PUBLISHED`, and the record showed up
  on `orders.events`.
- Stack shut down afterwards: `docker compose down` (no `-v`, volumes kept), `spring-boot:run`
  killed. The verification order (`order-20006`) and its two outbox rows remain in the local dev
  database, like any other manually created demo order.

## 7. Documentation updated

- `docs/adr/ADR-006-...md` — status line now matches ADR-005's convention ("Accepted. Frozen in Phase
  0; implemented in Phase 6, in Order Service only"); a correction block in Decision replaces the
  "redelivery can regenerate it" reasoning (§3); an "As built" list in Consequences records the four
  points implementation pinned down.
- `docs/architecture-diagram.md` §5 — the dual-write bullet now says three of four services, and
  states what Order Service actually guarantees (durable publication, still at-least-once).
- `frontend/src/pages/ArchitecturePage.tsx` — the "gap explicitly not yet closed" paragraph split
  into what Order Service now does (past tense) and what the other three still do not, including why
  their gap does not self-heal. `npm run build` and `npm run lint` clean.
- No contract file changed. `outbox_events` was reserved in Phase 0 with this exact schema and is
  implemented exactly as frozen, so the `docs/CHANGELOG-contracts.md` broadcast protocol does not
  apply.

## 8. Verification summary against the Phase 6 exit criteria

`implementation-phases.md`: *"A business transaction and its intended event are durably coupled."*

- **Coupled:** both of Order Service's outbound events are inserted inside the same transaction as
  the business change (and, for `PaymentRequested`, the same one as the `processed_events` claim).
  Asserted by four tests, including a rollback case and a `MANDATORY` case.
- **Durable:** the row survives a crash, and the background publisher sends it and marks it
  published — observed on a real broker in both the test suite and the live run.
- **Honestly bounded:** at-least-once, one service only; the remaining exposure is documented in the
  ADR, the architecture doc and the UI rather than papered over.
