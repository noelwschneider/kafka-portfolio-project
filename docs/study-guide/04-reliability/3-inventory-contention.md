# 4.3 — Inventory contention

[← Retry and DLQ](2-retry-and-dlq.md) · [Next: Out-of-order transitions →](4-out-of-order-transitions.md)

The project's highest-scrutiny code, and the shortest interesting file in it. Two orders want the
last two units; exactly one may have them.

---

## What changed since Chapter 2

[Chapter 2](../02-domain/4-the-four-domains.md) added `@Version` to `InventoryItemEntity` and left it
there. A conflict raised `ObjectOptimisticLockingFailureException`, and the honest response was to
return `409 Conflict` to the HTTP caller, who could retry or give up.

**There is no caller any more.** The reservation now runs inside a Kafka listener, and a listener has
nobody to hand a 409 to. It must resolve the conflict itself or fail — and failing means an order
stranded in `PENDING` with neither `InventoryReserved` nor `InventoryReservationFailed` ever
published.

So the same detection mechanism now needs a resolution policy.

---

## The loop

```java
public ReservationResult reserve(String orderId, List<OrderLine> lines, ProcessedEventKey eventKey) {
    ObjectOptimisticLockingFailureException lastConflict = null;
    for (int attempt = 0; attempt < MAX_OPTIMISTIC_LOCK_ATTEMPTS; attempt++) {
        try {
            return executor.attemptReserve(orderId, lines, eventKey);
        } catch (ObjectOptimisticLockingFailureException conflict) {
            lastConflict = conflict;
            optimisticLockConflicts.incrementAndGet();
            log.debug("Optimistic lock conflict reserving for order {} (attempt {}/{}); retrying",
                    orderId, attempt + 1, MAX_OPTIMISTIC_LOCK_ATTEMPTS);
            backOff(attempt);
        }
    }
    log.error("Gave up reserving for order {} after {} optimistic-lock conflicts; …", orderId, MAX_OPTIMISTIC_LOCK_ATTEMPTS);
    throw lastConflict;
}

private void backOff(int attempt) {
    long ceilingNanos = Math.min(BASE_BACKOFF_NANOS << Math.min(attempt, 6), MAX_BACKOFF_NANOS);
    LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(ceilingNanos) + 1);
}
```

Twenty lines. Almost every one of them has an argument behind it.

---

## Why the loop terminates

This is the part worth being able to explain from memory, because "we retry 25 times and hope" is not
an answer and this is not that.

> A version conflict here is never a "maybe it will work next time" retry: losing the compare-and-set
> on `inventory_items.version` is **proof** that a competing transaction committed a change to that
> row. In the reservation workload that competing commit consumed stock, so every conflict this loop
> observes is global forward progress, and the loop is guaranteed to terminate — either this order
> eventually wins the CAS, or it re-reads a row with too little free stock and returns a clean
> `INSUFFICIENT_STOCK` result without writing at all.

Read that again, because the structure of the argument is the transferable part.

An optimistic-lock failure is **not** an ambiguous "something went wrong." It is a *proof of a
specific fact*: another transaction committed to this row between your read and your write. In this
workload, committing to `inventory_items` means consuming stock — a finite, monotonically decreasing
resource.

So each conflict is not wasted work in aggregate; it is **someone else making progress**. The loop
cannot spin forever, because there are only so many units to consume. Every iteration ends one of two
ways: you win the CAS, or you re-read and find too little stock and exit cleanly without writing.

> The bound therefore only has to cover the number of competing commits that can occur while one
> order is trying, which is bounded by the stock being contended for.

That is why the number is 25 rather than 3:

> Attempts, not "retries after the first" — attempt 1 is the initial try. Sized to cover far more
> competing commits than this system can produce (Kafka partitions × listener concurrency ×
> instances), so exhaustion means something pathological, not ordinary contention.

**The bound is derived from the concurrency the system can actually produce**, not guessed. Three
partitions × listener concurrency × replica count is a number you can compute; 25 sits comfortably
above it.

> **We got this wrong.** The original bound was **3 attempts with no backoff**, and it was
> demonstrated to fail:
>
> > under genuinely simultaneous load an order could lose three CAS races in a row and this method
> > would then throw `ObjectOptimisticLockingFailureException` out of `InventoryOrderEventsConsumer`,
> > publishing neither InventoryReserved nor InventoryReservationFailed and leaving that order
> > stranded in PENDING.
>
> A retry budget chosen by feel rather than derived from the workload.
> [Chapter 10](../10-retrospective/README.md).

---

## The backoff

```java
private static final long BASE_BACKOFF_NANOS = 200_000L;   // 0.2 ms
private static final long MAX_BACKOFF_NANOS = 10_000_000L;  // 10 ms

private void backOff(int attempt) {
    long ceilingNanos = Math.min(BASE_BACKOFF_NANOS << Math.min(attempt, 6), MAX_BACKOFF_NANOS);
    LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(ceilingNanos) + 1);
}
```

**Sub-millisecond, capped at 10ms.** Utterly unlike the 0.5s–2s retry budget in
[section 2](2-retry-and-dlq.md), and correctly so: this is in-process contention on one database row,
which clears in microseconds. Waiting half a second would be waiting for nothing.

**Randomized within the ceiling, not a fixed sleep:**

> Backoff is randomized so that contenders that collided once do not re-collide in lockstep.

Two threads that collide, both sleep exactly 0.2ms, and both wake and retry together will collide
again. Jitter breaks the symmetry. Note this is the *opposite* choice from
[section 2](2-retry-and-dlq.md), where jitter was disabled on purpose — because there the retry timing
is part of a demonstration, and here it is a correctness-adjacent concern. Same knob, opposite
setting, both justified.

**`<< Math.min(attempt, 6)`** — exponential growth, with the shift itself clamped before the value is
capped. Belt and braces against a shift overflow if the attempt count ever grew.

**`LockSupport.parkNanos` rather than `Thread.sleep`** — nanosecond granularity, and no
`InterruptedException` to handle.

---

## Where the claim sits relative to the loop

The ledger claim is inside `attemptReserve`, which is inside the loop. That is not incidental:

> each attempt claims the event and, if it loses the optimistic-lock race, rolls the claim back with
> the rest of its transaction. So a reservation that takes seven attempts still leaves exactly one
> ledger row, written by the attempt that actually committed.

**Transactional rollback makes the interaction correct for free.** Had the claim been made one level
up — in `reserve`, outside the loop — the first attempt would claim the event, lose the race, and
every subsequent attempt would find the event already claimed and skip. The order would be silently
dropped.

That is why the pattern's rule is "the claim goes in the method that owns the business transaction,"
stated as a rule rather than a preference.

---

## When the loop does give up

```java
// Phase 4 gave this propagation a defined destination. ObjectOptimisticLockingFailureException
// is a TransientDataAccessException, so the shared error handler classifies it retryable:
// the record is redelivered up to three more times with 0.5s/1s/2s backoff — each redelivery
// being a fresh 25-attempt loop against fresh state, at a moment far enough away that the
// contention has almost certainly cleared — and if it still fails, the record lands on
// inventory.dlq with its failure metadata instead of being logged and skipped past.
throw lastConflict;
```

This is where [section 2](2-retry-and-dlq.md) and this section compose, and the layering is worth
seeing whole:

| Layer | Mechanism | Timescale | On exhaustion |
|---|---|---|---|
| Inner | 25 CAS attempts with 0.2–10ms jittered backoff | microseconds | throw |
| Outer | 3 Kafka redeliveries with 0.5s/1s/2s backoff | seconds | dead-letter |
| Terminal | `orders.dlq` → order marked `FAILED` | — | human |

**Two retry layers at two timescales, for two different kinds of waiting.** The inner one waits out
row contention. The outer one waits out a *situation* — and each redelivery is a fresh 25-attempt loop
against fresh state, at a moment far enough away that whatever was contending has almost certainly
finished.

And the whole thing is safe to redeliver because *"the losing attempt's transaction, ledger row
included, rolled back, so a redelivery re-reads fresh state and writes nothing twice."*

Also note **why it throws rather than returning a failure result:**

> the caller has no contract-legal way to report it — `InventoryReservationFailed.reason` is frozen to
> `INSUFFICIENT_STOCK`/`UNKNOWN_SKU`, neither of which is true here.

The frozen contract has no vocabulary for "I could not tell." Inventing a third reason would change a
contract to accommodate an implementation detail; throwing routes it into the machinery already built
for "something went wrong," which is exactly what happened.

---

## Optimistic or pessimistic?

Worth being able to argue both sides, because this is a standard interview question and the answer is
genuinely situational.

**Pessimistic** (`SELECT … FOR UPDATE`) — lock the row on read, so the second reader waits. No retry
loop, no conflict handling, straightforward to reason about. In exchange, every reader of that row
serializes, including ones that would never have conflicted, and holding locks across a transaction
invites deadlock when several rows are involved in different orders.

**Optimistic** (`@Version`) — no lock. Detect the conflict at write time and retry. Contention-free
paths pay nothing at all; contended paths pay a retry.

Inventory reservation is **usually uncontended** — four SKUs, most orders not competing for the same
one — so optimistic is the right default. Note that this project uses **both**, in different places:
[section 4](4-out-of-order-transitions.md) takes a pessimistic row lock on the order, because there
the operation genuinely is "read current status, decide, write" and per-order contention is expected.

**Different concurrency control for different access patterns, in the same codebase**, each chosen for
a stated reason, is a better answer than a blanket preference.

---

## Proving it works

The trap in testing concurrency is that a passing test may prove nothing:

```java
/**
 * Counts real {@code @Version} conflicts observed against the database. Exposed so
 * {@code InventoryConcurrencyIntegrationTest} can assert the conflict path was genuinely
 * exercised rather than assert an invariant that held only because nothing ever raced.
 */
private final AtomicLong optimisticLockConflicts = new AtomicLong();
```

A test that fires ten concurrent reservations and asserts `reserved ≤ available` passes if the ten
requests happened to serialize. **You learned nothing, and the test will keep passing after you break
the locking.**

Asserting `optimisticLockConflictCount() > 0` alongside the invariant closes that hole. Assert the
dangerous path was *taken*, not only that the outcome was fine.

Three tests cover this: `InventoryServiceOptimisticLockTest` (the mechanism),
`InventoryConcurrencyIntegrationTest` (concurrent HTTP), and
`InventoryKafkaConcurrencyIntegrationTest` (concurrent consumer threads — the path that actually runs
in production).

**Scenario 7 (Inventory Contention)** is the demonstrable version: several orders race for `SKU-004`,
which is seeded at 2 for exactly this purpose. Success condition — *reserved never exceeds available*.

---

[← Retry and DLQ](2-retry-and-dlq.md) · [Next: Out-of-order transitions →](4-out-of-order-transitions.md)
