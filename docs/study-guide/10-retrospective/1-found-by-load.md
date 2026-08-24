# 10.1 — Found by load

[← Chapter 10](README.md) · [Next: Found by looking →](2-found-by-looking.md)

Four bugs that no amount of reading would have caught, because they only exist when two things happen
at once.

---

## The status race (ADR-009)

**Where:** Order Service. **Found:** Phase 10, under scaling load. **Severity:** silent data
corruption.

Order Service consumes three topics — `inventory.events`, `payments.events`, `fulfillment.events` —
each with its own listener, partitions, and offsets. Kafka guarantees ordering within a partition of
*one* topic and nothing between them.

`OrderPersistence` wrote whatever status its caller handed it, reading the order row only to mutate
it. The frozen transition table existed as prose and documentation comments, and **no code consulted
it.**

Under load:

1. `PaymentAuthorized` (on `payments.events`) and `ShipmentCreated` (on `fulfillment.events`) both
   arrive.
2. `ShipmentCreated` is processed first, writing `FULFILLED` straight out of `PAYMENT_PENDING`.
3. The late `PaymentAuthorized` overwrites the terminal `FULFILLED` back to `FULFILLMENT_PENDING`.

**A completed order silently reverting to in-progress.** No error, no exception, no log line — just a
wrong row.

### Why it survived so long

The contract was **correct and unenforced**. Phase 0 wrote a complete, exhaustive transition table with
consistency checks proving every state reachable and every event accounted for
([Chapter 1](../01-design-contract/3-state-and-api-contracts.md)). Nothing read it.

And functional tests could not find it, because a functional test delivers events **in the order it
sends them**. The bug requires two topics to race, which requires real concurrency.

### The fix

`OrderTransitions.classify` returning `APPLY` / `STALE` / `AHEAD`, a `deferred_transitions` table for
premature arrivals, a drain after every applied transition, and a pessimistic row lock so the
classification cannot itself race. [Chapter 4](../04-reliability/4-out-of-order-transitions.md).

**The lesson:** a frozen contract that no code consults is a description of intent. If a rule matters,
something must enforce it — and the enforcement belongs as close to the write as possible.

---

## The retry budget that was too small

**Where:** Inventory Service. **Found:** Phase 3 concurrency work. **Severity:** stranded orders.

The optimistic-lock retry loop originally allowed **3 attempts with no backoff**:

> under genuinely simultaneous load an order could lose three CAS races in a row and this method would
> then throw `ObjectOptimisticLockingFailureException` out of `InventoryOrderEventsConsumer`,
> publishing **neither** `InventoryReserved` **nor** `InventoryReservationFailed` and leaving that
> order stranded in `PENDING`.

Three is a plausible-sounding number, and it is a *guess*. No backoff meant three contenders retried in
lockstep and collided again immediately.

The replacement — 25 attempts with randomized sub-millisecond backoff — is **derived**:

> Sized to cover far more competing commits than this system can produce (Kafka partitions × listener
> concurrency × instances), so exhaustion means something pathological, not ordinary contention.

Plus the termination argument: every conflict is *proof* another transaction committed, and in this
workload that means stock was consumed — a finite resource. [Chapter 4](../04-reliability/3-inventory-contention.md).

**The lesson:** a retry budget should be derived from the concurrency the system can actually produce.
If you cannot say why the number is what it is, it is a guess — and this one was guessed low.

---

## SSE writes interleaving

**Where:** Order Service. **Found:** Sprint 2, goal 2. **Severity:** corrupted client stream.

`SseEmitter#send` is not safe to call concurrently on one emitter. Order Service has **four** potential
writers per connection: three Kafka listener threads (inventory, payment, fulfillment) plus a scheduled
keep-alive tick.

With no synchronization:

> two threads' calls to the same `SseEmitter`'s underlying writer can interleave mid-write and corrupt
> the SSE byte stream — **observed as a client-side parser reconstructing a garbled or duplicated
> event.**

**No server-side error at all.** The logs look perfect. The bug appears in the browser, which is
exactly where you would blame the client.

Fixed by synchronizing on the emitter instance — per connection, not globally, so one slow client
cannot block delivery to everyone. [Chapter 5](../05-scenarios-and-frontend/2-server-sent-events.md).

**The lesson:** count your writers. Three consumer threads plus a scheduler is four, and *"is this
thread-safe?"* has to be asked of every shared object reachable from more than one of them. Spring's
own Javadoc said so; nothing enforced it.

---

## Cleanup that failed an unrelated request

**Where:** Order Service. **Found:** Sprint 2 bug hunt, under a concurrent SSE fan-out test.
**Severity:** successful order creations returning 500.

The subtlest bug in the project. The chain:

1. `OrderStatusStreamListener` is a `@TransactionalEventListener`, so `broadcast` runs **on the thread
   that just committed the business transaction.**
2. Someone's `POST /api/orders` commits successfully and triggers a broadcast on that thread.
3. One connected SSE client is dead. `send` throws — handled.
4. The `catch` calls `completeWithError`, **which throws again** because the async context is no longer
   usable.
5. That second exception escapes the catch block, propagates up through the broadcast, and fails the
   POST's own request handling.

**A dead SSE connection belonging to an unrelated viewer fails a successful order creation.** The order
was already committed; the client got a 500 for work that succeeded.

Fixed by wrapping cleanup in its own try/catch, plus the `void` `AsyncRequestNotUsableException` handler
in `GlobalExceptionHandler` — because no JSON error body can be written onto a committed
`text/event-stream` response, so the framework's own attempt to handle it failed a *third* time.

**The lesson:** **cleanup code on an error path must not be able to throw.** It runs when things are
already broken, which is precisely when its assumptions do not hold. And know which thread your
callbacks run on — coupling through a shared thread is invisible in both call sites.

---

## The duplicate-SKU oversell

**Where:** Inventory Service. **Found:** during Phase 3/4 work. **Severity:** oversell plus permanent
stock leak.

An order carrying the same SKU on two lines was checked line-by-line against the **unmutated** free
quantity:

> so 2 + 2 against a stock of 2 passed both checks and then applied both increments — reserving 4 of 2.
> It also collapsed to a single reservation row (the row id is derived from the SKU, and
> `inventory_reservations` is `UNIQUE (order_id, sku)`), so the release path would have handed back
> **only half of what was taken, leaking stock permanently.**

Two failures from one omission — an oversell, and a compensation path that gives back less than it
took. The second is worse: it is unrecoverable without manual intervention, and it compounds.

Fixed by summing quantities per SKU **before** checking anything.
[Chapter 2](../02-domain/4-the-four-domains.md).

**The lesson:** check-then-act again, in a third disguise. The check ran against state the write would
then change. And note that `OrderService.validateNoDuplicateSkus` makes this unreachable *through the
API* — but a domain method's correctness should not depend on a validation in a different service.

---

## What these five have in common

**None was reachable by reading the code.** Every one requires two things to happen simultaneously —
two topics, two threads, two CAS attempts, two lines of an order.

**Three are the same bug.** Check-then-act: read state, decide, write, with an interleaving in between.
The status race, the reservation oversell, and the idempotency-ledger hazard from
[Chapter 4](../04-reliability/1-idempotent-consumers.md) are the same shape with three different
correct answers — a pessimistic lock, an atomic insert, and an optimistic version check.

**Two produce no error anywhere.** The status race writes a wrong row; the SSE corruption appears only
in the browser. Both are invisible in the logs you would go looking at.

**Load was the detector, in all five cases.** Not review, not types, not unit tests. Which is the
argument for [Chapter 2](../02-domain/5-testing.md)'s conflict counter: a concurrency test that does not
prove the race occurred has tested nothing.

---

[← Chapter 10](README.md) · [Next: Found by looking →](2-found-by-looking.md)
