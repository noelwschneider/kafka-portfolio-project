# Database Ownership

**Status:** frozen by Phase 0. Draft source: `docs/planning/backend-design.md`'s PostgreSQL Data
Model section.

Rule: **every table has exactly one owning service.** Only that service's code issues DDL or writes
rows; no other service reads it either — cross-service data needs travel as events
(`docs/events/event-catalog.md`), not as shared SQL. A table that two services appear to need is a
boundary problem to resolve, not a shared table to permit (see §4).

Locally all services may share one PostgreSQL server, with **one schema per service** and one
Flyway migration history per service. That keeps the ownership boundary real (no cross-schema
queries, separate migration timelines) without running four database containers during development.

| Schema | Owner |
|---|---|
| `order_service` | Order Service |
| `inventory_service` | Inventory Service |
| `payment_service` | Payment Service |
| `fulfillment_service` | Fulfillment Service |
| `scenario_service` | Scenario Service |

---

## 1. Ownership table

| Table | Owning service | Purpose |
|---|---|---|
| `orders` | Order Service | Order header and lifecycle status |
| `order_items` | Order Service | Order lines |
| `order_status_history` | Order Service | One row per state transition, for the order timeline |
| `outbox_events` | Order Service | Transactional outbox (Phase 6) |
| `deferred_transitions` | Order Service | Status transitions consumed before their predecessor arrived, awaiting application (ADR-009) |
| `processed_events` (`order_service` schema) | Order Service | Idempotency ledger for its own consumers |
| `inventory_items` | Inventory Service | Per-SKU stock |
| `inventory_reservations` | Inventory Service | Per-order reservations |
| `processed_events` (`inventory_service` schema) | Inventory Service | Idempotency ledger for its own consumers |
| `payment_attempts` | Payment Service | Simulated authorization attempts |
| `processed_events` (`payment_service` schema) | Payment Service | Idempotency ledger for its own consumers |
| `shipments` | Fulfillment Service | Shipment records |
| `processed_events` (`fulfillment_service` schema) | Fulfillment Service | Idempotency ledger for its own consumers |
| `scenario_runs` | Scenario Service | One row per scenario run |
| `scenario_run_timeline` | Scenario Service | Timeline entries for a run |
| `events` | Scenario Service | Cross-service event projection backing the Event Explorer (Phase 5 addition — see §4's "Event Explorer's backing store has no owner yet") |

16 rows, 5 owners, no table owned twice. `processed_events` is deliberately listed once per owning
schema — see §2.

---

## 2. `processed_events` and `outbox_events` are per-service, not shared

`docs/planning/backend-design.md` groups these under a heading called "Shared reliability tables
where needed", which reads as if one table serves all services. Its own next sentence resolves it:
they "should normally belong to each service that uses them rather than becoming one shared
cross-service database table."

**Frozen: each service gets its own copy in its own schema, with identical DDL.** The word "shared"
describes the *pattern*, not the table. A single shared `processed_events` would put four services'
consumers in one write hotspot, break the transactional guarantee that makes the idempotency check
work (the dedup insert must commit in the same local transaction as the business change), and
couple four migration histories together.

```text
processed_events
----------------
event_id        -- envelope eventId
consumer_name   -- logical consumer, e.g. "inventory.order-created"
processed_at
PRIMARY KEY (event_id, consumer_name)
```

The composite key is what makes the same event processable by several *different* consumers while
being processed at most once by each. Within a single service, two distinct consumers therefore
coexist in one table without colliding.

`outbox_events` exists only in Order Service, per `docs/planning/implementation-phases.md`'s Phase 6
("at least the most important publisher, likely Order Service"). Other services keep publishing
after commit; the resulting dual-write window is documented in
`docs/adr/ADR-006-transactional-outbox-for-db-kafka-consistency.md`.

---

## 3. Tables

Column lists come from `docs/planning/backend-design.md`. Types, keys, and constraints are Phase 0
additions where that section gave a bare column name.

### Order Service — `order_service`

```text
orders
------
id              text PK              -- e.g. "order-21873"
customer_id     text NOT NULL
status          text NOT NULL        -- docs/order-state-machine.md §1
total_amount    numeric(10,2) NOT NULL
created_at      timestamptz NOT NULL
updated_at      timestamptz NOT NULL

order_items
-----------
id              bigserial PK
order_id        text NOT NULL REFERENCES orders(id)
sku             text NOT NULL
quantity        integer NOT NULL CHECK (quantity >= 1)
unit_price      numeric(10,2) NOT NULL
UNIQUE (order_id, sku)

order_status_history
--------------------
id              bigserial PK
order_id        text NOT NULL REFERENCES orders(id)
status          text NOT NULL
source_event_id uuid NULL            -- envelope eventId; NULL for internal transitions
occurred_at     timestamptz NOT NULL
INDEX (order_id, occurred_at)

outbox_events                        -- Phase 6
-------------
id              bigserial PK
aggregate_id    text NOT NULL
event_type      text NOT NULL
payload         jsonb NOT NULL       -- full envelope
created_at      timestamptz NOT NULL
published_at    timestamptz NULL
status          text NOT NULL        -- PENDING | PUBLISHED | FAILED
INDEX (status, created_at)

deferred_transitions                 -- ADR-009
--------------------
id              bigserial PK
order_id        text NOT NULL REFERENCES orders(id)
target_status   text NOT NULL        -- docs/order-state-machine.md §1
source_event_id uuid NULL            -- envelope eventId; NULL for internal transitions
status          text NOT NULL        -- PENDING | APPLIED | ABANDONED
deferred_at     timestamptz NOT NULL
resolved_at     timestamptz NULL
INDEX (order_id, status)
```

`deferred_transitions` holds transitions Order Service consumed **before their predecessor
transition had been applied** — the cross-topic ordering race of
`docs/adr/ADR-009-out-of-order-status-transitions.md`. A `PENDING` row means the event is durably
accounted for (its `processed_events` claim committed with this row) but not yet reflected in
`orders.status`; Order Service re-offers these rows after every status change and applies each one
the transition table permits. It is an Order Service internal, not a cross-service contract: no other
service reads or writes it.

`UNIQUE (order_id, sku)` means one line per SKU per order; a request naming the same SKU twice is a
validation error, not two lines.

### Inventory Service — `inventory_service`

```text
inventory_items
---------------
sku                 text PK
display_name        text NOT NULL
available_quantity  integer NOT NULL CHECK (available_quantity >= 0)
reserved_quantity   integer NOT NULL CHECK (reserved_quantity >= 0)
version             bigint NOT NULL      -- JPA @Version, optimistic locking
updated_at          timestamptz NOT NULL
CHECK (reserved_quantity <= available_quantity)

inventory_reservations
----------------------
id              text PK                  -- e.g. "resv-4471"
order_id        text NOT NULL
sku             text NOT NULL
quantity        integer NOT NULL CHECK (quantity >= 1)
status          text NOT NULL            -- RESERVED | RELEASED | FAILED
created_at      timestamptz NOT NULL
updated_at      timestamptz NOT NULL
UNIQUE (order_id, sku)
```

`CHECK (reserved_quantity <= available_quantity)` **states Scenario 7's invariant directly**, rather
than leaving the two per-column checks to imply it — added in Phase 4 because a real application-level
bug wrote `reserved_quantity = 4` against `available_quantity = 2` and the database accepted it
(`docs/agent-reports/phase-3-inventory-concurrency.md` §4, §7.2; migration `V3__reserved_within_available.sql`;
broadcast in `docs/CHANGELOG-contracts.md`).

`available_quantity >= 0` as a database CHECK is deliberate: it is the last line of defence for
Scenario 7's invariant ("total reserved inventory never exceeds available inventory") if the
application-level optimistic locking is ever wrong. `version` is the primary mechanism —
`docs/planning/backend-design.md` requires locking or optimistic concurrency control here, and
`docs/planning/execution-plan.md` §2 flags this as the single most correctness-sensitive piece of
logic in the project.

`inventory_reservations.order_id` is **not** a foreign key: `orders` belongs to another service. The
same is true of every `order_id` outside the `order_service` schema.

### Payment Service — `payment_service`

```text
payment_attempts
----------------
id              text PK                  -- e.g. "pay-9932"
order_id        text NOT NULL
idempotency_key uuid NOT NULL UNIQUE     -- eventId of the triggering PaymentRequested
status          text NOT NULL            -- PENDING | AUTHORIZED | REJECTED
amount          numeric(10,2) NOT NULL
failure_reason  text NULL                -- CARD_DECLINED | INSUFFICIENT_FUNDS
created_at      timestamptz NOT NULL
updated_at      timestamptz NOT NULL
```

`idempotency_key UNIQUE` is a second, independent guard against double authorization: even if the
`processed_events` check were bypassed, a redelivered `PaymentRequested` cannot create a second
attempt row.

### Fulfillment Service — `fulfillment_service`

```text
shipments
---------
id              text PK                  -- e.g. "shp-1180"
order_id        text NOT NULL UNIQUE
status          text NOT NULL            -- CREATED
tracking_number text NOT NULL
created_at      timestamptz NOT NULL
updated_at      timestamptz NOT NULL
```

`status` has exactly one value in v1, since `OrderShipped` was excluded
(`docs/events/event-catalog.md` §4). `order_id UNIQUE` prevents a duplicate `PaymentAuthorized`
delivery from producing two shipments — Scenario 4's assertion at the fulfillment end.

### Scenario Service — `scenario_service`

```text
scenario_runs
-------------
id              text PK                  -- runId, e.g. "run-193"
scenario_name   text NOT NULL            -- docs/scenarios.md
status          text NOT NULL            -- RUNNING | COMPLETED | FAILED
correlation_id  uuid NOT NULL            -- ties the run to its domain events
order_id        text NULL                -- primary order, when the scenario creates one
started_at      timestamptz NOT NULL
completed_at    timestamptz NULL
error_message   text NULL

scenario_run_timeline
---------------------
id              bigserial PK
run_id          text NOT NULL REFERENCES scenario_runs(id)
sequence        integer NOT NULL
label           text NOT NULL            -- e.g. "OrderCreated", "POST /api/orders"
kind            text NOT NULL            -- HTTP | EVENT | STATE_CHANGE
occurred_at     timestamptz NOT NULL
detail          jsonb NULL               -- topic/partition/offset/eventId/... where known
UNIQUE (run_id, sequence)
```

**Phase 0 addition.** `docs/planning/backend-design.md`'s data model defines no scenario tables, but
its own `GET /demo/scenario-runs/{runId}` endpoint and `docs/planning/frontend-design.md`'s Scenario
Run Detail page require stored runs with timelines. These two tables are the minimum that supports
them; reported in `docs/agent-reports/phase-0.md`.

`detail` is `jsonb` and nullable on purpose. `docs/planning/frontend-design.md` says "Do not
fabricate these fields. Display only values actually available from the system" — so partition,
offset, and retry count are stored when the runtime knows them and absent when it doesn't, rather
than defaulted to a plausible-looking zero.

```text
events
------
id              bigserial PK
event_id        uuid NOT NULL            -- envelope eventId; NOT unique alone (see below)
event_type      text NOT NULL
event_version   integer NOT NULL
occurred_at     timestamptz NOT NULL
correlation_id  uuid NOT NULL
aggregate_id    text NOT NULL            -- orderId, per docs/events/event-catalog.md §1
topic           text NOT NULL
partition       integer NOT NULL
offset          bigint NOT NULL
producer        text NOT NULL            -- publishing service, from the frozen topic-ownership table
dead_lettered   boolean NOT NULL DEFAULT false
payload         jsonb NOT NULL
recorded_at     timestamptz NOT NULL DEFAULT now()
UNIQUE (topic, partition, offset)
```

**Phase 5 addition**, made through the coordination protocol — resolves this section's own
"Event Explorer's backing store has no owner yet" note below. Full rationale, the query endpoint this
backs, and the honesty tradeoffs made are in `docs/agent-reports/phase-5-scenario-service.md`; the
one-line summary: Scenario Service already has to consume all four domain topics to build honest
scenario-run timelines, so it is the natural single owner of the general-purpose event projection too,
rather than standing up a second consumer of the same four topics. `UNIQUE (topic, partition, offset)`
— not `event_id` alone — because a DLQ record and the domain record it was dead-lettered from
legitimately share one `event_id` while being two distinct physical Kafka records; the same tuple also
makes the projection idempotent against Kafka's own at-least-once redelivery. Migration:
`services/scenario-service/src/main/resources/db/migration/V2__events.sql`.

---

## 4. Boundary notes and open items

### Where prices come from

`orders.total_amount` and `order_items.unit_price` need a price, and `PaymentRequested.amount`
carries one — but no table in `docs/planning/backend-design.md`'s data model holds a price at all.
`inventory_items` has `display_name` and quantities, no money.

**Frozen: Order Service owns a static seeded SKU → price map** for the four demo SKUs, applied at
order creation. No price column is added to `inventory_items`, and no service makes a synchronous
call to another to price an order.

| SKU | Display name | Unit price |
|---|---|---|
| `SKU-001` | Mechanical Keyboard | 129.00 |
| `SKU-002` | USB-C Dock | 189.00 |
| `SKU-003` | Developer Mug | 14.50 |
| `SKU-004` | External SSD | 249.00 |

Seed stock is unchanged from `docs/planning/backend-design.md`'s Seed Data section: SKU-001: 10,
SKU-002: 5, SKU-003: 100, SKU-004: 2.

This does mean `display_name` lives in Inventory Service while `unit_price` lives in Order Service —
product data split across two owners. That is the honest cost of the decision, and it is acceptable
here only because `docs/planning/project-overview.md` §3 rules out a real catalog: there is no
product service to own product data, and inventing one to hold four rows would add a service purely
to satisfy tidiness. Prices are demo constants, not business data. Reported as a judgment call in
`docs/agent-reports/phase-0.md`.

There is no currency column anywhere; the project uses a single implicit currency.

### The Event Explorer's backing store has no owner yet — resolved in Phase 5

`docs/planning/frontend-design.md`'s Event Explorer needs to query recent events across all services,
filtered by type, order, correlation ID, service, topic, and dead-lettered status. It suggests "a
lightweight event projection/audit store", but names no owner, and nothing in the data model matches:
`order_status_history` covers only order status changes, and `scenario_run_timeline` covers only
events that belong to a scenario run.

~~Deferred, not resolved.~~ **Resolved by Phase 5**, once the decisions this was waiting on were
actually made: Scenario Service consumes all four domain topics (plus their DLQs) to build honest
scenario-run timelines, so it is the projection's owner — see §3's `events` table above and
`docs/agent-reports/phase-5-scenario-service.md`. The query endpoint is `GET /demo/events`
(`docs/openapi/scenario-service.yaml`).

### Cross-schema references

`order_id` appears in `inventory_reservations`, `payment_attempts`, `shipments`, and
`scenario_runs`, and is a foreign key in none of them. It is a correlation identifier across
ownership boundaries, so the database cannot enforce it and each service must tolerate an
`order_id` it has never seen (a real possibility under at-least-once delivery and partial failure).

### No table is owned by two services

Checked against §1: the only table name appearing more than once is `processed_events`, and each
occurrence is a distinct table in a distinct schema with a distinct owner (§2). No physical table has
two owners.
