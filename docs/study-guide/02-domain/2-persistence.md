# 2.2 — Persistence

[← The project skeleton](1-project-skeleton.md) · [Next: The HTTP layer →](3-the-http-layer.md)

Four domains, four sets of tables, one PostgreSQL server, and a rule from
[Chapter 1](../01-design-contract/1-boundaries-and-ownership.md) that no package touches another
package's tables.

---

## The two tools, and where the line between them falls

Your application thinks in objects; the database thinks in rows. This project translates two ways,
deliberately:

- **JPA/Hibernate for domain aggregates** — orders, inventory items, reservations, payments,
  shipments. Things with identity, lifecycle, and behavior.
- **Raw `JdbcClient` for infrastructure tables** — ID sequences here, the idempotency ledger in
  [Chapter 4](../04-reliability/README.md), the outbox poller in [Chapter 6](../06-outbox/README.md). Things with
  no business identity and a fixed, simple access pattern.

Knowing where that line falls is more useful than picking one tool for everything.

> **Primer — [JPA and Hibernate](../technology/jpa/hibernate-basics.md)**
> Entities, the persistence context, dirty checking, lazy loading, the `@Enumerated` ORDINAL footgun,
> N+1 queries, detached entities, and why `open-in-view` should be off.

Two configuration lines set the ground rules for the whole project:

```yaml
spring:
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
```

`open-in-view: false` overrides a Spring Boot default that is widely considered wrong — it otherwise
holds a database connection for the entire request and lets lazy loads fire invisibly during JSON
serialization. `ddl-auto: validate` means Hibernate *checks* that entities match the schema at
startup and changes nothing. Flyway owns the schema; Hibernate verifies its own understanding of it,
so a mismatch is a clear startup failure rather than a runtime error on one code path.

---

## Schema management

Migrations must apply the same way on your laptop, in CI, in Compose, and in production — each
starting from wherever it currently is. Flyway does that with numbered SQL scripts and a history
table.

> **Primer — [Flyway and schema migrations](../technology/flyway/migrations.md)**
> File naming, checksums and immutability, forward-only in practice, versioned vs. repeatable, and
> running several independent histories in one JVM.

This project's arrangement:

```yaml
spring:
  flyway:
    schemas: order_service
```

Scoping Flyway to one schema gives each service its own `flyway_schema_history` and its own
independent version numbering — which is why four services all have a `V1__` and a `V2__` with no
conflict. This is ADR-004's *"five migration histories"* cost, made concrete.

> **In the monolith.** One JVM currently drives four schemas, so a single Flyway configuration cannot
> cover them all. The real project had a `SchemaMigrationRunner` for exactly this and deleted it in
> Phase 3 once *"each service's JVM only ever migrates its own schema."* You need something
> equivalent now — four `Flyway` instances, each with its own `schemas` and `locations`. A dozen
> lines, and it goes away in [Chapter 3](../03-kafka-and-services/README.md).

Schema ownership is also declared on the entity (`@Table(schema = "order_service")`) rather than
defaulted globally. Redundant after the split, and kept deliberately, so a reader looking at
`OrderEntity` learns which schema it belongs to without going to find a config file.

---

## The tables

### Order Service — `V1__orders.sql`

```sql
CREATE TABLE orders (
    id           text PRIMARY KEY,
    customer_id  text NOT NULL,
    status       text NOT NULL,
    total_amount numeric(10,2) NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL
);

CREATE TABLE order_items (
    id         bigserial PRIMARY KEY,
    order_id   text NOT NULL REFERENCES orders(id),
    sku        text NOT NULL,
    quantity   integer NOT NULL CHECK (quantity >= 1),
    unit_price numeric(10,2) NOT NULL,
    UNIQUE (order_id, sku)
);

CREATE TABLE order_status_history (
    id              bigserial PRIMARY KEY,
    order_id        text NOT NULL REFERENCES orders(id),
    status          text NOT NULL,
    source_event_id uuid NULL,
    occurred_at     timestamptz NOT NULL
);

CREATE INDEX idx_order_status_history_order_occurred ON order_status_history (order_id, occurred_at);
```

> **Primer — [PostgreSQL column types](../technology/postgres/column-types.md)**
> Why money is `numeric` and never a float, why time is `timestamptz` and never `timestamp`, how
> `CHECK` and `UNIQUE` divide labour with application validation, and why nullability is information.

Three choices here are specific to this project rather than general practice:

**Foreign keys inside the schema, none across.** `order_items.order_id` references `orders(id)`
because both are Order Service's. Compare `inventory_reservations.order_id` below, which references
nothing — ADR-004's *"`order_id` appears in four schemas and is a foreign key in exactly one."*

**`UNIQUE (order_id, sku)`** is enforced in the database *and* checked in
`OrderService.validateNoDuplicateSkus`. The database constraint is the truth; the application check
exists so the client gets a clear 400 instead of a constraint-violation 500.

**`source_event_id uuid NULL`.** Status-history rows record which event caused the transition — and
the three *internal* transitions from
[Chapter 1](../01-design-contract/3-state-and-api-contracts.md) have no inbound event, so the column
is null for them. **The nullability of that column is the state machine's event/internal distinction,
made physical.**

The index on `(order_id, occurred_at)` matches exactly how the table is read: history for one order,
oldest first.

### Inventory Service — `V1__inventory.sql`

```sql
CREATE TABLE inventory_items (
    sku                text PRIMARY KEY,
    display_name       text NOT NULL,
    available_quantity integer NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity  integer NOT NULL CHECK (reserved_quantity >= 0),
    version            bigint NOT NULL,
    updated_at         timestamptz NOT NULL
);

CREATE TABLE inventory_reservations (
    id         text PRIMARY KEY,
    order_id   text NOT NULL,
    sku        text NOT NULL,
    quantity   integer NOT NULL CHECK (quantity >= 1),
    status     text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (order_id, sku)
);
```

**Two quantity columns, not one.** `available_quantity` is physical stock; `reserved_quantity` is how
much of it is spoken for; free stock is the difference. Modelling a reservation as a decrement of a
single counter would make *releasing* stock after a failed payment indistinguishable from
*restocking*, and would lose the information the UI needs to explain why an item is unavailable.

**`version bigint`** is the optimistic-locking column. [Chapter 4](../04-reliability/README.md) is where it
earns its keep; note that it is present from the very first migration, because retrofitting
concurrency control onto a live table is unpleasant.

**`UNIQUE (order_id, sku)` on reservations** is quietly load-bearing: it makes "reserve this order's
SKU-001" something the database will permit only once — a second line of defence behind the
idempotency machinery of [Chapter 4](../04-reliability/README.md).

### Seed data — `V2__seed_data.sql`

```sql
INSERT INTO inventory_items (sku, display_name, available_quantity, reserved_quantity, version, updated_at) VALUES
    ('SKU-001', 'Mechanical Keyboard', 10, 0, 0, now()),
    ('SKU-002', 'USB-C Dock',           5, 0, 0, now()),
    ('SKU-003', 'Developer Mug',      100, 0, 0, now()),
    ('SKU-004', 'External SSD',         2, 0, 0, now());
```

Four SKUs is the entire catalog — *"keep the domain small"* taken seriously. The quantities are chosen
for the **scenarios**, not for realism: SKU-004's stock of 2 is what makes Scenario 7 (inventory
contention) possible, and SKU-002's 5 is what Scenario 2 (out of stock) exhausts. Seed data as a test
fixture, shipped in a migration.

---

## Entities

```java
@Entity
@Table(name = "orders", schema = "order_service")
public class OrderEntity {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    protected OrderEntity() { }

    public OrderEntity(String id, String customerId, OrderStatus status, BigDecimal totalAmount, Instant createdAt) { … }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    // no setter for id, customerId, totalAmount, createdAt
}
```

Two choices here are decisions, not boilerplate, and both are consequences of how Hibernate works.

**Setters only where mutation is legitimate.** `status` and `updatedAt` have them; `id`,
`customerId`, `totalAmount`, and `createdAt` do not. Because Hibernate's dirty checking persists *any*
setter call inside a transaction, **not having a setter is a real constraint** rather than a stylistic
preference. The entity encodes what may change about an order after creation.

**No `@OneToMany` to items.** `OrderEntity` has no collection of `OrderItemEntity`; they are related
only by `order_id`, and `OrderService` fetches them through a separate repository. That sidesteps lazy
loading, cascade semantics, and orphan removal entirely, at the cost of assembling the aggregate by
hand. For an aggregate this small, a deliberate and reasonable trade.

`InventoryItemEntity` adds two things:

```java
    @Version
    private long version;

    public int freeQuantity() {
        return availableQuantity - reservedQuantity;
    }
```

`@Version` is Hibernate's optimistic-locking marker — [Chapter 4](../04-reliability/README.md). And
`freeQuantity()` is a small but important habit: the derived value lives **on the entity** that owns
both numbers, so there is exactly one definition of "free stock" rather than one per call site.

---

## Repositories

```java
public interface OrderRepository extends JpaRepository<OrderEntity, String> {
    Page<OrderEntity> findByStatusAndCustomerIdOrderByCreatedAtDesc(OrderStatus status, String customerId, Pageable pageable);
    Page<OrderEntity> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);
    Page<OrderEntity> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);
    Page<OrderEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
```

An interface with no implementation anywhere — Spring Data generates one at startup, parsing the
method names into queries.

> **Primer — [Spring Data repositories](../technology/spring/data-repositories.md)**
> Derived query method grammar, `@Query`, pagination (and the hidden count query), `@Lock`, and the
> `@Transactional` self-invocation trap.

> **Not yet.** The real `OrderRepository` also declares `findByIdForUpdate`, a
> `@Lock(LockModeType.PESSIMISTIC_WRITE)` query that serializes status transitions per order. It
> belongs to ADR-009 and is built in [Chapter 4](../04-reliability/README.md); there is nothing to serialize
> against yet, because only one thread writes status.

---

## IDs

Order IDs look like `order-21873` — readable, greppable, and pleasant in a demo, which is why they are
not UUIDs. They come from a PostgreSQL sequence, read with plain SQL because a sequence value is not
an entity and there is nothing to map:

```java
public String nextOrderId()       { return "order-" + nextVal("order_service.order_id_seq"); }
public String nextReservationId() { return "resv-"  + nextVal("inventory_service.reservation_id_seq"); }

private long nextVal(String sequenceName) {
    return jdbcClient.sql("SELECT nextval('" + sequenceName + "')").query(Long.class).single();
}
```

The Javadoc records why:

> A DB sequence (rather than the in-memory `AtomicLong` this replaced) survives restarts and is safe
> across multiple instances of the same service.

An in-memory counter is the obvious first implementation and it is wrong twice over: it restarts at 1
after a restart, and two replicas of the same service both issue `order-1`. The second is the one that
matters, and it does not surface until [Chapter 8](../08-observability-and-scaling/README.md) runs multiple
replicas — hence the `V3__order_id_sequence.sql` migration in every service.

Note also that each `next*Id()` always targets the same schema regardless of caller, because
id-kind-to-schema ownership is fixed by `docs/db-ownership.md`. That is a deliberate contrast with the
idempotency ledger in [Chapter 4](../04-reliability/README.md), whose table name *is* per-service
configuration.

> **Open question — a dangling citation.** `IdGenerator`'s Javadoc ends with *"see
> docs/CHANGELOG-contracts.md for why that mattered."* That file has seven entries and **none
> concerns the ID generator**; the earliest is dated 2026-08-18 and this change appears to predate it.
> The argument above is reconstructed from the comment itself and is sound on its own terms, but the
> original reasoning is not recorded anywhere in the repo.

---

[← The project skeleton](1-project-skeleton.md) · [Next: The HTTP layer →](3-the-http-layer.md)
