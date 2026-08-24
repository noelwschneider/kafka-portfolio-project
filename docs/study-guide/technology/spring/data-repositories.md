# Spring Data repositories

*Referenced from [Chapter 2.2 — Persistence](../../02-domain/2-persistence.md).*

---

## An interface with no implementation

```java
public interface OrderRepository extends JpaRepository<OrderEntity, String> {
    Page<OrderEntity> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);
}
```

There is no `OrderRepositoryImpl` anywhere. Spring Data generates a proxy at startup that implements
every method.

`JpaRepository<EntityType, IdType>` supplies the standard set for free: `save`, `saveAll`,
`findById`, `findAll`, `delete`, `deleteById`, `count`, `existsById`, plus pagination and sorting
variants.

## Derived query methods

Spring Data parses the **method name** and generates the query.

```
findByStatusAndCustomerIdOrderByCreatedAtDesc(OrderStatus status, String customerId, Pageable p)
└─┬─┘└──┬───┘ └───┬────┘ └────┬─────┘└──┬───┘
  │     │         │           │         └─ direction
  │     │         │           └─ sort property
  │     │         └─ second criterion (parameter 2)
  │     └─ first criterion (parameter 1)
  └─ subject: find / count / exists / delete
```

The vocabulary: `And`, `Or`, `Between`, `LessThan`, `GreaterThan`, `Like`, `StartingWith`, `In`,
`IsNull`, `Not`, `IgnoreCase`, `Top`/`First` (`findTop10By…`), `Distinct`.

**The risk is that a typo is a startup failure, not a compile error** — the method name is parsed
against your entity's properties, so `findByCustmerId` fails when the context loads. Preferable to
failing at runtime, but a reason to keep names short.

**Rule of thumb:** if you cannot comfortably read the method name aloud, write the query explicitly.

## `@Query`, for everything else

```java
@Query("select o from OrderEntity o where o.id = :id")
Optional<OrderEntity> findByIdForUpdate(@Param("id") String id);
```

JPQL by default — it queries *entities and their properties*, not tables and columns. Add
`nativeQuery = true` for real SQL when you need something JPQL cannot express.

## Pagination

```java
Page<OrderEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

// caller
PageRequest.of(page, size)
```

A `Pageable` parameter produces `LIMIT`/`OFFSET`. A `Page<T>` return type also issues a **second
count query** so `getTotalElements()` and `getTotalPages()` can be answered. If you do not need the
total, return `Slice<T>` instead and save the count query — on a large table that is a significant
difference.

## Locking

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select o from OrderEntity o where o.id = :id")
Optional<OrderEntity> findByIdForUpdate(@Param("id") String id);
```

`PESSIMISTIC_WRITE` issues `SELECT … FOR UPDATE`, taking a row lock that blocks other transactions
attempting the same until yours commits. Use it when a "read, decide, write" sequence must not
interleave with another writer's.

The optimistic alternative — a `@Version` column, no lock, detect the conflict at write time — is the
other half of the story. Both are covered in [Chapter 4](../../04-reliability/README.md).

## Transactions

Spring Data's built-in methods are transactional individually. **Your own multi-step operations are
not**, unless you say so:

```java
@Transactional
public void reserveAll(String orderId, List<OrderLine> lines) {
    // several repository calls — all one transaction, or none
}

@Transactional(readOnly = true)
public OrderDetail getOrder(String orderId) {
    // no dirty checking, no flush
}
```

Two things worth internalizing about `@Transactional`:

- **It works by proxy.** Calling an annotated method *from another method of the same class* bypasses
  the proxy and the annotation does nothing. This is the single most common Spring transaction bug.
- **`readOnly = true` is not just a hint.** It disables dirty checking, which is both a safety net
  against accidental writes and a real performance improvement on read paths.

## Repository, or plain SQL?

Spring Data is excellent for entity aggregates. It is a poor fit for tables that are not aggregates —
an idempotency ledger, a sequence, a work queue polled in batches — where you want exact control over
the SQL and there is no object to map.

For those, inject `JdbcClient` and write the statement:

```java
jdbcClient.sql("SELECT nextval('order_service.order_id_seq')").query(Long.class).single();
```

Knowing where that line falls is more useful than picking one tool for everything.
