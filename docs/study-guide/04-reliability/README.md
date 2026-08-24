# Chapter 4 — Reliability

**Build history:** Phase 4 (`4be88ab reliability pattern`), plus ADR-009 after Phase 10 and three
Sprint 2 additions — the `FAILED` transition, retention, and the retry-budget fix.

The chapter where the project stops being a distributed system that works and starts being one that
keeps working. Everything here exists because of one sentence from
[Chapter 1](../01-design-contract/2-the-event-contract.md):

> At-least-once. Consumers must tolerate duplicate delivery.

Four consequences follow, and each gets a section: records arrive twice; records sometimes cannot be
processed at all; concurrent consumers race for the same row; and records from different topics arrive
in the wrong order.

Phase 4's exit criterion is the strictest in the project:

> Each advertised failure scenario is backed by an automated integration test.

Not "works in a demo." Tested.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [Idempotent consumers](1-idempotent-consumers.md) | The `processed_events` ledger, why the insert is the authority, where the claim lives in each service, `Propagation.MANDATORY`, retention |
| 2 | [Retry and the dead-letter queue](2-retry-and-dlq.md) | Retryable vs. non-retryable classification, the retry budget and why those numbers, the recoverer, DLQ failure metadata, the `FAILED` transition |
| 3 | [Inventory contention](3-inventory-contention.md) | The optimistic-lock retry loop, why it provably terminates, sub-millisecond jittered backoff, the two-layer retry composition, proving a race was actually exercised |
| 4 | [Out-of-order transitions](4-out-of-order-transitions.md) | Cross-topic ordering, the `APPLY`/`STALE`/`AHEAD` classifier, deferral and draining, the pessimistic row lock, and why retrying was the wrong fix |
| 5 | [Pausing consumers](5-pausing-consumers.md) | `pause()` vs `stop()`, waiting for a pause to take effect, why the control lives inside each service |

---

## The scenarios this chapter makes possible

Five of the eight, which is why it is the longest chapter in the guide.

| Scenario | Section | Success condition |
|---|---|---|
| 4 — Duplicate Event Delivery | [1](1-idempotent-consumers.md) | No duplicate side effect |
| 5 — Consumer Outage and Recovery | [5](5-pausing-consumers.md) | Backlog processed after resume |
| 6 — Poison Message / DLQ | [2](2-retry-and-dlq.md) | Record lands in the expected DLQ with inspectable metadata |
| 7 — Inventory Contention | [3](3-inventory-contention.md) | Reserved never exceeds available |

Plus the `FAILED` transition ([2](2-retry-and-dlq.md)), which is not a scenario but is what stops a
dead-lettered order from pretending to still be in progress.

---

## Two ideas worth carrying out of this chapter

**Check-then-act is the recurring hazard, and it appears three times.** "Have I processed this event?"
then apply. "Is there enough stock?" then reserve. "What status is this order?" then transition. Each
is a read followed by a decision followed by a write, and each is wrong if another thread can
interleave.

The three fixes are all different, and choosing between them is the actual skill:

| Where | Hazard | Fix | Why that one |
|---|---|---|---|
| Idempotency ledger | Two threads both see "not processed" | `INSERT … ON CONFLICT DO NOTHING` — the write *is* the check | The database already serializes it, for free |
| Inventory reservation | Two orders both see enough stock | Optimistic `@Version` + bounded retry | Conflicts are rare; uncontended paths pay nothing |
| Order status | Two topics both decide from a stale status | Pessimistic `SELECT … FOR UPDATE` | Conflicts are *expected*, and the operation is too expensive to redo |

**Every bound in this chapter is derived, and every derivation is written down.** 3 retries because
retrying blocks a partition. 25 CAS attempts because that exceeds the concurrency the system can
produce. 10 drain passes because the longest legal chain is 6. 7 days of retention because that is
Kafka's own topic retention.

None of them is a round number chosen by feel — and the one that *was* (3 CAS attempts, no backoff)
is the one that broke. That is the lesson worth taking, not the specific numbers.

---

## Build it yourself

**Idempotency** — [section 1](1-idempotent-consumers.md)

1. `V2__processed_events.sql` in each of the four business services: `(event_id uuid, consumer_name
   text, processed_at timestamptz)`, composite primary key.
2. `ProcessedEventKey` record in `common`, with null checks.
3. `ProcessedEventLedger` in `common` — `JdbcClient`, table name from configuration and **validated
   against an identifier pattern in the constructor**, `isProcessed` as a cheap read, and
   `recordProcessed` as `INSERT … ON CONFLICT DO NOTHING` annotated
   `@Transactional(propagation = MANDATORY)`.
4. `orderfulfillment.reliability.processed-events-table` in each service's `application.yml`.
5. A `*Consumers` constant per listener method for `consumer_name` — `"<service>.<listener>"`, stable
   across restarts.
6. Thread the claim into each domain's transactional method, as its **first statement**: Inventory's
   `attemptReserve` (in the separate executor bean, so the proxy applies), `PaymentService.authorize`,
   `FulfillmentService.createShipment`, `OrderPersistence.appendStatus`.
7. In each listener: filter by `eventType` **before** touching the ledger, then `isProcessed` as an
   early-out, then delegate.
8. `ProcessedEventRetentionScheduler` in `common`, `@ConditionalOnProperty` on the table property,
   defaulting to 7 days and running daily.

**Retry and DLQ** — [section 2](2-retry-and-dlq.md)

9. `DeliveryAttemptTracker` (a `RetryListener` counting deliveries per record) and `DlqHeaders`
   constants.
10. `ConsumerErrorHandlerFactory` in `common`: `ExponentialBackOff(500ms, ×2)` capped at 2s with 3
    max attempts and **jitter off**; the four non-retryable exception types; a
    `DeadLetterPublishingRecoverer` resolving to `new TopicPartition(dlqTopic, -1)`; a headers
    function adding the five `x-*` headers from the **root cause**; and an `ERROR` log carrying the
    exception.
11. A nine-line `*KafkaReliabilityConfig` per service naming only its own DLQ topic.
12. `OrderDeadLetterConsumer` on `orders.dlq` in its own consumer group, calling
    `OrderPersistence.markFailed`. **No ledger claim** — the terminal-state guard is the idempotency.

**Contention** — [section 3](3-inventory-contention.md)

13. Wrap `executor.attemptReserve` in a 25-attempt loop catching
    `ObjectOptimisticLockingFailureException`, with randomized backoff from 0.2ms capped at 10ms via
    `LockSupport.parkNanos`. Rethrow the last conflict on exhaustion, with an `ERROR` log.
14. An `AtomicLong` conflict counter, package-visible, so tests can prove the race happened.

**Ordering** — [section 4](4-out-of-order-transitions.md)

15. `OrderTransitions` — `VALID_PREDECESSORS` transcribed from the frozen table with row numbers as
    comments, `PROGRESS` as a derived happy-path ordinal, and `classify` returning
    `APPLY`/`STALE`/`AHEAD` in that check order.
16. `V5__deferred_transitions.sql` plus entity, repository, and a `DeferredTransitionStatus` of
    `PENDING`/`APPLIED`/`ABANDONED`.
17. `findByIdForUpdate` with `@Lock(LockModeType.PESSIMISTIC_WRITE)`, taken first by **every**
    transition.
18. `drainDeferred` after every applied transition — bounded at 10 passes, reusing `classify`,
    resolving rows to `APPLIED` or `ABANDONED`.
19. `DeferredTransitionRetentionScheduler`, purging **resolved rows only**.

**Demo control** — [section 5](5-pausing-consumers.md)

20. `ConsumerControl` in `common` over `KafkaListenerEndpointRegistry`: `list`, `pause`, `resume`,
    each **waiting for the state to take effect** with a 10s bound and reporting the observed state.
21. A `DemoConsumerController` per service under `/demo/consumers`, never under `/api`.

**Tests** — the exit criterion

22. Per service: a duplicate-delivery test asserting **one** ledger row *and* one business row; a
    poison-message test publishing genuinely malformed bytes via the raw `KafkaTemplate` and asserting
    the DLQ record's `x-failure-retryable` is `false` and `x-delivery-attempts` is `1`; a
    consumer-outage test; and a retry/DLQ test for the retryable path.
23. `InventoryConcurrencyIntegrationTest` and `InventoryKafkaConcurrencyIntegrationTest` — assert both
    `reserved ≤ available` **and** that the conflict counter is greater than zero.
24. `OrderOutOfOrderTransitionIntegrationTest` — deliver `ShipmentCreated` before `PaymentAuthorized`
    and assert the order converges to `FULFILLED` without ever regressing.
25. `RetentionSchedulerIntegrationTest` for both purges.

**Done when:** every one of Scenarios 4, 5, 6 and 7 has a passing integration test; a duplicate has no
second side effect; a poison record reaches the DLQ with readable failure metadata and its order goes
`FAILED`; concurrent orders for `SKU-004` never oversell **and the test proves they raced**; and an
order whose `ShipmentCreated` arrives before its `PaymentAuthorized` still ends at `FULFILLED`.

---

## Next

[Section 1 — Idempotent consumers](1-idempotent-consumers.md).
