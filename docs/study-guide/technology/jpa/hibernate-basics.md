# JPA and Hibernate

*Referenced from [Chapter 2.2 — Persistence](../../02-domain/2-persistence.md).*

**JPA** (Jakarta Persistence API) is a specification for mapping Java objects to relational tables.
**Hibernate** is the implementation Spring Boot uses. You write JPA annotations; Hibernate generates
and executes SQL.

---

## Why an ORM at all

Your application thinks in objects. Your database thinks in rows. Something has to translate, and
there are three options:

| Approach | You get | You pay |
|---|---|---|
| Hand-written SQL (JDBC, `JdbcClient`) | Total control; every query is exactly what you wrote | Boilerplate for ordinary CRUD; you maintain the mapping |
| ORM (JPA/Hibernate) | Enormous leverage on ordinary operations | A layer that does things you did not explicitly ask for |
| Both, at different boundaries | Each where it fits | You have to know where the line is |

Most real systems land on the third. A reasonable line: **ORM for domain aggregates** (things with
identity, lifecycle, and behavior), **raw SQL for infrastructure tables** (ledgers, sequences,
queues) that have no business identity and whose access patterns are fixed and simple.

---

## The four concepts that carry most of it

### 1. The entity

A class mapped to a table.

```java
@Entity
@Table(name = "orders", schema = "order_service")
public class OrderEntity {

    @Id
    private String id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    protected OrderEntity() { }   // required by JPA
}
```

- **`@Entity`** — this class is persistent. It must have a no-argument constructor and a mapped ID.
- **`@Table`** — the table (and optionally schema) it maps to. Without it, the class name is used.
- **`@Id`** — the primary key.
- **`@Column`** — needed only when the default naming or nullability is wrong.

**The no-argument constructor** must exist because Hibernate instantiates entities reflectively when
loading rows. Make it `protected` rather than `public`: available to Hibernate (which subclasses
entities to create lazy proxies) but discouraging application code from building half-initialized
objects.

### 2. The persistence context

A first-level cache scoped to a transaction. When you load an entity, Hibernate keeps two things: a
reference to the object, and **a snapshot of the state it was loaded with**.

Two consequences fall straight out:

- Loading the same row twice in one transaction gives you the *same object instance*, not two copies.
- Hibernate can tell what you changed, which leads directly to:

### 3. Dirty checking

At the end of a transaction, Hibernate compares each managed entity against its loaded snapshot and
issues `UPDATE` statements for whatever differs.

**You do not call `save()` to persist a change to a loaded entity.** This is enough:

```java
@Transactional
public void markPaid(String orderId) {
    OrderEntity order = repository.findById(orderId).orElseThrow();
    order.setStatus(OrderStatus.PAID);   // that's it — an UPDATE is issued at commit
}
```

Powerful, and the source of an entire category of accidental writes: any setter called on a managed
entity inside a transaction *is* a database write, whether or not you meant it to be. Two defences
worth adopting:

- **Only write setters for fields that may legitimately change.** A field with no setter cannot be
  accidentally mutated. This is why `OrderEntity` has `setStatus` but no `setTotalAmount`.
- **Use `@Transactional(readOnly = true)` for reads.** It tells Hibernate to skip dirty checking
  entirely, which is both a safety net and a performance win.

### 4. Lazy loading

Associated entities can be fetched on first access rather than up front. Hibernate hands you a proxy;
touching it triggers a query.

If that first touch happens **after the transaction has closed**, the proxy has no session to query
through and throws `LazyInitializationException`. This is the most common JPA error there is, and it
is almost always a symptom of the same underlying mistake: the service layer did not fetch what the
caller was going to need.

---

## The traps

### `@Enumerated` defaults to ORDINAL, and ORDINAL is dangerous

```java
@Enumerated(EnumType.STRING)   // always do this
private OrderStatus status;
```

The default, `EnumType.ORDINAL`, stores the enum constant's **position** as an integer. Insert a new
constant anywhere except the end, and every existing row silently means something different. No
error, no migration failure, no way to detect it after the fact.

`EnumType.STRING` stores `"PENDING"`. It costs a few bytes, is readable in a `psql` session, and
survives reordering. There is no situation in which ORDINAL is worth it.

### `open-in-view` defaults to true, and true is wrong

```yaml
spring:
  jpa:
    open-in-view: false
```

Spring Boot's default keeps the persistence context open for the whole HTTP request, including JSON
serialization. Lazy loads triggered during rendering therefore succeed.

That sounds like a convenience. What it actually does:

- **Holds a database connection for the entire request**, including time spent writing bytes to a
  slow client. Under load this exhausts the connection pool.
- **Scatters queries into the rendering phase**, where nothing can see or batch them — the N+1 query
  problem, generated invisibly.
- **Hides a design error.** A lazy load during serialization means the service layer returned an
  incomplete object.

With it off, that case throws at development time, which is the correct feedback. Turn it off in
every Spring Boot project, on day one.

### N+1 queries

Load 20 orders, then access each one's items: 1 query for the orders, 20 for the items. It scales
with your data and looks fine in a test with three rows.

Fixes, roughly in order of preference: don't map the association at all and fetch explicitly; use a
`JOIN FETCH` query; use an `@EntityGraph`; batch-fetch. The first is often best for small aggregates
and is what this project does — `OrderEntity` has no `@OneToMany` to items, and the service fetches
them through a second repository call.

### Detached entities and `merge`

An entity loaded in one transaction and used after it closes is **detached** — no longer tracked.
Changes to it do nothing. `repository.save(detached)` calls `merge`, which copies its state onto a
freshly-loaded managed copy and returns *that*; the object you passed in remains detached. Assigning
the return value is not optional.

---

## `ddl-auto`: let Flyway own the schema

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
```

| Value | Behavior |
|---|---|
| `none` | Do nothing |
| `validate` | Check entities against the existing schema at startup; fail on mismatch. **Change nothing.** |
| `update` | Add missing tables and columns. Never removes or alters. |
| `create` / `create-drop` | Drop and recreate |

Use **`validate`** with a real migration tool ([Flyway](../flyway/migrations.md)). Hibernate then
verifies its own understanding of the schema and a mismatch becomes a clear startup failure instead
of a runtime error on one code path.

`update` is the tempting one and it drifts silently: it cannot remove a column, cannot alter a type,
cannot backfill data, and cannot express anything an annotation cannot. It is for throwaway
sandboxes.
