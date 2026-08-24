# Phase 3 Fan-Out Report — Inventory Service Concurrency (Scenario 7)

**Date:** 2026-08-18
**Scope:** the Inventory Service workstream of Phase 3's parallel fan-out — proving, against a real
Postgres under real concurrent load, the invariant `docs/scenarios.md` / `docs/planning/frontend-design.md`
state as Scenario 7's success condition: **total reserved inventory never exceeds available inventory**.
**Gap closed:** the one `docs/agent-reports/phase-3-boundary.md` §11 explicitly left open — *"The
invariant … under genuine concurrent load against a real running Inventory Service is not proven by
anything in this report."*

**Headline: the setup was not already correct.** Two genuine oversell/robustness defects were found
and fixed, plus one configuration setting without which the race being tested could not physically
occur inside a single instance. All three were invisible to the existing mocked unit test by
construction.

No file under `docs/planning/`, `docs/openapi/`, `docs/events/`, `docs/adr/`,
`docs/order-state-machine.md`, `docs/db-ownership.md`, `docs/scenarios.md`, or
`docs/architecture-diagram.md` was modified. Contract gaps are flagged in §7, not fixed. All code
changes are inside `services/inventory-service/`; `services/common/` was not touched.

---

## 1. Findings at a glance

| # | Finding | Severity | Status |
|---|---|---|---|
| 1 | Retry budget of 3 with no backoff exhausts under real contention; `ObjectOptimisticLockingFailureException` then escapes the `@KafkaListener`, publishing neither `InventoryReserved` nor `InventoryReservationFailed` and stranding the order in `PENDING` | **High** — silent loss of an order | Fixed (§3) |
| 2 | One order carrying the same SKU on two lines oversells it outright — `reserved_quantity` 4 against `available_quantity` 2 — and leaves only one reservation row, so the release path leaks the difference permanently | **High** — direct violation of the core invariant, no concurrency needed | Fixed (§4) |
| 3 | `@KafkaListener` concurrency was left at Spring Kafka's default of 1, so a single instance serialized all three `orders.events` partitions onto one thread and could never contend with itself | Medium — masks the race Scenario 7 exists to demonstrate | Fixed (§2) |
| 4 | Concurrency tests mutated shared seed SKUs and broke sibling tests depending on execution order | Low — test-suite defect | Fixed (§5.3) |
| 5 | `InventoryReservationFailed.reason` has no value meaning "lost under contention", so retry exhaustion has no contract-legal outcome | — | **Flagged, not fixed** (§7.1) |
| 6 | No database-level guard (`CHECK (reserved_quantity <= available_quantity)`) backstops the invariant | — | **Flagged, not fixed** (§7.2) |

---

## 2. Was the race even reachable? — the listener-concurrency finding

Investigated first, because if the answer were "no", every downstream assertion would be vacuous.

`KafkaTopicConfig` (in `services/common/`) creates every topic with **3 partitions**. Inventory
Service set no `concurrency` anywhere — not in `application.yml`, not on either
`@KafkaListener` annotation. Spring Kafka's default is `concurrency=1`, which means **one**
`KafkaMessageListenerContainer` thread servicing *all* assigned partitions. Two `OrderCreated`
events for the same scarce SKU, even when they land on different partitions, were therefore
processed strictly one after the other inside a single JVM. The optimistic-locking race could not
happen at all in a single-instance deployment — which is exactly how the demo and the tests run.

This is a genuine masking problem rather than a merely theoretical one: the version column's whole
purpose (`docs/db-ownership.md`: *"version bigint NOT NULL — JPA @Version, optimistic locking"*) was
unreachable in the deployment shape the project actually demonstrates.

**Fix** — `services/inventory-service/src/main/resources/application.yml`:

```yaml
    listener:
      concurrency: 3
```

One consumer thread per partition. This is faithful to what the system is standing in for: real
horizontal scaling (several `inventory-service` instances in the same consumer group, which Phase 10's
HPA demo will actually produce) distributes those same 3 partitions across instances and races the
same way. Raising in-instance concurrency reproduces that failure mode locally without needing three
JVMs. Verified live — the running service now logs three separate containers taking one partition
each, where previously one thread held all three:

```
[ntainer#0-0-C-1] inventory-service: partitions assigned: [orders.events-0]
[ntainer#0-1-C-1] inventory-service: partitions assigned: [orders.events-1]
[ntainer#0-2-C-1] inventory-service: partitions assigned: [orders.events-2]
```

Per-order ordering is unaffected: Kafka still guarantees one partition per consumer thread, and
`docs/events/event-catalog.md` keys every record by `orderId`, so all of one order's events remain on
one partition and in order. Only *cross-order* processing became parallel, which the catalog
explicitly says nothing may depend on (*"It provides no cross-order ordering, and the implementation
must not assume any."*).

---

## 3. Finding 1 — retry exhaustion strands orders (the main bug)

### What was wrong

`InventoryService.reserve` retried a version conflict **3 times, with no backoff**, then rethrew:

```java
for (int attempt = 0; attempt < MAX_OPTIMISTIC_LOCK_RETRIES; attempt++) {   // 3
    try { return executor.attemptReserve(orderId, lines); }
    catch (ObjectOptimisticLockingFailureException conflict) { lastConflict = conflict; }
}
throw lastConflict;
```

Under genuinely simultaneous load an order loses three compare-and-swaps in a row easily — with no
backoff, contenders that just collided immediately collide again in lockstep. The first run of the
new test reproduced it on **round 0**, with 20 of 24 concurrent orders getting an uncaught exception
instead of any outcome at all:

```
ObjectOptimisticLockingFailureException: Unexpected row count (expected row count 1 but was 0)
  [update inventory_service.inventory_items set available_quantity=?,display_name=?,
   reserved_quantity=?,updated_at=?,version=? where sku=? and version=?]
  for entity [InventoryItemEntity with id 'SKU-001']
```

The consequence is worse than a failed reservation. That exception propagates out of
`InventoryOrderEventsConsumer.onMessage`, so **neither** `InventoryReserved` **nor**
`InventoryReservationFailed` is published. Order Service — which only ever leaves `PENDING` on one of
those two events (`docs/order-state-machine.md`) — never hears anything. Spring Kafka's
`DefaultErrorHandler` then retries the record in-process on its default `FixedBackOff(0, 9)`
(ten immediate attempts, all of which lose the same way under sustained contention), logs, and seeks
past the record. The order is stranded in `PENDING` forever, having silently vanished from the
workflow. Under the project's stated at-least-once semantics this is a real correctness failure, not
cosmetic.

### Why the retry loop is actually sound, once sized correctly

Worth stating explicitly, because it is what justifies the fix rather than merely papering over the
symptom. Losing the CAS on `inventory_items.version` is not a "maybe next time" failure — it is
*proof that a competing transaction committed*. In the reservation workload that competing commit
consumed stock. So every conflict this loop observes is global forward progress, and the loop
provably terminates: either this order eventually wins the CAS, or it re-reads a row with too little
free stock and returns a clean `INSUFFICIENT_STOCK` **without writing at all**. The bound therefore
only has to exceed the number of competing commits possible while one order is trying — which is
bounded by the contended stock, and in the Kafka path by (partitions × listener concurrency ×
instances).

That is why the answer is "(a) raise the budget and add backoff", not "(b) the test is unrealistic".
The 24-way test *is* more severe than the Kafka path can produce; but 3 was too small even for
modest contention, and the correct bound is one that cannot be exhausted by any contention the system
can generate.

### The fix

`services/inventory-service/src/main/java/com/orderfulfillment/inventory/InventoryService.java`:

- `MAX_OPTIMISTIC_LOCK_ATTEMPTS = 25` (renamed from `..._RETRIES` — it counts attempts including the
  first, which the old name misstated).
- Randomized capped backoff between attempts (`LockSupport.parkNanos`, 0.2 ms base doubling to a
  10 ms ceiling, jittered). Jitter matters more than duration: it stops two contenders that just
  collided from lining up again on the next attempt.
- Exhaustion now logs at ERROR naming the consequence, then rethrows.
- An `AtomicLong` conflict counter, so tests can assert the conflict path was genuinely exercised
  rather than assert an invariant that held only because nothing raced (see §5.1).

Rethrowing on exhaustion is deliberate, not a leftover. There is no contract-legal alternative
(§7.1), and propagation is *safe*: the losing attempt's transaction rolled back, so redelivery
re-reads fresh state and cannot double-write. It is now unreachable in practice, and loud if reached.

### Why the mocked unit test could not have caught this

`InventoryServiceOptimisticLockTest` stubs the executor to throw on cue. Its two cases are
"throw once, then succeed" and "throw forever". **Neither is sensitive to the budget being too
small**: a stub that throws exactly once passes with any budget ≥ 2, and a stub that throws forever
passes with any finite budget at all. The test asserted `times(3)` — it was pinning down the very
number that was wrong, and would have kept passing at 3, at 25, or at 2. It also cannot observe the
real failure mode, because "how many times do real contenders collide before one wins" is a property
of Postgres, Hibernate, and thread scheduling, none of which a mock contains.

This is worth recording for Phase 4/9's testing-philosophy notes: the mocked test is not wrong, it is
*scoped* — it verifies the loop's control flow and nothing about the loop's sizing. Its Javadoc now
says so explicitly and points at the integration test that covers the rest.

---

## 4. Finding 2 — duplicate SKU lines oversell in a single thread

Found while reading `attemptReserve` for the concurrency work, and confirmed with a throwaway probe
against real Postgres before being written up (the probe was then replaced by the two permanent
regression tests in §5.1).

`attemptReserve` checked each order line against `item.freeQuantity()` in a first pass, then applied
every line's increment in a second pass. With the same SKU on two lines, both checks ran against the
*unmutated* quantity. Probe output, against SKU-004 seeded at 2, for one order with lines
`[SKU-004 × 2, SKU-004 × 2]`:

```
PROBE result             = ReservationResult[success=true, reservationId=resv-4001, ...]
PROBE reserved_quantity  = 4
PROBE available_quantity = 2
PROBE reservation rows   = [{id=resv-4001-SKU-004, sku=SKU-004, quantity=2, status=RESERVED}]
```

Four units of a two-unit SKU reserved, and reported as a **success**. The second defect is in that
last line: the row id is derived from the SKU (`<reservationId>-<sku>`), so the two lines collapsed
onto one primary key and only **one** row of quantity 2 was persisted. `release` walks reservation
rows, so a later `PaymentRejected` would have returned 2 of the 4 units taken — leaking 2 units of
stock permanently, invisibly, on every such order.

No concurrency, no lock, no version column involved: this violates the exact invariant this
workstream exists to prove, on a single thread.

**Fix** — sum lines per SKU up front, then check and write from the summed map, so the check and the
write can no longer disagree. This also matches what the frozen schema already assumes: the
`UNIQUE (order_id, sku)` constraint on `inventory_reservations` (`docs/db-ownership.md`) states that
one order has at most one row per SKU, which is only coherent if quantities are summed. As a
side-effect it removes a latent index-alignment bug in the old code (`resolved.get(i)` indexed a list
that skipped unknown SKUs, so the two lists were only aligned because an unknown SKU always short-
circuited first).

Whether Order Service can currently emit such an order was **not** investigated — its code is another
workstream's and out of bounds. Inventory Service defends independently regardless, which is the same
posture `docs/agent-reports/phase-1.md` §3.4 already established for `UNKNOWN_SKU`. Flagged for the
Order Service workstream in §7.3.

---

## 5. The tests

Two new integration test classes, both extending the existing `AbstractIntegrationTest`
(Testcontainers `postgres:16-alpine` + `apache/kafka:4.0.0`), per the pattern
`InventoryServiceIntegrationTest` already uses.

### 5.1 `InventoryConcurrencyIntegrationTest` — forced simultaneity, many rounds

Direct concurrent calls into the real `InventoryService.reserve` path from many threads sharing one
connection pool against one real Postgres. Simultaneity is forced with a `CyclicBarrier` (every
thread blocks until the last arrives, then all are released together) and results are collected via
`ExecutorService.invokeAll` + `Future`. No sleeps anywhere in the harness.

Four tests:

1. **`concurrentOrdersForTheWholeScarceStockNeverOversell`** — Scenario 7 as frozen: **SKU-004,
   stock 2**, each contender requesting the full 2 so at most one can win. 8 contenders × 15 rounds.
   Per round it asserts: no exception escaped; exactly one success; all 7 losers returned
   `INSUFFICIENT_STOCK` with a shortage naming SKU-004; and — read back from the database, not
   inferred from return values — `reserved_quantity == 2`, `available - reserved == 0`, exactly one
   `RESERVED` row, belonging to the winner.
   It then asserts the **conflict counter advanced**, which is what stops the whole test from being
   vacuous: if 15 rounds of 8 simultaneous writers never produced a single real `@Version` conflict,
   the threads were being serialized somewhere and every assertion above would have been proving
   nothing. This is the assertion that would have failed had the concurrency masking of §2 not been
   fixed.
2. **`highContentionSingleUnitOrdersReserveExactlyTheAvailableStock`** — 24 contenders for 10 units
   of SKU-001, 10 rounds. Many winners per round means many conflicting writes pile up on one row,
   which is what actually stresses the retry budget. This is the test that caught Finding 1.
3. **`oneOrderRepeatingTheSameSkuCannotOversellIt`** — regression test for Finding 2.
4. **`oneOrderRepeatingTheSameSkuWithinStockReservesTheSummedQuantityOnce`** — the same order shape
   that *should* succeed, asserting one row carrying the summed quantity so release returns
   everything taken.

Deliberately chosen: **direct calls, not Kafka**, for this class. Forcing 8 threads to enter the
reservation transaction within microseconds of each other 15 times over is exact and cheap through a
barrier; through Kafka it would depend on partition assignment and fetch timing. This is a faithful
proxy because it exercises the identical code path a listener thread takes — the same
`InventoryService.reserve` → `InventoryReservationExecutor.attemptReserve` `REQUIRES_NEW`
transaction, the same Hibernate CAS, the same pool — differing only in what invokes it. What it does
*not* cover (the consumer wiring, and that a real listener thread actually reaches this path
concurrently) is exactly what the second class covers.

### 5.2 `InventoryKafkaConcurrencyIntegrationTest` — the real `@KafkaListener` path

Same invariant, driven end to end: real `OrderCreated` records on `orders.events`, consumed by the
real `InventoryOrderEventsConsumer`, with the real `InventoryReserved` / `InventoryReservationFailed`
records read back off `inventory.events`.

Two mechanisms make the race genuine rather than hopeful:

- **Distinct partitions by construction.** Candidate order ids are filtered by computing Kafka's own
  default partitioning of the record key (`Utils.murmur2` → `toPositive` → `% 3`; `EventPublisher`
  keys every record by `aggregateId` = `orderId`), picking one contender per partition. Three random
  order ids frequently hash onto the same partition, where Kafka's per-partition ordering would
  serialize them and leave no race to observe.
- **Stop, publish, start.** The listener containers are stopped via `KafkaListenerEndpointRegistry`,
  all three `OrderCreated` records are published, and only then are the containers started — so the
  three consumer threads pick their backlog up at once rather than trickling in one record at a time.
  Again no sleeps; container state is awaited on a real predicate.

Asserts exactly one `InventoryReserved` and two `InventoryReservationFailed` (all with
`"reason":"INSUFFICIENT_STOCK"`), plus the same database-level invariant checks.

### 5.3 Finding 4 — test isolation

The concurrency tests deliberately drive SKUs to exhaustion, and the module's tests share one
Testcontainers database and one Spring context. On the first full-suite run this broke a sibling:
`InventoryServiceIntegrationTest.orderCreatedWithAdequateStockReservesAndPublishesInventoryReserved`
timed out, because SKU-001 had been left at `reserved_quantity = 10` and its 1-unit order could no
longer be satisfied — a pass/fail that depended purely on execution order.

Fixed by giving both new classes an `@AfterEach` that restores the SKUs they touch to their
`V2__seed_data.sql` values and deletes the reservation rows they created (the Kafka class also
restarts the listener containers, in case a failure landed between stop and start). Restoring
afterwards rather than only resetting beforehand means the tests are order-independent in both
directions.

### 5.4 Running them

Requires Docker (Testcontainers) and JDK 21 — the machine default of JDK 26 breaks Mockito's
byte-buddy instrumentation, as Phases 1–3 already noted.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
cd /path/to/repo
mvn -DskipTests install                                    # once, so `common` resolves
mvn -pl services/inventory-service test                    # 15 tests
# or just this workstream's:
mvn -pl services/inventory-service test -Dtest='InventoryConcurrencyIntegrationTest,InventoryKafkaConcurrencyIntegrationTest'
```

Result — **15 tests, 0 failures**, and green on four consecutive full-suite runs (three before the
Finding 2 work at 13 tests, one after at 15), specifically to confirm the isolation fix holds rather
than passing by ordering luck:

```
Tests run: 1,  ... InventoryKafkaConcurrencyIntegrationTest
Tests run: 2,  ... InventoryServiceOptimisticLockTest
Tests run: 4,  ... InventoryConcurrencyIntegrationTest
Tests run: 5,  ... InventoryReservationExecutorTest
Tests run: 3,  ... InventoryServiceIntegrationTest
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

One test-only configuration addition: `src/test/resources/application-test.yml` raises the Hikari
pool to 30. The concurrency tests fire more simultaneous threads than the default pool of 10, and
without this the barrier would be defeated by threads queueing for a connection instead of racing for
the row. Production configuration is untouched.

---

## 6. Manual live-stack verification — Scenario 7, for real

Not Testcontainers: the actual four services via `mvn spring-boot:run`, against real
`docker compose` infrastructure, with a volume reset first (`docker compose down -v`) so results
could not be contaminated by earlier manual runs — the false-signal trap
`docs/agent-reports/phase-3-boundary.md` §10.3 documents.

```bash
docker compose down -v && docker compose up -d
JAVA_HOME=<jdk21> mvn -DskipTests install
cd services/{order,inventory,payment,fulfillment}-service && JAVA_HOME=<jdk21> mvn spring-boot:run   # ×4
```

All four confirmed up (`Started OrderServiceApplication`, `…InventoryServiceApplication`,
`…PaymentServiceApplication`, `…FulfillmentServiceApplication`).

**Inventory before:**

```json
{"sku":"SKU-004","displayName":"External SSD","availableQuantity":2,"reservedQuantity":0,"version":0,...}
```

**The two competing orders**, Scenario 7's own framing — *Order A requests 2, Order B requests 2*.
Both `curl` processes were blocked on a shared FIFO and released together, so neither started first
by construction:

```bash
mkfifo /tmp/gate
fire() { read line < /tmp/gate; curl -s -X POST localhost:8081/api/orders \
   -H 'Content-Type: application/json' \
   -d "{\"customerId\":\"scenario7-$1\",\"items\":[{\"sku\":\"SKU-004\",\"quantity\":2}]}"; }
fire a & fire b &
printf 'go\ngo\n' > /tmp/gate       # both released at once
```

Both accepted, **85 microseconds apart**:

```json
Order A -> {"id":"order-20002","status":"PENDING","createdAt":"2026-08-18T19:36:43.178632Z"}
Order B -> {"id":"order-20001","status":"PENDING","createdAt":"2026-08-18T19:36:43.178717Z"}
```

**Outcome — one won, one was cleanly rejected:**

| Order | Customer | Final status | History |
|---|---|---|---|
| `order-20002` | `scenario7-a` | **`FULFILLED`** | PENDING → INVENTORY_RESERVED → PAYMENT_PENDING → PAID → FULFILLMENT_PENDING → FULFILLED |
| `order-20001` | `scenario7-b` | **`REJECTED_OUT_OF_STOCK`** | PENDING → REJECTED_OUT_OF_STOCK |

Every event-caused transition carried a real `sourceEventId` (e.g. the winner's `INVENTORY_RESERVED`
at `aada6efa-3844-4a78-956f-c2eb350fd6c8`, the loser's rejection at
`806950eb-404a-421b-b16a-ab00542eff41`), so both outcomes were driven by real Kafka events, not by
anything synchronous or simulated.

**Inventory after:**

```json
{"sku":"SKU-004","displayName":"External SSD","availableQuantity":2,"reservedQuantity":2,"version":1,...}
```

`reserved_quantity` landed exactly on `available_quantity` and never above it. `version` advanced
`0 → 1` — exactly one successful write to that row, which is the direct evidence that the second
order's reservation never touched it. **Scenario 7's success condition holds against the real running
stack.**

**Honest limit of this particular run.** Whether the two transactions genuinely overlapped in the
database — versus the winner committing in the ~0.4 ms before the loser read the row — is not
established by this transcript, and the conflict log is at DEBUG so nothing appeared either way. Two
orders through Kafka cannot be forced to interleave from outside. The live run proves the *outcome*
and the invariant end to end; the deterministic proof that the CAS conflict path actually fires and
resolves correctly is §5.1's barrier-forced test, which asserts on the conflict counter precisely so
that this is proven somewhere rather than assumed everywhere. Set
`logging.level.com.orderfulfillment: DEBUG` to watch conflicts live.

---

## 7. Contract gaps and judgment calls — flagged, not fixed

Per `.claude/CLAUDE.md`'s coordination protocol and `docs/planning/execution-plan.md` §5.

### 7.1 `InventoryReservationFailed.reason` has no value for "lost under contention"

`docs/events/event-catalog.md` freezes the enum to `INSUFFICIENT_STOCK` and `UNKNOWN_SKU`. When the
retry budget exhausts, neither is true — stock may well exist; this order simply kept losing the CAS.
Consequently there is **no contract-legal event Inventory Service can publish**, which is why §3's
fix propagates instead. That is defensible now (the path is unreachable at any contention this system
can produce, and redelivery is safe because the losing transaction rolled back), but it means the
*documented* behaviour on exhaustion is "the record is redelivered by the consumer error handler,
and if that also exhausts, the order is stranded in `PENDING`".

**Recommendation for Phase 4** (whose scope is exactly retry/backoff/DLQ, per
`docs/planning/implementation-phases.md`), for whoever owns the catalog: either add a third `reason`
value (e.g. `CONTENTION_TIMEOUT`) so the order can be failed cleanly and visibly, **or** route the
exhausted record to `inventory.dlq` — which `event-catalog.md` §2 already reserves — and give Order
Service a defined behaviour for an order whose reservation never resolves. Deliberately not decided
here: it changes a frozen contract and affects Order Service's state machine, so it belongs to a
sequential contract step, not a fan-out workstream. Forcing it into `INSUFFICIENT_STOCK` would have
been a lie about why the order failed, and inventing a new enum value unilaterally would have broken
the freeze.

### 7.2 No database-level backstop for the invariant

`docs/db-ownership.md` gives `inventory_items` `CHECK (available_quantity >= 0)` and
`CHECK (reserved_quantity >= 0)` but nothing relating the two. Finding 2 wrote
`reserved_quantity = 4` against `available_quantity = 2` and **the database accepted it** — the
project's headline invariant was enforced only in application code, so any bug in that code
(exactly the bug that existed) silently corrupts stock.

**Recommendation:** add `CHECK (reserved_quantity <= available_quantity)` to `inventory_items`. It is
cheap, it makes the invariant true by construction rather than by inspection, and it converts any
future oversell from silent corruption into a loud constraint violation. Not done here because it is
a change to a frozen schema contract and would need a `V3__` migration — flagged per the protocol.
Worth noting it is a strictly stronger guarantee than any test can give, and this workstream's whole
subject is the invariant it would enforce.

### 7.3 Whether Order Service can emit duplicate SKU lines is unverified

Finding 2 is fixed defensively inside Inventory Service, but I did not check whether
`CreateOrderRequest` validation permits `[{SKU-004, 2}, {SKU-004, 2}]` in the first place, because
Order Service belongs to a different fan-out workstream. **For the Order Service workstream:** confirm
whether duplicate SKU lines are rejected or merged at order creation; if neither, `orders.order_items`
and `PaymentRequested.amount` may have their own version of this problem, since pricing multiplies
quantity by unit price per line.

### 7.4 `execution-plan.md` §2's Scenario 7 tier call was correct

Recording this as a data point for future planning rather than as a gap: the tier table's rationale
for putting this one workstream at Opus/xhigh — *"the one place a subtle bug silently oversells
inventory and undermines the project's core reliability claim"* — described what was actually found,
almost exactly. Two independent oversell/loss defects and one masking misconfiguration were live in
code that had passing tests and had been reviewed by three prior phases. Nothing about the code
*looked* wrong; both defects needed real transactions to surface.

### 7.5 Judgment calls made

1. **Both a direct-call and a Kafka-driven concurrency test**, rather than choosing one (§5.1, §5.2).
   The brief permitted either; they prove different halves (exact forced simultaneity, repeated many
   times, versus the real consumer wiring) and neither alone closes §11's gap.
2. **Raised in-instance listener concurrency** rather than testing multi-instance contention some
   other way (§2) — it reproduces the production failure mode (multiple instances, same consumer
   group) in the deployment shape the project actually demonstrates, and per-order ordering is
   preserved because records are keyed by `orderId`.
3. **25 attempts with jittered backoff** rather than an unbounded or deadline-based loop. The
   progress argument in §3 means an unbounded loop would also terminate, but a bound is honest about
   pathological cases (e.g. a `release`-heavy workload bumps the version without consuming stock,
   weakening the progress argument) and keeps worst-case latency knowable.
4. **Fixed Finding 2 rather than only reporting it.** It is outside "concurrency" strictly read, but
   it is a live violation of the exact invariant this workstream was told to make "provable and
   true", the fix is small and confined to this service, and reporting the invariant as proven while
   knowingly leaving an oversell path open would have been the dishonest option.
5. **Updated the mocked test's `times(3)` to `times(25)` and rewrote its Javadoc** rather than
   deleting it. It still has value for the loop's control flow; what it needed was an explicit
   statement of what it cannot prove, so a future reader does not mistake it for concurrency
   coverage.
6. **Added a conflict counter to production code** (package-private, one `AtomicLong`, no framework)
   purely so a test can prove the race actually raced. Judged worth its small footprint: without it,
   a regression that re-serializes the listeners would leave every concurrency assertion passing
   vacuously — which is precisely the failure mode this workstream exists to prevent.

---

## 8. Files changed

All inside `services/inventory-service/`.

| File | Change |
|---|---|
| `src/main/java/…/inventory/InventoryService.java` | Retry budget 3 → 25 attempts, randomized capped backoff, ERROR log on exhaustion, conflict counter, Javadoc explaining why conflicts imply forward progress |
| `src/main/java/…/inventory/InventoryReservationExecutor.java` | Sum order lines per SKU before checking or writing (Finding 2); removes latent index-alignment bug |
| `src/main/resources/application.yml` | `spring.kafka.listener.concurrency: 3` |
| `src/test/java/…/inventory/InventoryConcurrencyIntegrationTest.java` | **New** — barrier-forced concurrency, 4 tests |
| `src/test/java/…/inventory/InventoryKafkaConcurrencyIntegrationTest.java` | **New** — same invariant through the real `@KafkaListener` |
| `src/test/java/…/inventory/InventoryServiceOptimisticLockTest.java` | Scope warning in Javadoc; `times(3)` → `times(25)` |
| `src/test/resources/application-test.yml` | Hikari pool 30 (test-only; keeps the barrier from being defeated by connection queueing) |

Not modified: `services/common/`, any other service, the frozen `docs/` contracts, the database
schema, and `InventoryOrderEventsConsumer` / `InventoryPaymentEventsConsumer` (the Kafka wiring
Phases 2–3 built was correct as found).

**Unrelated observation, no action taken:** `git status` shows staged-but-deleted entries under
`services/inventory-service/src/main/java/com/orderfulfillment/inventory_tmp/`, left over from the
boundary step's `git mv` staging. Harmless to the build (the directory does not exist on disk), but
whoever commits Phase 3 should clean the index rather than commit that path.
