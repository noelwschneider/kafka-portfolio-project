# Chapter 0 — Orientation

Before the build starts: what this system is, what the words mean, and how the rest of the guide is
put together.

Nothing here is a build step. This chapter exists so that when Chapter 3 says "Inventory Service
consumes `orders.events` keyed by `orderId`," every one of those nouns already means something to
you.

---

## 1. What this project is

**Order Fulfillment Systems Lab** is a distributed system that processes fake orders through a real
event-driven architecture, and lets you deliberately break it in eight specific ways while watching
what happens.

The thing to be clear about — and it shapes nearly every decision in the guide — is that **the
product is the sandbox, not the store**. There is a catalog of four SKUs and no pretense of being a
storefront. What the project actually demonstrates is what happens when an order's journey is split
across five independently-deployed services that talk only through a message log: duplicate
delivery, out-of-order arrival, consumers that fall over and catch up, records that can never be
processed, two orders racing for the last unit of stock.

`docs/planning/project-overview.md` states this directly: *"The main value of the application is the
distributed systems sandbox, not the fictional store."*

That framing has consequences you will meet repeatedly:

- The frontend is a **console**, not a shop. Its job is to expose what the backend is doing.
- Failure scenarios are **real** — real HTTP requests, real Kafka records, real database writes. No
  scenario is a frontend animation, and no scenario-specific branch is allowed inside business
  logic.
- Guarantees are stated honestly. This system is **at-least-once**, not exactly-once, and no
  document or UI string in the repo is permitted to claim otherwise.

---

## 2. The system in one page

Five Spring Boot services, one Kafka cluster, one PostgreSQL instance carved into five schemas, and
a React console.

```
                        React console (Vite + TanStack Query + EventSource)
                                 │              │
                    REST /api ───┤              ├─── REST /demo, SSE
                                 ▼              ▼
   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
   │ Order        │  │ Inventory    │  │ Payment      │  │ Fulfillment  │  │ Scenario     │
   │ Service      │  │ Service      │  │ Service      │  │ Service      │  │ Service      │
   │ :8081        │  │ :8082        │  │ :8083        │  │ :8084        │  │ :8085        │
   └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
          │                 │                 │                 │                 │
          └─────────────────┴────────┬────────┴─────────────────┘                 │
                                     ▼                                            │
                         Apache Kafka (KRaft, no ZooKeeper)                        │
              orders.events · inventory.events · payments.events · fulfillment.events
                    + one .dlq topic per domain, 3 partitions each  ◄────────────────┘

                         PostgreSQL — one schema per service, no shared tables
              order_service · inventory_service · payment_service · fulfillment_service · scenario_service
```

### Who owns what

| Service | Owns | Publishes to |
|---|---|---|
| **Order Service** | The order and its lifecycle status. The only writer of `orders.status`. | `orders.events` |
| **Inventory Service** | Stock levels and reservations. | `inventory.events` |
| **Payment Service** | Payment attempts. A simulator — no real provider, ever. | `payments.events` |
| **Fulfillment Service** | Shipments. | `fulfillment.events` |
| **Scenario Service** | Demo control plane. Runs scenarios, records timelines, projects every event for the Event Explorer. Owns no business data. | *(nothing of its own — it publishes duplicate and poison records onto the domain topics above)* |

Three properties of that table matter more than the table itself:

1. **No business service calls another business service.** Order, Inventory, Payment and Fulfillment
   communicate *only* through Kafka. The only synchronous service-to-service calls in the system
   come from Scenario Service, and they are demo control (pause a consumer, force a payment
   rejection) rather than workflow.
2. **Each service touches exactly one schema.** No shared tables, no cross-schema joins, no foreign
   keys across a service boundary. See `docs/db-ownership.md`.
3. **A service publishes only to its own domain topic.** This is why `PaymentRequested` — obviously
   a payment-flavored event — lives on `orders.events`: Order Service publishes it, so it goes on
   Order Service's topic. Chapter 1 explains why that rule is worth the initial surprise.

---

## 3. The happy path, narrated

This is the sequence the whole system exists to run. Every chapter after this one is, in some sense,
about making this sequence survive something.

1. A client `POST`s to `/api/orders` on **Order Service**. The order is persisted as `PENDING` and
   the response returns *immediately* — the workflow has not happened yet. `OrderCreated` is
   published to `orders.events`.
2. **Inventory Service** consumes `OrderCreated`, tries to reserve every line against stock, and
   publishes `InventoryReserved` (or `InventoryReservationFailed`) to `inventory.events`.
3. **Order Service** consumes `InventoryReserved`, moves the order to `INVENTORY_RESERVED`, and
   publishes `PaymentRequested` to `orders.events`.
4. **Payment Service** consumes `PaymentRequested`, simulates an authorization, and publishes
   `PaymentAuthorized` (or `PaymentRejected`) to `payments.events`.
5. **Order Service** consumes `PaymentAuthorized` → `PAID` → `FULFILLMENT_PENDING`.
   **Fulfillment Service** independently consumes the same `PaymentAuthorized` and creates a
   shipment, publishing `ShipmentCreated` to `fulfillment.events`.
6. **Order Service** consumes `ShipmentCreated` and moves the order to `FULFILLED`. Terminal.

Notice what that sequence implies. The HTTP request in step 1 finished long before step 6. Nothing
in the system knows the "whole" workflow — each service knows only which events it consumes and
which it publishes. And the order's status is a *projection* of events arriving from three different
topics, which is the source of an entire class of problems Chapter 4 has to solve.

### The two failure paths

- **Out of stock.** Step 2 fails → `InventoryReservationFailed` → order lands in
  `REJECTED_OUT_OF_STOCK`. No payment was attempted, so there is nothing to undo.
- **Payment rejected.** Step 4 declines → `PaymentRejected` → order lands in `PAYMENT_FAILED`, *and*
  Inventory Service also consumes `PaymentRejected` and releases the reservation, publishing
  `InventoryReleased`. That release is a **compensating action**: there are no distributed
  transactions here, so undoing an earlier step means explicitly doing the opposite of it.

---

## 4. The order states

Nine states, owned exclusively by Order Service. Everything else in the system — the UI, the tests,
the scenario success conditions — asserts against this enum.

| State | Terminal | Meaning |
|---|---|---|
| `PENDING` | no | Accepted and persisted. Nothing reserved, nothing charged. |
| `INVENTORY_RESERVED` | no | Every line reserved. Payment not yet requested. |
| `REJECTED_OUT_OF_STOCK` | **yes** | A line could not be reserved. Nothing to compensate. |
| `PAYMENT_PENDING` | no | `PaymentRequested` published; awaiting the simulator. |
| `PAID` | no | Payment authorized. Fulfillment not yet recorded as pending. |
| `PAYMENT_FAILED` | **yes** | Declined. Inventory releases the reservation as compensation. |
| `FULFILLMENT_PENDING` | no | Authorized; a shipment is expected. |
| `FULFILLED` | **yes** | A shipment exists. The happy path's terminal state. |
| `FAILED` | **yes** | The order cannot progress because one of its events could not be processed. |

`FAILED` is worth separating from the other two terminals in your head. `REJECTED_OUT_OF_STOCK` and
`PAYMENT_FAILED` are **business outcomes** — the system worked correctly and the answer was no.
`FAILED` is a **fault** — an event could not be processed at all, retries were exhausted, and the
order is stuck. Chapter 4 builds the machinery that produces it.

Order status is not the same thing as the local statuses each service keeps for its own records
(`inventory_reservations.status`, `payment_attempts.status`, `shipments.status`). Those are private.
Conflating them is an easy mistake and `docs/order-state-machine.md` calls it out explicitly.

---

## 5. The eight scenarios

The scenarios *are* the portfolio. Each is a real, repeatable exercise of one distributed-systems
failure mode, triggered by `POST /demo/scenarios/{name}` on Scenario Service.

| # | Scenario | What it demonstrates | Built in |
|---|---|---|---|
| 1 | Standard Fulfillment | The happy path end to end | Ch. 2, via Kafka in Ch. 3 |
| 2 | Out of Stock | A business rejection with nothing to compensate | Ch. 2 / Ch. 3 |
| 3 | Payment Rejection | A business rejection *with* compensation | Ch. 2 / Ch. 3 |
| 4 | Duplicate Event Delivery | Idempotent consumers — the same record twice, one side effect | Ch. 4 |
| 5 | Consumer Outage and Recovery | Offsets and backlogs — pause a consumer, watch it catch up | Ch. 4 |
| 6 | Poison Message / DLQ | A record that can never be processed, routed out of the way | Ch. 4 |
| 7 | Inventory Contention | Concurrent writers racing for the last unit; reserved never exceeds available | Ch. 4 |
| 8 | High-Volume Batch | Throughput, consumer lag, and what more replicas actually buy you | Ch. 8 |

Plus `POST /demo/reset`, which is not a scenario: it restores seed inventory and clears anything a
run left behind (a paused consumer, a forced payment behavior).

Seed data, referenced throughout: **SKU-001: 10, SKU-002: 5, SKU-003: 100, SKU-004: 2**. SKU-004's
stock of 2 is what makes the contention scenario possible.

---

## 6. Vocabulary

The guide uses a set of terms without re-explaining them — *partition*, *offset*, *envelope*,
*idempotent*, *compensating action*, *dual write*, and a dozen more.

They live in one place: **[`glossary.md`](glossary.md)**. Read it now if any of those are unfamiliar;
otherwise keep it open in a tab. The chapters do not link to it on every use, because a page that
links five words per paragraph is unreadable.

---

## 7. The repo, at a glance

```
kafka-portfolio-project/
├── pom.xml                     Maven parent — six modules
├── docker-compose.yml          The whole stack locally
├── services/
│   ├── common/                 Shared: envelope, publisher, codec, idempotency ledger,
│   │                           error handling, correlation IDs, topic definitions
│   ├── order-service/
│   ├── inventory-service/
│   ├── payment-service/
│   ├── fulfillment-service/
│   └── scenario-service/
├── frontend/                   Vite + React + TypeScript console
├── infrastructure/
│   ├── kubernetes/             Local (kind) manifests + production/ kustomize overlay
│   ├── observability/          Prometheus config, Grafana dashboards
│   └── dev-box/                Scripts for the on-demand development VPS
└── docs/
    ├── adr/                    11 architecture decision records
    ├── openapi/                Five API contracts
    ├── events/event-catalog.md The event contract
    ├── order-state-machine.md  The status contract
    ├── db-ownership.md         The persistence contract
    ├── reliability-pattern.md  The idempotency/retry/DLQ contract
    ├── scenarios.md            The eight scenarios, specified
    └── planning/               Per-sprint design docs
```

Each service is a Spring Boot application with its own `pom.xml`, its own `application.yml`, its own
Flyway migrations under `src/main/resources/db/migration/`, and its own tests. `common` is a plain
library module the other five depend on — it is not a service and does not run.

### Pinned versions

Java 21 · Spring Boot 4.1.0 · Maven multi-module · Flyway · PostgreSQL · `apache/kafka` (KRaft) ·
Testcontainers 1.21.4 · Node 22 · Vite 8 · React 19 · TypeScript 6 · TanStack Query 5 ·
React Router 7.

These are pinned deliberately in `docs/planning/project-overview.md` §0 so that independent work
does not diverge. If you rebuild along with this guide, matching them will save you a lot of
irrelevant debugging.

---

## 8. Prerequisites for the build-along

If you intend to actually rebuild this rather than just read about it:

- **JDK 21** and **Maven 3.9+**
- **Docker** (Desktop or equivalent) — used from Chapter 2 onward for PostgreSQL and Kafka, and by
  Testcontainers for integration tests
- **Node 22** and npm
- An IDE with real Java support — the project was built in IntelliJ Ultimate for backend and VS Code
  for frontend
- From Chapter 7: **`kind`** and **`kubectl`**
- Chapter 9 only: a cloud VPS, a domain, and a willingness to spend a few euros a month. Everything
  before it runs entirely on your machine.

You do not need any of the Chapter 7+ tooling to get through Chapters 1–6, which is where most of
the actual engineering lives.

---

## 9. How the guide is built

Each chapter develops its material through four layers, in roughly this order:

1. **The problem** — stated independently of this project. Why event-driven systems need idempotent
   consumers *at all*, before how these consumers do it.
2. **The technology** — how the mechanism actually works underneath.
3. **The decision** — why this project chose what it chose, and what it rejected. The eleven ADRs in
   `docs/adr/` are the source for most of this.
4. **The code** — real paths, real excerpts, walked through.

Then a **Build it yourself** section: an explicit list of what to create and change to reach that
chapter's state. It is instructions, not a quiz.

Two reference directories sit alongside the chapters. `patterns/` holds implementation patterns
specific to this codebase that recur across it — explained once, linked to thereafter.
`technology/` holds general concept pages, nested by technology, for anything needing more depth than
a chapter's flow can carry. Chapters remain readable straight through; the links are for going
deeper, not for filling gaps.

Two callout styles recur, and they mean different things:

> **Not yet.** The system genuinely does not handle this at this point in the build. Expected, and
> the callout names the chapter that fixes it.

> **We got this wrong.** The real project shipped this broken and fixed it later. The build-along
> builds the corrected version; [Chapter 10](10-retrospective/README.md) tells you what actually happened.

---

## Next

[Chapter 1 — The design contract](01-design-contract/README.md), which is where the build actually starts:
freezing the boundaries, the events, and the states *before* writing a line of implementation, and
why that ordering is itself one of the project's better decisions.
