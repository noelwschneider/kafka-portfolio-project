# 4.1 — Idempotent consumers

[← Chapter 4](README.md) · [Next: Retry and DLQ →](2-retry-and-dlq.md)

The first of [Chapter 3](../03-kafka-and-services/README.md)'s three open gaps: a redelivered record
currently reserves stock twice.

---

## Why this is mandatory, not defensive

Kafka delivers at least once. Not "might, under unusual circumstances" — **will**, as the ordinary
consequence of ordinary events. ADR-005 lists them:

> - a consumer processes a record, writes to the database, and crashes before committing its offset —
>   on restart it reads the same record again;
> - a consumer group rebalances mid-batch and a partition's uncommitted records are redelivered;

Add a producer retry after a timed-out send, and there are three routine paths to the same record
arriving twice.

The asymmetry that makes this urgent: a duplicated *read* is harmless, a duplicated *side effect* is
a second reservation, a second charge, a second shipment. ADR-001 puts it bluntly — *"a consumer that
is not idempotent is a latent double-charge."*

In this system the worst case is inventory **release**, and the code says so:

> Releasing is the operation that most obviously must not be applied twice: a second release would
> hand the same units back to stock again, inventing inventory out of nothing.

> **Pattern — [The idempotent consumer](../patterns/idempotent-consumer.md)**
> The three ways to achieve idempotence, the `processed_events` ledger design, why the insert rather
> than the read is the authority, why the claim must be inside the business transaction and at the
> right level, listener shape, retention, and what this does *not* buy you.
>
> **Read it before continuing** — this section covers only what is specific to this codebase.

---

## The table, per service

```sql
CREATE TABLE processed_events (
    event_id      uuid NOT NULL,
    consumer_name text NOT NULL,
    processed_at  timestamptz NOT NULL,
    PRIMARY KEY (event_id, consumer_name)
);
```

`V2__processed_events.sql` in each of the four business services. Identical DDL, four schemas.

ADR-004 rejected a single shared table on correctness grounds rather than taste:

> the deduplication insert must commit in the same local transaction as the business change it
> guards, which is impossible if the ledger lives in another service's schema.

---

## One shared class, no JPA

`ProcessedEventLedger` lives in `common` and is used by all four services. Its Javadoc explains a
decision worth understanding, because the obvious alternative looks more idiomatic and is worse:

> The table's DDL is frozen and identical in every schema, so the only thing that actually differs
> between services is the schema name. Expressing it as JPA would need a `@MappedSuperclass`, an
> `@Embeddable` id, a subclass entity and a repository interface in each of the four services — four
> copies of the one thing Phase 4's fan-out is most likely to let drift. Two SQL statements against
> `JdbcClient` put the whole implementation here, and leave a fan-out service with exactly two things
> to add: a Flyway migration and one line of configuration.

**Two things to add per service** is the design goal, and it is met:

```yaml
orderfulfillment:
  reliability:
    processed-events-table: order_service.processed_events
```

This is the `JdbcClient`-not-JPA line from [Chapter 2](../02-domain/2-persistence.md) being drawn
exactly where it should be: an infrastructure table with no business identity and two fixed access
patterns is not an aggregate, and mapping it as one costs more than it returns.

One detail worth noticing, since the table name is **interpolated into SQL**:

```java
private static final Pattern QUALIFIED_TABLE_NAME =
        Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?");
```

Validated in the constructor, so a bad value fails at startup rather than becoming a SQL-injection
vector through configuration. A table name cannot be a bind parameter, so if you must interpolate
one, constrain it to an identifier and do it once at construction.

And the reason this works transactionally at all:

> `JdbcClient` joins whatever transaction is already in progress (Spring's `JpaTransactionManager`
> exposes its JDBC connection to `DataSourceUtils`), which is what makes `recordProcessed` commit
> atomically with the surrounding business change rather than in a transaction of its own.

Mixing `JdbcClient` and JPA in one transaction is safe **because they share the connection**. That is
worth knowing before you reach for a second `DataSource`, which would silently break the guarantee.

---

## Where the claim lives, service by service

The pattern page states the rule — the claim goes in the method that owns the business transaction.
Here is where that lands in each service, because the answer is not the same shape each time.

**Inventory Service** → `InventoryReservationExecutor.attemptReserve`, not `InventoryService.reserve`.
The executor exists as a separate class for a reason that would otherwise be invisible:

> Split out from InventoryService so its `@Transactional(REQUIRES_NEW)` methods go through Spring's
> proxy — a self-invoked call (`this.attemptReserve(...)`) from within InventoryService would silently
> skip the proxy and run without a transaction/retry boundary at all.

This is the `@Transactional` self-invocation trap (see the
[Spring Data primer](../technology/spring/data-repositories.md)), and it is worth internalizing:
**`@Transactional` on a method called from another method of the same class does nothing.** No error,
no warning. Extracting the transactional method into its own bean is the standard fix.

The retry loop lives *outside* the claim, which has a consequence
[section 3](3-inventory-contention.md) explores:

> each attempt claims the event and, if it loses the optimistic-lock race, rolls the claim back with
> the rest of its transaction. So a reservation that takes seven attempts still leaves exactly one
> ledger row, written by the attempt that actually committed.

**Payment Service** → the first statement of `PaymentService.authorize`:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public PaymentOutcome authorize(String orderId, BigDecimal amount, UUID idempotencyKey, ProcessedEventKey eventKey) {
    if (eventKey != null && !processedEventLedger.recordProcessed(eventKey)) {
        return PaymentOutcome.duplicate();
    }
    // …
}
```

**Fulfillment Service** → the first statement of `createShipment`, with the business-key backstop
made explicit:

> `shipments.order_id UNIQUE` is a defense-in-depth backstop, not the primary guard [...] the ledger
> key is the Kafka `eventId`, so it stops a duplicate *delivery* of the same event; the unique
> constraint is a business-level invariant ("one shipment per order") that would also catch a
> hypothetical bug that reached this method twice for the same order under two different event ids.
> Belt and suspenders — the ledger is expected to be the one that actually fires.

Two mechanisms answering **two different questions**. The ledger answers "have I seen this event?"
The constraint answers "does this order already have a shipment?" Neither subsumes the other.

**Order Service** → `OrderPersistence.appendStatus` and its siblings, each `REQUIRES_NEW`, each
claiming first.

### The nullable `eventKey`

Every one of these takes `ProcessedEventKey eventKey` and tolerates `null`:

> @param eventKey the event being applied, or `null` for a call that does not originate from a Kafka
> record (administrative and test callers) and so has nothing to deduplicate against

A defensible small compromise. The alternative — two overloads, or a separate non-idempotent path —
duplicates the domain logic, which is worse. The nullable parameter keeps one implementation and makes
the "no event to deduplicate against" case explicit at every call site.

---

## Retention

ADR-005's own accepted costs flagged that the ledger grows without bound. Sprint 2 added the policy.

```java
@Component
@ConditionalOnProperty(prefix = "orderfulfillment.reliability", name = "processed-events-table")
public class ProcessedEventRetentionScheduler {
```

Three things worth taking from it.

**The window is derived, not chosen.** 7 days, because:

> A ledger row can only ever need to answer "was this event already processed?" for as long as Kafka
> could still redeliver that event, so purging rows older than the topic retention is safe by the same
> reasoning ADR-005 already states — never purging a row while its event could still arrive.

Kafka's default `log.retention.hours=168` is 7 days, and `KafkaTopicConfig` sets no explicit
`retention.ms`, so the topics run on that default. **If you change topic retention, change this
too** — the coupling is real and lives only in a comment.

**`@ConditionalOnProperty` keeps it out of Scenario Service**, which has no `processed_events` table
and never sets the property. A bean that would fail at runtime in one of five services is better
excluded by construction than guarded by an `if`.

**It reuses the already-validated table name** from `ProcessedEventLedger.tableName()` rather than
re-reading and re-validating the raw property — one validation, one source of truth.

> **We got this wrong — mildly.** ADR-005 shipped in Phase 4 with unbounded growth as a documented
> accepted cost, and it stayed that way until Sprint 2. Documented-and-deferred is a legitimate
> choice; what makes it legitimate is that the cost was *written down* rather than unnoticed. See
> [Chapter 10](../10-retrospective/README.md).

---

## Demonstrating it

Scenario 4 (Duplicate Event Delivery) republishes a record **verbatim** — same `eventId`, same
payload, same key. That is only possible because of the envelope rule from
[Chapter 1](../01-design-contract/2-the-event-contract.md):

> A duplicate delivery of the same logical event reuses the same `eventId` (that is exactly what
> Scenario 4 republishes).

Success condition: no duplicate side effect. Concretely — one reservation row, one ledger row,
`reserved_quantity` incremented once, and exactly one `order_status_history` entry.

The test to write is the pointed version of that: publish the same envelope twice and assert
`SELECT count(*) FROM processed_events WHERE event_id = ?` returns 1 *and* the business table shows
one row. Asserting only the business outcome would pass if the second delivery never arrived.

---

[← Chapter 4](README.md) · [Next: Retry and DLQ →](2-retry-and-dlq.md)
