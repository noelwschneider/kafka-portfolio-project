# PostgreSQL: picking column types

*Referenced from [Chapter 2.2 — Persistence](../../02-domain/2-persistence.md).*

Four choices that are hard to reverse and easy to get wrong.

---

## Money: `numeric`, never a float

```sql
total_amount numeric(10,2) NOT NULL
```

Binary floating point (`real`, `double precision`, Java's `float`/`double`) cannot represent `0.10`
exactly, because a finite binary fraction cannot express one tenth. Individual values look fine when
printed; sums drift.

```
0.1 + 0.2 = 0.30000000000000004
```

Add enough line items and you are off by a cent. It survives to production because it does not appear
in small test data.

`numeric(precision, scale)` is exact decimal arithmetic — `numeric(10,2)` is up to 10 significant
digits with 2 after the point. Slower than floating point, and utterly irrelevant at any scale where
the difference is not already dwarfed by network time.

In Java this maps to **`BigDecimal`**, for the same reason, with the same rule: never route a monetary
value through a `double` on the way in or out. And construct `BigDecimal` from a string
(`new BigDecimal("129.00")`), never from a double — `new BigDecimal(0.1)` faithfully preserves the
floating-point error you were trying to avoid.

## Time: `timestamptz`, not `timestamp`

```sql
created_at timestamptz NOT NULL
```

Despite the name, `timestamptz` does **not** store a time zone. It stores an absolute point in time
(UTC internally), converting on input and output according to the session's time zone.

`timestamp` (without time zone) stores wall-clock digits with no reference frame — `2026-08-07
20:31:04` in an unspecified zone. Two services in different zones write the same instant as different
values, and nothing can tell them apart afterwards.

Always `timestamptz`. Maps to Java's **`Instant`** — also an absolute point on the timeline, with no
zone of its own. (`LocalDateTime` is the zoneless one; it is the wrong type for a persisted event
time, for exactly the same reason.)

## Constraints: let the database enforce invariants

```sql
quantity integer NOT NULL CHECK (quantity >= 1),
UNIQUE (order_id, sku)
```

A `CHECK` constraint holds regardless of which code path wrote the row — including a future one that
forgets, a data migration, and a manual `psql` fix at 2am.

Application-level validation and database constraints are not redundant; they do different jobs:

- **The database constraint is the truth.** It cannot be bypassed.
- **The application check is the error message.** It produces `A SKU may appear at most once per
  order` and a `400`, instead of a constraint-violation stack trace and a `500`.

Write both. The database one first.

A `UNIQUE` constraint is often quietly load-bearing beyond its stated purpose: `UNIQUE (order_id,
sku)` on a reservations table makes "reserve this order's SKU-001" an operation the database will
only ever permit once — a second line of defence behind whatever idempotency logic sits above it.

## Foreign keys: inside a boundary, not across one

```sql
-- Order Service's own schema: enforced
order_id text NOT NULL REFERENCES orders(id)

-- Another service's schema: the same column, no REFERENCES
order_id text NOT NULL
```

A foreign key asserts an invariant the database will maintain. Across a service boundary you cannot
maintain it — the other service may not have received the event yet, or ever — so the constraint
would be asserting something the architecture does not actually guarantee.

More practically, a cross-schema foreign key makes two services' migrations a coordination problem and
makes it impossible to deploy or restart them independently.

Inside one service's schema, use foreign keys freely. Across boundaries, the column is a correlation
identifier the database cannot enforce, and every consumer must tolerate an ID it has never seen.

## Nullability is information

```sql
source_event_id uuid NULL
```

A nullable column should be nullable for a *reason you can state*. Here: status-history rows record
which event caused a transition, and some transitions are internal — the service moved the record
itself with no inbound event. The nullability of that column **is** the event/internal distinction,
made physical.

`NOT NULL` should be the default, and every exception should have an answer to "what does null mean
here?"
