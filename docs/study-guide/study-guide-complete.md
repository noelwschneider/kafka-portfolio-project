# Order Fulfillment Systems Lab — Study Guide

A build-along explanation of this project: what every significant piece of it does, why it exists,
how the underlying technology actually works, and how you would build the whole thing again from an
empty directory.

This is personal study material. It lives in the repo for tidiness but is gitignored
(`.gitignore:6`) and never gets committed or pushed.

---

## How to read this

Start at [`00-orientation.md`](00-orientation.md), then go in order. The chapters are sequenced as a
build: each one assumes everything before it exists and nothing after it does.

If you only want one topic, the chapter list below and the two reference directories
(`patterns/`, `technology/`) are addressable directly — but chapters lean on earlier chapters, so a
cold jump into Chapter 6 will send you backwards.

---

## Chapters

| # | Chapter | Covers | Build history |
|---|---|---|---|
| 0 | [Orientation](00-orientation.md) | The system on one page, vocabulary, how the guide works, build-along prerequisites | — |
| 1 | [The design contract](01-design-contract/README.md) | Service boundaries, the event envelope, topics and keys, the order state machine, database ownership, the `/api` vs `/demo` split, OpenAPI | Phase 0 |
| 2 | [The domain, built synchronously](02-domain/README.md) | Spring Boot layering, JPA, Flyway, validation, error handling, transactions, the four business domains, the first React UI | Phase 1 |
| 3 | [Kafka, and the split into services](03-kafka-and-services/README.md) | Kafka fundamentals, Spring Kafka wiring, the workflow re-expressed as events, correlation IDs, Maven multi-module extraction, per-service schemas | Phases 2–3 |
| 4 | [Reliability: duplicates, failures, ordering](04-reliability/README.md) | Idempotent consumers, retry and DLQ, retryable vs non-retryable, optimistic locking, cross-topic ordering and deferred transitions, consumer pause/resume, retention | Phase 4 |
| 5 | [The scenario engine and the live frontend](05-scenarios-and-frontend/README.md) | Scenario Service, the eight scenarios, SSE, the React console, event projection, consumer lag | Phase 5 |
| 6 | [The dual-write problem and the transactional outbox](06-outbox/README.md) | Why a DB write and a Kafka publish cannot be one transaction, the outbox table, the dispatcher, what it does and does not buy | Phase 6 + Sprint 2 |
| 7 | [Containers and Kubernetes](07-containers-and-kubernetes/README.md) | Dockerfiles, Compose, the Kubernetes object model, probes, resources, secrets, running on `kind` | Phases 7–8 |
| 8 | [Observability and scaling](08-observability-and-scaling/README.md) | Actuator, Micrometer/Prometheus, structured logging, correlation IDs across services, Grafana, consumer groups vs replicas, partitions as the parallelism ceiling, the HPA | Phases 9–10 |
| 9 | [Production: the public demo](09-production/README.md) | k3s on a Hetzner box, kustomize overlays, GHCR and cross-arch image builds, ingress and allowlisting, tuning for 2 vCPU, sequential rollouts, idle reset | Sprint 2 |
| 10 | [Retrospective: what we got wrong](10-retrospective/README.md) | The bugs and gaps that were actually found late, how they were found, and what each one taught | Sprint 2 – 3 |

Plus **[`glossary.md`](glossary.md)** — every term the chapters use without re-explaining, in one
alphabetical list. Chapters deliberately do not link to it on every use.

---

## Reference directories

Two kinds of content are pulled out of the chapters so they live in exactly one place.

### `patterns/`

Implementation patterns **specific to this codebase** that recur across it. Explained once, in full,
at the point the guide first needs them; every later occurrence links here instead of re-explaining.

- [DTO / entity separation](patterns/dto-entity-separation.md)
- [Correlation ID propagation](patterns/correlation-id-propagation.md)
- [The idempotent consumer](patterns/idempotent-consumer.md)
- [The transactional outbox](patterns/transactional-outbox.md)

### `technology/`

General technology concepts, independent of this project — nested by technology
(`technology/kafka/`, `technology/spring/`, and so on). See
**[`technology/README.md`](technology/README.md)** for the full index. A concept gets its own page
when it needs more depth than a chapter's flow can carry, or when two or more chapters need it.

The dividing line between the two directories: `patterns/` is what you would explain *only* about
this codebase; `technology/` is what you would explain the same way on any project.

### Chapter files

Every chapter is a directory containing a `README.md` — the chapter introduction, its section index,
and its *Build it yourself* checklist — plus numbered section files for the body. Links to a chapter
always point at its `README.md`, so cross-references stay valid however a chapter is later split or
merged.

Chapters stay readable on their own: each explains enough to follow it linearly and links out for the
deeper treatment through a callout box at the point the concept first matters. You should never *have*
to leave a chapter mid-paragraph to understand it.

> **Primer — [Some concept](technology/)**
> One line naming what the page covers, so you can tell at a glance whether you need it.

---

## What each chapter contains

Every topic is developed through four layers:

1. **The problem** — what this solves, independent of this project.
2. **The technology** — how the underlying mechanism actually works, written for genuine
   unfamiliarity rather than jargon-recognition.
3. **The decision** — why this project solved it this way, and what was rejected.
4. **The code** — real file paths, real excerpts, walked through line by line.

Each chapter ends with a **Build it yourself** section: an explicit, ordered list of what to create
and change to reach that chapter's state. It is instructions, not an exercise — nothing is withheld
to test you.

---

## Conventions

**Two kinds of callout, and they mean different things.**

> **Not yet.** Something the system genuinely does not handle at this point in the build. Chapter 2
> has no Kafka; Chapter 3 has no idempotency, so a redelivered record double-reserves stock. This is
> build order working as intended, and the callout names the chapter that closes it.

> **We got this wrong.** Something the real project shipped in a broken state and fixed later. The
> build-along always builds the corrected version — the callout points at
> [Chapter 10](10-retrospective/README.md) for what actually happened.

**The build-along is correct by construction.** Where the real build order and the correct design
disagree, the guide teaches the correct design and records the real order in Chapter 10. Known bugs
are never written into the build steps on purpose.

**Code excerpts are real.** Every path is a real path, and excerpts are quoted from the working tree
rather than paraphrased. Where a file has changed since the phase under discussion, the chapter says
so.

**Cite by filename and section title**, not by section number — the project's own docs restart
numbering per file (`docs/planning/README.md`, "Cross-reference note").

---

## If you are resuming this work

A fresh session picking this up should:

1. Read `docs/planning/sprint-3/study-guide-agent-briefing.md` — the standing brief for this task.
2. Read this file, then skim the **Conventions** section above and one finished chapter
   (`03-kafka-and-services/`) to calibrate depth, voice, and the callout styles.
3. Check the status table below against what is actually on disk — the table is the running index.
4. Continue from the first chapter marked *not started*.

Established decisions, so they do not get re-litigated:

- **Correct by construction.** The build-along always builds the corrected version of anything the
  real project got wrong. Real mistakes are recorded in callouts and told as a story in Chapter 10 —
  never written into the build steps.
- **Two callout styles**, meaning different things: *Not yet* (build order, expected) and *We got this
  wrong* (a real shipped bug).
- **Technology primers live in `technology/`**, project-specific recurring patterns in `patterns/`,
  and chapters link out through a `> **Primer — [...]**` or `> **Pattern — [...]**` callout at the
  point the concept first matters. Chapters stay readable straight through.
- **Every chapter is a directory** with a `README.md` (intro, section index, *Build it yourself*) plus
  numbered section files. Cross-chapter links always point at `NN-name/README.md`.
- **Verify against code, not docs.** Several stale claims have already been found this way; they are
  flagged inline as *Open question* callouts rather than repeated.
- Section length follows logical breaks, not a target. Short sections are fine.

---

## Status

**Rough first pass complete across all ten chapters.** Everything below is written and internally
linked; none of it has been read against the real code by the developer yet, which is the next step.

| Chapter | Status |
|---|---|
| 0 — Orientation | complete (rough) |
| glossary | complete (rough) |
| 1 — The design contract | complete (rough) |
| 2 — The domain | complete (rough) |
| 3 — Kafka and the split | complete (rough) |
| `technology/` | 21 primers written |
| 4 — Reliability | complete (rough) |
| 5 — Scenarios and frontend | complete (rough) |
| 6 — Transactional outbox | complete (rough) |
| 7 — Containers and Kubernetes | complete (rough) |
| 8 — Observability and scaling | complete (rough) |
| 9 — Production | complete (rough) |
| 10 — Retrospective | complete (rough) |

Gaps where the source material genuinely does not support a confident answer are flagged inline as
open questions rather than filled with plausible-sounding text.


<hr style="page-break-after: always;"/>

# Glossary

Terms the guide uses without re-explaining. Alphabetical, so this works as a lookup rather than a
reading.

Some entries are general vocabulary you would meet in any distributed system; some are specific to
how *this* project uses a word. Where the two differ, the entry says so.

The chapters do not link here on every use — a page that links five words per paragraph is
unreadable. Keep this open in a tab.

---

**ADR (Architecture Decision Record).** A short document recording one decision: the context, what
was decided, what was rejected, and what it costs. This project has eleven, in `docs/adr/`. They are
the primary source for the guide's "why this way" material.

**Aggregate / aggregate ID.** The thing an event is *about*. Every event in this system carries an
`aggregateId`, and in every case it is the `orderId` — the order is the only aggregate. This is also
the Kafka record key, which is what keeps one order's events in one partition, in order.

**At-least-once.** The delivery guarantee this system provides: a record may be delivered more than
once, and consumers must tolerate that. After Chapter 6 it may not be silently *lost*. It is
explicitly **not** exactly-once, and no document, UI string, or README in the repo is permitted to
claim otherwise.

**Broker.** A single Kafka server. This project runs one, in KRaft mode.

**Compensating action.** Undoing an earlier step by explicitly doing its opposite, because there is
no distributed transaction to roll back. When payment declines, Inventory Service releases the
reservation it made earlier — that release is a compensating action, not a rollback.

**Consumer group.** A set of consumer instances that share the work of a topic. Each partition is
assigned to exactly one member of the group. This is the mechanism behind horizontal scaling: more
instances in the group means more partitions processed in parallel, up to the partition count.

**Consumer lag.** How many records a consumer group is behind the end of a partition. The visible
symptom of a consumer that has fallen over, and the number the high-volume scenario watches.

**Correlation ID.** An identifier threaded through every request, event, and log line belonging to
one logical operation, so a single scenario run can be traced across five services and their logs.

**DLQ (dead-letter queue/topic).** Where a record goes when it can never be processed, so that it
stops blocking every record behind it in the same partition. This project has one per domain topic:
`orders.dlq`, `inventory.dlq`, `payments.dlq`, `fulfillment.dlq`.

**Dual write.** Writing to your database and publishing to Kafka as two separate operations, with a
crash window between them where one has happened and the other has not. Chapter 6 exists entirely to
close that window.

**Envelope.** The common wrapper every event in this system shares — event ID, type, version,
timestamp, correlation ID, aggregate ID — carrying a type-specific payload inside. Frozen in
Chapter 1.

**Event.** A statement of fact about the past: `InventoryReserved`, not `ReserveInventory`. Events in
this system are named in the past tense throughout, which is not cosmetic — a command can be
rejected, a fact cannot.

**Event-driven / asynchronous.** A service records that something happened and moves on. Whoever
cares reacts later. Nobody waits for anybody.

**Idempotent.** Processing the same event twice has the same effect as processing it once. The
property that makes at-least-once delivery survivable. Chapter 4.

**Internal transition.** In this project's state machine, an order status change that no inbound
event caused — Order Service moved the order itself. There are three, and they are the ones a reader
would otherwise go hunting for an event to explain.

**Key.** A value attached to each Kafka record that determines its partition. Every record in this
system is keyed by `orderId`.

**KRaft.** Kafka's own consensus protocol for cluster metadata, replacing the external ZooKeeper
dependency older Kafka deployments needed. This project uses the `apache/kafka` image in KRaft mode,
so there is no ZooKeeper anywhere.

**Offset.** A consumer's committed bookmark — its position in a partition. Restart a consumer and it
resumes from its offset, which is what makes "pause a consumer and watch it catch up" work at all.

**Optimistic locking.** Detecting a concurrent write rather than preventing one: read a row with a
version number, write it back only if the version is unchanged, and fail if someone got there first.
How two orders racing for the last unit of stock are kept from both winning. Chapter 4.

**Outbox.** A table in a service's own database where an event is written *in the same transaction*
as the business change, then published to Kafka afterwards by a separate poller. The fix for the dual
write. Chapter 6.

**Partition.** A topic is split into partitions, each an ordered, append-only sequence. Kafka
guarantees ordering **within** a partition and nothing across partitions or across topics. This one
sentence causes more of this project's interesting problems than anything else in it.

**Poison message.** A record that can never be processed successfully no matter how many times it is
retried — malformed, or referencing something that does not exist. Retrying it forever blocks the
partition; the answer is to dead-letter it.

**Probe (readiness / liveness).** Kubernetes' two health questions. *Readiness*: should traffic be
sent to this pod right now? *Liveness*: is this pod broken badly enough to restart? Getting the
distinction wrong causes restart loops. Chapter 7.

**Projection.** A read-optimized copy of data derived from events, kept for querying rather than for
correctness. Scenario Service projects every event it sees into its own table so the Event Explorer
has something to page through — nothing in the workflow depends on it.

**Rebalance.** What a consumer group does when membership changes: partitions are reassigned across
the surviving members. A normal event, and one of the ordinary causes of duplicate delivery.

**Replica.** In Kubernetes, one running copy of a service. In Kafka, a copy of a partition on another
broker. The guide uses it in the Kubernetes sense unless it says otherwise — this project runs a
single-broker Kafka, so partition replication is not in play.

**Retryable vs. non-retryable.** Whether a processing failure has any chance of succeeding if tried
again. A database timeout is retryable; a payload that fails to deserialize never will be. This
project classifies failures explicitly and dead-letters non-retryable ones on the first delivery
instead of burning retries. Chapter 4.

**SSE (Server-Sent Events).** A one-way HTTP stream from server to browser over an ordinary
long-lived connection, with automatic reconnection built into the browser. Used here for live order
status and scenario timelines, and chosen over WebSockets because nothing needs to travel the other
way. Chapter 5.

**Terminal state.** An order status from which there is no exit. This project has four:
`REJECTED_OUT_OF_STOCK`, `PAYMENT_FAILED`, `FULFILLED`, and `FAILED`. Nothing may transition out of
one, which is also what makes redelivered events safe to ignore once an order has finished.

**Testcontainers.** A library that starts real Docker containers — a real PostgreSQL, a real Kafka —
for the duration of a test. Every integration test in this project uses it, which is why the tests
prove things about actual Kafka behavior rather than about a mock.

**Topic.** A named, append-only log in Kafka. `orders.events` is a topic.


<hr style="page-break-after: always;"/>

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


<hr style="page-break-after: always;"/>

# Chapter 1 — The design contract

**Build history:** Phase 0. Commits `ef24cb9 add planning documents` and
`22171a9 document system and contracts`.

This chapter produces no running code. That is the point of it.

Phase 0 froze seven things — service boundaries, order states, event names, the event envelope, the
core database tables, the scenario list, and the initial APIs — and wrote them down as documents that
every later phase treats as authoritative. Nothing was implemented until those documents existed.

By the end of this chapter you will have written `docs/` and nothing else, and you will understand
why that is a defensible use of the first stretch of a project rather than procrastination.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [Boundaries and ownership](1-boundaries-and-ownership.md) | Why five services and not one or twelve; why they talk over Kafka and never over HTTP (ADR-001); why each owns its own schema (ADR-004) |
| 2 | [The event contract](2-the-event-contract.md) | What an event envelope is and why you need one; every field and what it is for; topics, keys, and the rule that a service publishes only to its own topic; versioning |
| 3 | [The state and API contracts](3-state-and-api-contracts.md) | The order state machine as a frozen artifact; OpenAPI as a contract rather than documentation; the `/api` vs `/demo` split (ADR-002) |
| 4 | [Sequencing, and what Phase 0 refused to decide](4-sequencing-and-deferrals.md) | Why Kubernetes waits until Phase 8 (ADR-007); what was deliberately left open, and why leaving things open is part of the discipline |

---

## Layer 1 — the problem contracts solve

You are about to build a system in which four independent processes cooperate on a workflow none of
them can see the whole of. Inventory Service will receive a JSON record from Kafka and has to know,
without asking anyone, what fields it contains, which of them it may rely on, what it is allowed to
do in response, and where to put the result.

There are only two ways for it to know that.

**One:** you write Inventory Service and Order Service together, look at one while writing the other,
and let the shape emerge. This works, right up until the moment there are four services and a
frontend, at which point the shape lives in five heads and one of them is wrong. The failure is not
dramatic — it is a field that was optional on Tuesday and required on Thursday, a status string that
one service spells `OUT_OF_STOCK` and another spells `REJECTED_OUT_OF_STOCK`, an event that two
services both believe they own.

**Two:** you decide the shape once, write it down somewhere both services point at, and treat that
document as the thing that is true. When implementation disagrees with the document, the
implementation is wrong — or the document gets changed deliberately, with a note, and everything
that depends on it gets rechecked.

The second is a **contract**. It is not documentation. Documentation describes something that
already exists; a contract constrains something that does not exist yet.

The distinction matters because it changes what happens on disagreement. If `docs/events/event-catalog.md`
is documentation and Inventory Service does something different, the doc is stale and someone should
update it eventually. If it is a contract, Inventory Service has a bug — and if it turns out the
contract was genuinely wrong, you change the contract *first*, deliberately, and then fix everything
downstream of it.

This project states that rule explicitly, in `.claude/CLAUDE.md`:

> If you're working on one service and discover a contract file is wrong or insufficient: Stop —
> don't work around it locally.

### Why this matters more here than in most projects

Two reasons specific to this build.

**It was built by parallel workstreams.** Different sessions, working at different times, on
different services, with no shared memory. A contract is the only thing that makes that possible —
it is the shared memory. Any agent picking up Inventory Service can read the event catalog and know
exactly what `OrderCreated` contains without reading Order Service at all.

**The restructuring was planned in advance.** Phase 1 is a modular monolith. Phase 2 puts Kafka in
the middle of it. Phase 3 splits it into separate deployables. The system is *deliberately* rebuilt
twice. Boundaries that exist only as package structure would be renegotiated at each step; boundaries
that exist as a frozen document survive all three shapes, because a document does not care whether
the two sides of it are in the same JVM.

---

## Layer 3 — what "frozen" actually means here

Every Phase 0 artifact carries a status line like this one, from `docs/order-state-machine.md`:

> **Status:** frozen by Phase 0. This is the authoritative order status enum and transition set for
> every service, test, and UI string.

Frozen does not mean unchangeable. It means changes go through a defined process rather than
happening incidentally:

1. Propose the change **in the contract file**, with a one-line rationale.
2. Update the affected implementations and tests.
3. Leave a note (`docs/CHANGELOG-contracts.md`) so other in-flight work knows to re-check.

The value is not the ceremony. The value is that a change becomes *visible*. A field quietly added
to a DTO is invisible; a field added to the event catalog is a diff that everything downstream can
be checked against.

> **Verify anyway.** The contracts in this repo have drifted from the implementation before, and
> this chapter found a fresh instance of it while being written — see the "Where the contract and
> the code disagree" note in [section 2](2-the-event-contract.md). Frozen is a discipline, not a
> guarantee. When something is load-bearing — an exact number, an exact behavior — check it against
> the code before you build an argument on it.

---

## Build it yourself

Phase 0's deliverable is a `docs/` directory. Create these, in this order — each one feeds the next.

1. **`docs/planning/project-overview.md`** — what you are building and, more importantly, what you
   are *not*. Pin your technology choices in a table (language, build tool, database, migration tool,
   broker image, frontend stack) so that later decisions cannot silently diverge. Write an explicit
   non-goals list; it will save you more time than anything else in the file.

2. **`docs/architecture-diagram.md`** — the service boundaries. Which services exist, what each
   owns, which arrows are allowed. Getting to "no arrow between two business services" is the whole
   exercise. See [section 1](1-boundaries-and-ownership.md).

3. **`docs/events/event-catalog.md`** — the envelope, the topic table, the key rule, and every event
   with its payload. This is the single most valuable document in the set. See
   [section 2](2-the-event-contract.md).

4. **`docs/order-state-machine.md`** — the status enum, which states are terminal, and an exhaustive
   transition table with a cause for every row. Then check two properties explicitly: every state is
   reachable, and every status-changing event has a transition. See
   [section 3](3-state-and-api-contracts.md).

5. **`docs/db-ownership.md`** — every table, its owning service, and its columns. One owner per
   table, no exceptions, no cross-schema foreign keys.

6. **`docs/openapi/*.yaml`** — one spec per service. Business endpoints under `/api`, demo endpoints
   under `/demo`, and never both in the same file for the same service without the split being
   obvious.

7. **`docs/scenarios.md`** — the failure scenarios you intend to demonstrate, each with a trigger, a
   narrative, and a concrete success condition. Write these *before* the reliability code, because
   they are what tells you which reliability code you need.

8. **`docs/adr/`** — one record per decision that had a real alternative. Context, decision,
   alternatives considered, consequences. Write the alternatives honestly, including the ones that
   were nearly right; an ADR whose rejected options are all straw men is worthless in an interview
   and worse than worthless six months later when you have forgotten why.

**Do not write any Kubernetes manifests.** See [section 4](4-sequencing-and-deferrals.md) for why
that instruction is in the plan in so many words.

---

## Next

[Section 1 — Boundaries and ownership](1-boundaries-and-ownership.md).


# 1.1 — Boundaries and ownership

[← Chapter 1](README.md) · [Next: The event contract →](2-the-event-contract.md)

Two questions, answered before any code exists: **where are the lines between services**, and
**what crosses them**.

---

## The problem: a boundary is a promise about change

The usual framing of "how do I split this into services" is about size, and size is the least useful
criterion available. A service is not a unit of code volume. It is a unit of **independent change and
independent failure**.

Draw a line between two pieces of a system and you are asserting three things:

1. They can be deployed separately. Changing one does not require rebuilding the other.
2. They can fail separately. One being down degrades the system rather than stopping it.
3. Neither depends on the other's internals — only on an agreed interface between them.

If any of those three is false, the line is decorative. Two "services" that share a database table
are one service with extra network hops and worse failure modes. This is the well-known distributed
monolith: all the operational cost of microservices, none of the independence.

So the real question is not "how many services" but "along which seams does this system actually
change and fail independently?"

### The seams in an order workflow

An order passes through inventory reservation, payment authorization, and shipment creation. Ask the
three questions of each step:

- **Does it change independently?** Payment logic changes for reasons that have nothing to do with
  stock counting. Yes.
- **Does it fail independently?** A payment provider outage has nothing to do with the inventory
  database. Yes.
- **Does each need the others' internals?** Inventory needs to know an order was placed and what was
  on it. It does not need to know what the order cost, who the customer is, or whether payment
  succeeded — except insofar as a decline means "release what you reserved." No.

Those are real seams. This project draws four services along them, plus a fifth that is not a
business service at all.

---

## The five services

| Service | Owns | Exists because |
|---|---|---|
| **Order Service** | The order, its items, its status, its history | Something must own the order's lifecycle and be the single writer of its status |
| **Inventory Service** | Stock levels and reservations | Stock is a contended, concurrently-mutated resource with its own consistency rules |
| **Payment Service** | Payment attempts | The step most obviously owned by an external system in a real build |
| **Fulfillment Service** | Shipments | The terminal step; the thing that turns an authorized payment into a physical promise |
| **Scenario Service** | Scenario runs, timelines, an event projection | Not a business service — the demo control plane (ADR-002, [section 3](3-state-and-api-contracts.md)) |

Four, not twelve. `docs/planning/project-overview.md`'s scope principles say it directly: *"Favor a
few well-designed services over many superficial microservices"* and *"Do not create ten or more
microservices merely to appear sophisticated."* There is no Product Service, no Customer Service, no
Notification Service — and the absence of a Product Service has a visible cost that the project
records rather than hides (product `display_name` lives in Inventory Service, `unit_price` lives in
Order Service, and `docs/db-ownership.md` explains why under "Where prices come from").

That cost being *documented* rather than *fixed* is itself the lesson. Every boundary has a price.
The discipline is knowing what you paid.

---

## What crosses the boundary: events, not calls

Having drawn the lines, the next decision is how the pieces talk. This is ADR-001, and it is the
single most consequential decision in the project.

### The technology, first

Two shapes are available, and the choice between them is the single most consequential one in the
project.

**Synchronous request/response** is simpler to reason about — the whole workflow is one function,
readable top to bottom — but availability multiplies. Three services at 99% uptime, called in
sequence within one request, give roughly 97%, and a restart downstream *fails* the order rather than
delaying it.

**Asynchronous messaging** buys independence and pays for it in immediacy and observability. Nothing
is knowable at the moment of the request, and the workflow exists nowhere as readable code.

Within asynchronous messaging there is a second split that decides Kafka vs. RabbitMQ: a **queue**
hands a message over and forgets it, while a **log** appends records to an ordered, retained sequence
that consumers read at their own pace, each holding an offset. Retention is what makes "pause a
consumer, watch the backlog build, resume it, watch it catch up" demonstrable at all.

> **Primer — [Messaging: queues vs. logs](../technology/kafka/log-vs-queue.md)**
> Synchronous vs. asynchronous in full, what a queue is good at, what a log buys you, and honest
> guidance on which to actually choose.

### The decision, and the honest version of it

ADR-001 chooses Kafka, and it is unusually candid about why:

> A four-step workflow over four demo SKUs does not need a distributed log on its own merits. What
> needs it is the set of behaviors the project exists to demonstrate.

That sentence is worth internalizing, because it is the correct answer to the obvious interview
question ("isn't this over-engineered for four SKUs?"). Yes. Deliberately. The *product* is the
demonstration of partition-level ordering, consumer groups, offsets, replay, duplicate delivery, and
dead-lettering. The order domain is a vehicle.

The ADR names what it rejected and, importantly, does not pretend the rejected options were bad:

- **Synchronous REST orchestration** — *"genuinely the right answer for a system of this size."*
  Rejected because it demonstrates none of the target behaviors and couples the order's availability
  to every downstream service.
- **RabbitMQ** — adequate, lighter to operate. Rejected because messages are consumed off a queue
  rather than retained in a log, so the outage-and-replay scenario is a much weaker demonstration and
  offsets do not exist in the same form. Explicitly *not* rejected on throughput; throughput is
  irrelevant at this scale.
- **A database-backed job queue** — no new infrastructure, and transactional with the business write,
  which would have removed the dual-write problem [Chapter 6](../06-outbox/README.md) exists to solve.
  Rejected because it teaches nothing about messaging.
- **Event sourcing / CQRS** — an explicit non-goal. Events here are notifications between services,
  **not** the system of record. Each service keeps its own state in its own tables. This distinction
  trips people up constantly: an event-driven system is not an event-sourced one.

### The rule that falls out of it

> **No synchronous service-to-service call anywhere in the order workflow.**

One exception, and it is not workflow: Scenario Service calls the other services over HTTP to arm the
payment simulator and pause consumers. That is a control plane, and [section 3](3-state-and-api-contracts.md)
covers why the exception is defensible.

If you take one structural idea from this project, take this one. Every arrow in the architecture
diagram between two business services is a Kafka topic, and there is no arrow that isn't.

---

## Ownership of data (ADR-004)

The second half of a boundary is persistence. A service that cannot be reasoned about without knowing
another service's tables does not have a boundary.

### The problem

The four domains' data is obviously related. A reservation, a payment attempt, and a shipment all
reference the same order. The natural relational design is one schema with foreign keys between them,
and it would make the obvious query — *show me this order with its reservation, payment and shipment*
— a single join.

It would also make the boundaries fictional, for a reason worth stating precisely: **two services
writing the same table cannot be deployed, restarted, or migrated independently.** A migration
belongs to whoever runs it last. That is not a style objection; it breaks the Phase 3 exit criteria
outright.

### The decision

Every table has exactly one owning service. Only the owner reads or writes it. Cross-service data
travels as events, never as shared SQL.

- **One schema per service** — `order_service`, `inventory_service`, `payment_service`,
  `fulfillment_service`, `scenario_service` — each with its own Flyway migration history.
- **One PostgreSQL server locally.** The boundary is enforced by convention and construction, not by
  network separation. Four database containers to enforce a rule a code review already enforces was
  judged disproportionate — and the schema-per-service layout means splitting later is a
  configuration change, not a migration.
- **No foreign keys across schemas.** `order_id` appears in four schemas and is a foreign key in
  exactly one: `order_service`, where `orders` actually lives. Everywhere else it is a correlation
  identifier the database cannot enforce.
- **Reliability tables are per-service, not shared.** Each service gets its own `processed_events`
  table with identical DDL.

That last point has a correctness argument behind it that is much better than "it seemed tidier," and
it is worth having ready: the deduplication insert **must commit in the same local transaction as the
business change it guards**. If the ledger lived in another service's schema, that transaction would
span two services and could not exist. [Chapter 4](../04-reliability/README.md) builds the mechanism this
protects.

### Rejected, and why

- **One shared schema with cross-domain foreign keys.** Strongest integrity, simplest queries.
  Rejected because it makes Phase 3's extraction a data migration, and because the integrity is less
  valuable than it looks: an order and its shipment are **eventually consistent by design**, so a
  foreign key would be asserting an invariant the architecture does not hold.
- **A separate PostgreSQL server per service from the start.** The strongest boundary and closest to
  a real deployment. Rejected locally as disproportionate.
- **Shared read access, private write access.** Each service writes only its own tables but may read
  others' for convenience. Tempting — and rejected on the sharpest reasoning in the ADR:
  *read coupling is still coupling.* It makes another service's schema part of your contract, so any
  migration becomes a cross-service coordination problem. It also removes the reason the event
  exists.

### The costs, recorded

- No cross-domain joins. Assembling a full picture of an order means several API calls.
- No referential integrity across boundaries. Every service must tolerate an `order_id` it has never
  seen — not hypothetical under at-least-once delivery and partial failure.
- Duplicated shape: four `processed_events` tables with identical DDL. Deliberate duplication,
  cheaper than the coupling it avoids.
- Five migration histories to keep in order.

---

## What this looks like in code, eventually

Phase 0 produces documents, but two of them get a direct code representation as soon as there is
code, both in `services/common` — the library module every service depends on.

`services/common/src/main/java/com/orderfulfillment/common/kafka/KafkaTopics.java` is the topic table
turned into constants, and its own comment carries the rule rather than leaving it in prose:

```java
/** docs/events/event-catalog.md §2 — frozen topic ownership table. A service publishes only to its
 * own domain topic; this class is the single place topic name strings live. */
public final class KafkaTopics {

    public static final String ORDERS_EVENTS = "orders.events";
    public static final String INVENTORY_EVENTS = "inventory.events";
    public static final String PAYMENTS_EVENTS = "payments.events";
    public static final String FULFILLMENT_EVENTS = "fulfillment.events";
```

Two things to notice, because they recur all over this codebase.

**The Javadoc cites the contract by filename and section.** That is not decoration — it is what makes
the contract enforceable six months later. Someone changing this file is told, in the file, where the
authority for it lives.

**Topic names exist in exactly one place.** A string literal `"orders.events"` scattered across five
services is a typo waiting to become a silently-unconsumed topic, because Kafka will happily create
`orders.event` for you and never tell anyone.

---

## Open question

`docs/adr/ADR-004`'s decision section says *"`outbox_events` exists only in Order Service
(ADR-006)."* That was true when written and is **no longer true** — Sprint 2 added an `outbox_events`
table and dispatcher to Inventory, Payment, and Fulfillment Service as well
([Chapter 6](../06-outbox/README.md)). ADR-006 carries a Sprint 2 correction note; ADR-004 does not. Worth
knowing before you quote ADR-004 at anyone.

---

[← Chapter 1](README.md) · [Next: The event contract →](2-the-event-contract.md)


# 1.2 — The event contract

[← Boundaries and ownership](1-boundaries-and-ownership.md) · [Next: State and API contracts →](3-state-and-api-contracts.md)

`docs/events/event-catalog.md` is the most valuable document in this project. Everything that crosses
a service boundary is defined in it, and nothing crosses a boundary that isn't.

---

## The problem: a consumer gets bytes

Kafka does not know or care what you put in a record. A record is a key (bytes), a value (bytes), a
timestamp, and some headers. That is the entire model.

So when Inventory Service pulls a record off `orders.events`, it holds a byte array. Before it can do
anything at all it must answer:

- **What is this?** Is it an `OrderCreated`, or something else? A topic can carry more than one event
  type, and this one does.
- **What shape is the inside?** Which fields exist, which are required, what types are they?
- **Have I seen it before?** At-least-once delivery means the answer is sometimes yes, and processing
  it twice must not reserve stock twice.
- **What is it about?** Which order does this concern?
- **Where did it come from?** When something goes wrong across five services, which chain of events
  does this belong to?

None of that is in Kafka. All of it has to be in the record you wrote.

The naive approach is to serialize the domain payload directly — `{"orderId": "...", "items": [...]}`
— and infer the rest. It works for exactly one event type. The moment a topic carries two, the
consumer is guessing from field presence, which is the kind of code that works until someone adds an
optional field.

## The technology: an envelope

The standard answer is an **envelope**: a fixed outer structure, identical for every event in the
system, carrying a variable inner `payload`. The consumer deserializes the envelope first — which it
can always do, because the envelope never changes — reads the type from it, and only then interprets
the payload accordingly.

This is the same idea as an HTTP message (fixed headers, variable body) or an email (fixed headers,
variable content), and it buys the same thing: infrastructure can route, deduplicate, trace, and log
a message without understanding its contents.

---

## This project's envelope

```json
{
  "eventId": "0c7c3acd-8b3b-45fd-ae4a-b8c73b5a419e",
  "eventType": "OrderCreated",
  "eventVersion": 1,
  "occurredAt": "2026-08-07T20:31:04.220Z",
  "correlationId": "d89512f7-b544-4170-b66b-2e93f475ea8f",
  "aggregateId": "order-21873",
  "payload": {
    "orderId": "order-21873",
    "customerId": "demo-customer",
    "items": [{ "sku": "SKU-001", "quantity": 2 }]
  }
}
```

Six envelope fields. Each answers one of the questions above, and each is worth understanding
individually — this is the structure the entire rest of the system is built on.

### `eventId` — a UUID, unique per published record

The **idempotency key**. A consumer records the `eventId`s it has processed and skips ones it has
seen ([Chapter 4](../04-reliability/README.md)).

The subtle part, and the part worth getting right in your head: it is unique per *published record*,
not per *delivery*. A redelivered record carries the **same** `eventId` — that is exactly what makes
deduplication possible, and it is exactly what Scenario 4 exploits when it republishes a record
verbatim to prove the consumer is idempotent. If a duplicate got a fresh `eventId`, no consumer could
tell it apart from a genuinely new event, and nothing downstream could save you.

### `eventType` — one of eight frozen strings

Routing and interpretation. `OrderCreated`, `InventoryReserved`, `InventoryReservationFailed`,
`InventoryReleased`, `PaymentRequested`, `PaymentAuthorized`, `PaymentRejected`, `ShipmentCreated`.

All past tense. Not a stylistic preference: **an event is a statement of fact about something that
already happened**, and the naming enforces the mental model. `ReserveInventory` would be a command —
a request that the recipient may decline. `InventoryReserved` is a report that cannot be declined,
only reacted to. Commands imply a boss; events imply peers. This system has no boss.

### `eventVersion` — an integer, starting at 1

The escape hatch for changing a payload's shape later without breaking consumers that have not caught
up. The rule (§5 of the catalog):

- **Additive, optional field** → no bump. Consumers must ignore unknown fields, so deserialization is
  configured to tolerate them.
- **New required field, removed field, renamed field, or changed semantics** → bump the version for
  that event type only, and document what changed.
- A consumer receiving a version it does not know must treat it as a **non-retryable** failure and
  dead-letter the record. Retrying cannot fix a schema you do not understand — a distinction
  [Chapter 4](../04-reliability/README.md) builds real machinery around.

The catalog is honest about the status of this field: *"`eventVersion` exists in v1 mainly so that
this rule can be stated and demonstrated; no event is expected to reach version 2."* That is the right
call and the right way to record it. A versioning scheme you have thought through and not yet needed
is cheap; retrofitting one onto a live topic is not.

### `occurredAt` — RFC 3339, UTC, milliseconds

When the publishing service **decided the event happened** — not when Kafka wrote it, and not when a
consumer read it.

The distinction is easy to wave past and matters under exactly the conditions this project
demonstrates. With the outbox ([Chapter 6](../06-outbox/README.md)), an event can be written to a database
minutes before it reaches Kafka. With a paused consumer (Scenario 5), it can be read an hour after
that. If `occurredAt` meant "when it was published," a timeline built from these events would be a
timeline of infrastructure delays rather than of business facts.

### `correlationId` — a UUID, constant across one workflow

The thread that ties everything together. Generated once by whoever starts a workflow — Order Service
on `POST /api/orders`, or Scenario Service at the start of a run — and **copied by every consumer onto
every event it publishes in reaction**.

That copying rule is the whole mechanism. Order Service generates a correlation ID and puts it on
`OrderCreated`. Inventory Service consumes that, and when it publishes `InventoryReserved` it does not
generate a new one — it carries the same one forward. So does Payment Service, and Fulfillment. One
order's entire event chain, across four services, shares one identifier.

The same identifier also lands in every log line those services write while handling the event
([Chapter 8](../08-observability-and-scaling/README.md)), which turns "what happened to order 21873" from an
archaeology exercise into a filter.

ADR-001 lists this as a consequence rather than a feature: *"Debugging spans process boundaries, which
is why correlation IDs are a required envelope field rather than a nice-to-have."*

### `aggregateId` — the thing the event is about

For every event in this system, the `orderId`. It is also the Kafka record key, which is where it
stops being bookkeeping and starts being load-bearing — see below.

"Aggregate" is domain-driven-design vocabulary for the entity that owns a consistency boundary. This
project has exactly one, which keeps the concept simple: everything is about an order.

### `payload` — event-specific, never null

Use `{}` rather than omitting it. One deliberate redundancy is allowed: `orderId` appears in both
`aggregateId` and inside each payload, *"because payload consumers should not have to understand the
envelope's aggregate convention to find the order."*

That is a small decision with a good justification, and the sort of thing worth being able to defend:
a rule that is 95% consistent with one documented exception is better than a rule that is 100%
consistent and forces every consumer to learn a convention.

---

## Topics and keys

### The technology: partitions are the unit of ordering

A Kafka topic is split into **partitions**, each an independent, ordered, append-only sequence. Which
partition a record lands in is decided by its **key**: same key, same partition, always.

> Kafka guarantees ordering **within a single partition**. It guarantees nothing across partitions,
> and nothing across topics.

So the key is not metadata. It is the choice of *what you want ordered relative to what* — and
whatever you key by is the exact scope of your ordering guarantee.

> **Primer — [Kafka: topics, partitions, keys, and offsets](../technology/kafka/topics-partitions-keys.md)**
> The record model, partitions as the unit of both ordering and parallelism, keys and hashing, offsets
> and commits, consumer groups and rebalancing, replication, producer `acks`, and KRaft.

### The decision: key everything by `orderId`

Every record on every topic in this system is keyed by `orderId`. The catalog states the property
that buys, and the property it does not:

> This gives per-order ordering within a partition, which is the property the workflow actually
> depends on (an order's `InventoryReserved` must not be processed after its `PaymentAuthorized`).
> It provides no cross-order ordering, and the implementation must not assume any.

That second sentence is the more useful half. Order 100's events are ordered with respect to each
other. Order 100's events have no defined ordering with respect to order 101's, ever. Any logic that
assumes otherwise is a bug that will appear under load and vanish when you try to reproduce it.

> **Not yet — and it's bigger than it looks.** Keying by `orderId` orders one order's events within
> *one topic's* partition. Order Service consumes **three different topics**, each with its own
> partitions and its own offsets, and Kafka guarantees nothing between them. Phase 0 did not notice
> this. It took until after Phase 10 to find, and the fix is ADR-009 — built in
> [Chapter 4](../04-reliability/README.md), with the story of how it was found in
> [Chapter 10](../10-retrospective/README.md).

### The rule: a service publishes only to its own topic

| Topic | Published by | Carries |
|---|---|---|
| `orders.events` | Order Service | `OrderCreated`, `PaymentRequested` |
| `inventory.events` | Inventory Service | `InventoryReserved`, `InventoryReservationFailed`, `InventoryReleased` |
| `payments.events` | Payment Service | `PaymentAuthorized`, `PaymentRejected` |
| `fulfillment.events` | Fulfillment Service | `ShipmentCreated` |
| `orders.dlq`, `inventory.dlq`, `payments.dlq`, `fulfillment.dlq` | the failing **consumer** | Records that exhausted retries, plus failure metadata |

Topics are **domain-oriented**, one per publishing service — not one per event type, and not one per
consumer.

The immediately surprising row is `PaymentRequested` on `orders.events`. It is a payment-flavored
event sitting on the orders topic. The reason is precise:

> the alternative would make Payment Service both a producer and a consumer of the same topic, so
> every Payment Service consumer would have to filter out its own output.

That is worth sitting with, because it generalizes. Once "a service publishes only to its own topic"
is the rule, the awkward case resolves itself automatically and every consumer knows, from the topic
name alone, which service's output it is reading. A topic-per-event-type scheme would have avoided the
surprise here and created a proliferation of near-empty topics and a routing table nobody can hold in
their head.

### DLQ topics are routing targets, not event types

There is one DLQ per **consuming** domain, and the rule for which one a record goes to is the part
people get backwards. From `KafkaTopics.java`:

```java
/**
 * Dead-letter topics, one per consuming service — routing targets, not domain event types
 * ...
 * A service dead-letters to its <em>own</em> domain's DLQ
 * regardless of which topic the failing record came from: Inventory Service consumes
 * {@code orders.events} and {@code payments.events}, and both dead-letter to
 * {@code inventory.dlq}, because the failure belongs to the consumer, not to the publisher.
 */
```

**The failure belongs to the consumer, not to the publisher.** Order Service published a perfectly
good record; Inventory Service could not process it. Routing that to `orders.dlq` would file
Inventory's bug under Order's name, and whoever owns Inventory Service would never look there.

### Partition count

`KafkaTopicConfig` declares all eight topics explicitly rather than relying on broker auto-creation,
at **3 partitions, replication factor 1**:

```java
private static final int PARTITIONS = 3;
private static final int REPLICATION_FACTOR = 1;
```

Two reasons for declaring rather than auto-creating, and the code comments give both. Determinism —
auto-created topics get broker defaults, which are not yours. And for the DLQs specifically: *"a DLQ
that only exists because a broker auto-created it on first dead-letter would have broker-default
partitioning, and Phase 4's whole point is that the failure path is as real and as deterministic as
the happy path."*

Remember the number 3. It is the ceiling on consumer parallelism per group, and
[Chapter 8](../08-observability-and-scaling/README.md) runs into it directly: a fourth replica in the same
consumer group gets no partitions and does nothing.

Replication factor 1 means a single broker with no redundancy. If it dies, the data is gone. That is a
deliberate scope decision (`docs/planning/project-overview.md` rules out "full production Kafka
operations") and one to state plainly rather than let someone discover.

---

## Delivery semantics, stated honestly

> At-least-once. Consumers must tolerate duplicate delivery [...] This project does **not** implement
> exactly-once semantics, and no document, UI string, or README may claim it.

This is a project rule with its own entry in `docs/planning/agent-guidance.md` (rule 18), and it is
worth understanding why it is enforced so aggressively.

"Exactly-once" is the most oversold phrase in messaging. Kafka does offer exactly-once *semantics*
within a bounded scope — transactional reads and writes where both sides are Kafka. The moment your
consumer's side effect is a database write, an HTTP call, or an email, that guarantee does not extend
to it: Kafka cannot make your side effect part of its transaction. What you get instead is
at-least-once delivery plus an idempotent consumer, which produces the same *observable* outcome by a
completely different mechanism.

Claiming exactly-once when you mean "at-least-once plus deduplication" is the kind of thing an
interviewer notices immediately, and it is why the project bans the phrase outright.

The catalog also records the gap that existed when it was written:

> Until Phase 6 (transactional outbox), publishers persist their business change and then publish —
> so a crash between commit and publish loses the event.

A known limitation, written down at the time, rather than discovered later. [Chapter 6](../06-outbox/README.md).

---

## Events deliberately excluded

Phase 0 also recorded what it decided *not* to have — three candidates, each with a reason:

| Candidate | Why excluded |
|---|---|
| `OrderShipped` | In v1 a shipment is created and the order is immediately `FULFILLED`. There is no separate dispatch step, so the event would carry no information `ShipmentCreated` doesn't. |
| `FulfillmentRequested` | It would make `PAID` → `FULFILLMENT_PENDING` event-driven and mirror `PaymentRequested` nicely — but Fulfillment Service consumes `PaymentAuthorized` directly, so nobody needs to send it a request. Rejected as a cosmetic gain that contradicts a frozen design doc. |
| Notification events | The Notification Service they belong to is explicitly "only after the core system is stable." |

A list of things you decided not to build, with reasons, is worth as much as the list of things you
did. It is the difference between "we didn't think of that" and "we considered it and here's the
tradeoff" — and the second is the only one of the two that is an answer.

---

## What this looks like in code

`services/common/src/main/java/com/orderfulfillment/common/events/EventEnvelope.java` — the whole
thing:

```java
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID correlationId,
        String aggregateId,
        T payload
) {
}
```

A Java **record**: an immutable data carrier where the compiler generates the constructor, accessors,
`equals`, `hashCode`, and `toString` from the header. Exactly right for a message envelope, which
should never be mutated after construction.

The generic `T payload` is the interesting part, and its Javadoc explains the asymmetry:

> producers build an envelope over a concrete payload record; consumers first deserialize with
> `payload` typed as `JsonNode` [...] and then convert it to the concrete payload type once
> `eventType` is known, since a single topic can carry more than one event type.

That is the envelope pattern's whole point expressed in one type parameter. A producer knows what it
is sending, so it uses `EventEnvelope<OrderCreatedPayload>`. A consumer does not know yet, so it
deserializes to `EventEnvelope<JsonNode>` — a parsed-but-uninterpreted JSON tree — reads `eventType`,
and only then converts the payload to the right type. [Chapter 3](../03-kafka-and-services/README.md) walks
through the `EventCodec` that does this.

Alongside it, `EventTypes.java` pins the eight strings and the current version in one place:

```java
public static final String ORDER_CREATED = "OrderCreated";
// ...
/** Every eventType in this catalog is at eventVersion 1 (event-catalog.md §5). */
public static final int CURRENT_VERSION = 1;
```

---

## Where the contract and the code disagree

The event catalog's topic table (§2) lists a sixth topic:

> | `demo.events` | Scenario Service | Scenario-run lifecycle records used to build the run timeline. Not domain events; no entry in §3. |

**This topic does not exist.** It appears nowhere in `KafkaTopics`, nowhere in `KafkaTopicConfig`,
and nowhere else in the codebase. Scenario Service builds its run timeline by writing
`scenario_run_timeline` rows through `TimelineRecorder` and pushing them straight out over SSE —
persist first, then publish to subscribers, *"so a subscriber never sees an entry that isn't durable
yet."* No Kafka is involved. Scenario Service does use a `KafkaTemplate`, but only to publish
duplicate and poison records onto the **existing domain topics** for Scenarios 4 and 6.

So `demo.events` is a Phase 0 idea that the implementation solved a simpler way and the contract was
never updated to match. It is harmless — nothing depends on it — but it is a live example of exactly
what this chapter's [introduction](README.md) warns about: frozen is a discipline, not a guarantee,
and load-bearing claims get checked against the code.

---

[← Boundaries and ownership](1-boundaries-and-ownership.md) · [Next: State and API contracts →](3-state-and-api-contracts.md)


# 1.3 — The state and API contracts

[← The event contract](2-the-event-contract.md) · [Next: Sequencing and deferrals →](4-sequencing-and-deferrals.md)

Two more Phase 0 artifacts. `docs/order-state-machine.md` defines what an order can be and how it
gets there. `docs/openapi/*.yaml` defines what a client can ask for. Both are frozen; both are
enforced in code later.

---

## Part A — The state machine

### The problem: status is the one thing everybody reads

Almost nothing in this system is shared. Each service owns its own tables, publishes its own events,
and minds its own business. Order status is the exception: it is written by one service and read by
everything — the UI renders it, every integration test asserts on it, every scenario's success
condition is expressed in terms of it, and the ADRs argue about it.

That makes it the single highest-value thing to get exactly right up front, and the single most
expensive thing to change later.

Two failure modes if you don't:

**Vocabulary drift.** Two documents call the same state different names; two services implement both;
a test asserts one and the UI displays the other. Nobody notices until a scenario fails for a reason
that has nothing to do with the scenario.

**Silent invalid states.** Without an explicit set of legal transitions, "what states can an order be
in, and how did it get here" has no answer other than reading every writer. Any writer can put an
order into any state, and there is no place to notice that it shouldn't have.

### The technology: a finite state machine

Four parts: a finite set of **states**, a designated **initial** state, a set of **terminal** states
with no exit, and a set of legal **transitions** — `(from, to)` pairs, each with a named cause.

The power is entirely in what it **forbids**. If the transition set is exhaustive, any pair not in it
is invalid by definition, and "invalid" becomes something code can detect rather than something a
reviewer might notice.

> **Primer — [Finite state machines](../technology/concepts/state-machines.md)**
> Why an explicit transition set matters, encoding the table so code actually consults it, the two
> consistency checks worth running on the table itself, marking internal vs. externally-caused
> transitions, and the difference between *rejecting* and *deferring* an invalid transition.

### The decision: nine states, nine transitions, every cause named

The states are in [Chapter 0](../00-orientation.md#4-the-order-states). The transitions are the
interesting half:

| # | From | To | Cause | Kind |
|---|---|---|---|---|
| 1 | *(none)* | `PENDING` | `POST /api/orders` succeeded | REST |
| 2 | `PENDING` | `INVENTORY_RESERVED` | `InventoryReserved` | event |
| 3 | `PENDING` | `REJECTED_OUT_OF_STOCK` | `InventoryReservationFailed` | event |
| 4 | `INVENTORY_RESERVED` | `PAYMENT_PENDING` | Order Service publishes `PaymentRequested` | **internal** |
| 5 | `PAYMENT_PENDING` | `PAID` | `PaymentAuthorized` | event |
| 6 | `PAYMENT_PENDING` | `PAYMENT_FAILED` | `PaymentRejected` | event |
| 7 | `PAID` | `FULFILLMENT_PENDING` | Order Service records a shipment is outstanding | **internal** |
| 8 | `FULFILLMENT_PENDING` | `FULFILLED` | `ShipmentCreated` | event |
| 9 | any non-terminal | `FAILED` | Non-retryable failure, or retries exhausted into a DLQ | **internal** |

And the rule that gives the table teeth:

> Any transition not listed above is invalid and must be rejected by the domain model, not silently
> applied.

**Event-caused vs. internal** is a distinction worth having explicitly. Six transitions happen because
Order Service consumed a named event. Three happen because Order Service moved the order itself, with
no inbound event — and those three are exactly the ones a reader would otherwise waste time hunting
for an event to explain. Marking them is a small act of kindness toward your future self.

Transition 7 is the one that surprises people, and its explanation is a good example of a contract
doing real work:

> Fulfillment Service consumes `PaymentAuthorized` directly, so Order Service never sends it a
> request. [...] one event (`PaymentAuthorized`) therefore drives two consecutive transitions in the
> same Order Service handler — `PAID` records the payment outcome, `FULFILLMENT_PENDING` records that
> a shipment is outstanding. `PAID` is consequently short-lived and will rarely be observed by the UI.

So a state exists in the enum that the UI will almost never display, because two transitions fire back
to back inside one handler. Without that note, the first person to watch the UI would file a bug.

### Consistency checks: the part most people skip

`docs/order-state-machine.md` §4 does something Phase 0 did not strictly have to do, and it is the
part worth copying. It checks the contract against itself, twice:

**Every state is reachable.** A table mapping each of the nine states to the transition that produces
it. Trivially mechanical — and it catches the classic error of an enum value nobody can actually get
into.

**Every status-changing event is accounted for.** A table mapping each of the eight catalogued events
to its status effect. Two of them (`OrderCreated`, `InventoryReleased`) have *no* status effect, and
they are listed anyway, with the reason stated:

> The two with no status effect are listed explicitly so that "missing from the transition table"
> cannot be mistaken for an oversight.

Recording a deliberate absence so it cannot be mistaken for a gap is a habit that pays off every time
someone new reads the document — including you, later.

### What freezing actually resolved

The clearest evidence that this was worth doing is a naming conflict Phase 0 caught. Three planning
documents disagreed:

- `backend-design.md`'s state list said `OUT_OF_STOCK`.
- `backend-design.md`'s own flow diagram said `REJECTED_OUT_OF_STOCK`.
- `frontend-design.md`'s Scenario 2 said `REJECTED_OUT_OF_STOCK`.

A single document contradicted itself. Phase 0 froze `REJECTED_OUT_OF_STOCK` — matching two of three
references, including the one the UI and the Scenario 2 test assert against — and *did not edit the
planning docs*, recording the conflict and its resolution in the state machine document instead.

Had this not been caught in Phase 0, it would have been caught in Phase 5 by a UI that renders a
status string no backend ever emits, and the fix would have touched an enum, a database column, a
test suite, and a frontend.

### What this becomes in code

`services/order-service/.../OrderStatus.java` is the enum, with terminality as a first-class property:

```java
/** docs/order-state-machine.md §1 — the frozen order lifecycle enum. Owned exclusively by Order Service. */
public enum OrderStatus {
    PENDING, INVENTORY_RESERVED, REJECTED_OUT_OF_STOCK, PAYMENT_PENDING,
    PAID, PAYMENT_FAILED, FULFILLMENT_PENDING, FULFILLED, FAILED;

    private static final Set<OrderStatus> TERMINAL =
            Set.of(REJECTED_OUT_OF_STOCK, PAYMENT_FAILED, FULFILLED, FAILED);

    public boolean isTerminal() { return TERMINAL.contains(this); }
}
```

And `OrderTransitions.java` is §3's table, transcribed with the table's row numbers left in as
comments so the two can be diffed by eye:

```java
// 2, 3
VALID_PREDECESSORS.put(OrderStatus.INVENTORY_RESERVED, Set.of(OrderStatus.PENDING));
VALID_PREDECESSORS.put(OrderStatus.REJECTED_OUT_OF_STOCK, Set.of(OrderStatus.PENDING));
// 4
VALID_PREDECESSORS.put(OrderStatus.PAYMENT_PENDING, Set.of(OrderStatus.INVENTORY_RESERVED));
```

> **We got this wrong.** `OrderTransitions` did not exist until well after Phase 10. For most of the
> project's life the transition table was prose and documentation comments, and *no code consulted
> it* — `OrderPersistence` wrote whatever status its caller handed it. The bug that produced is in
> [Chapter 10](../10-retrospective/README.md); the mechanism is built properly in
> [Chapter 4](../04-reliability/README.md). The build-along writes the guard from the start.

---

## Part B — The API contracts

### OpenAPI, and what it is for

OpenAPI is a YAML description of an HTTP API: paths, methods, parameters, schemas, status codes.
Tooling can generate clients, servers, and documentation from it — and none of that tooling is why it
is here.

It is here because it is **a contract readable by someone who is not going to read your controller**,
and in this project's case by a different agent session building the frontend against a backend that
did not yet exist. That only works if the spec is *written first* and the code built against it, which
is a different activity from generating a spec out of finished code.

> **Primer — [OpenAPI](../technology/http/openapi.md)**
> Generated-from-code vs. written-first and why the distinction matters, what a schema cannot say,
> `$ref` / `operationId` / `servers`, and how a hand-written spec drifts.

The specs carry their status in the description block:

> **Frozen by Phase 0.** Changes must follow the coordination protocol [...]: propose the change in
> this file first with a rationale, then update implementations and tests.

They also carry the things a schema cannot express. From `order-service.yaml`:

> **Asynchrony.** `POST /api/orders` returns as soon as the order is persisted and `OrderCreated` is
> published. It does not wait for inventory, payment, or fulfillment.

That paragraph is the most important thing in the file. A client author who reads only the response
schema sees an order object with a `status` field and reasonably concludes that the status is the
answer. It is not; it is the *first* answer.

And a boundary statement worth copying:

> **Health and metrics** are exposed by Spring Boot Actuator [...] and are deliberately outside this
> document.

Saying where the document *stops* is part of writing a contract.

### The `/api` vs `/demo` split (ADR-002)

This is the decision that keeps the project honest, and it is the one most worth being able to defend
out loud.

**The problem.** The project's centerpiece is reproducible failure: reject this payment, pause that
consumer, republish this record. Those controls have to be reachable, because scenarios must be real
rather than animated. And the cheapest way to make a payment fail is a flag on the order request:

```
POST /api/orders {"forcePaymentFailure": true}
```

ADR-002's account of why that is fatal is precise:

> Once that exists, the production-style API is no longer production-style, and the project's central
> claim ("this is what real event-driven order processing looks like") is quietly false. A reviewer
> reading the controller would see demo scaffolding inside business logic.

Plus a second-order effect that is easy to underrate: *"a flag that exists in a DTO gets validated,
tested, documented, and eventually depended on."* Demo scaffolding does not stay contained. It
acquires tests. It becomes load-bearing.

**The decision.** Two namespaces, separated by construction and never mixed.

- **`/api`** — production-style business endpoints. No scenario parameters, no fault-injection flags,
  no demo-only fields, and **no branch anywhere in their call path that asks which scenario is
  running**.
- **`/demo`** — scenario control and fault injection. Consumer pause/resume, payment simulator
  behavior, scenario runs, environment reset.

With three concrete consequences:

1. A dedicated **Scenario Service** owns orchestration. This is the fifth service, and it exists
   entirely because of this decision.
2. Where a control must live inside the service it affects — you cannot pause a Kafka listener from
   another process — it lives under that service's `/demo` prefix, **in a separate controller**. Hence
   `DemoConsumerController` and `DemoInventoryController` sitting beside `InventoryController`.
3. Scenarios drive the system through its own public `/api` endpoints. Scenario 3 creates its order
   with the same `POST /api/orders` any client uses; only the simulator's *configured* behavior
   differs.

Point 3 is the elegant part. **Failure injection is a property of the environment, not of the
request** — which is also how real operational failures actually arrive. Nobody's production incident
begins with a client politely setting `forceFailure: true`.

**Rejected alternatives**, each for a different reason:

- **Flags on business endpoints.** Rejected outright — contradicts two scope principles and makes the
  demonstration self-undermining.
- **One all-knowing demo service that manipulates other services' databases and Kafka state
  directly.** Keeps every service's code clean. Rejected because it violates ADR-004 (Scenario
  Service would need write access to four schemas) *and because a listener cannot be paused from
  outside its own process anyway*. The `/demo` prefix inside each service is the smaller compromise:
  demo code is local, but visibly quarantined.
- **A separate Spring profile or port for demo endpoints.** Stronger isolation, and compatible with
  this decision later. Rejected as premature — and note the sharper reason: *the demo endpoints must
  be reachable in the deployed demo.* That is the whole product. Compiling them out is not what is
  wanted.

**The costs, recorded rather than hidden:**

- Scenario Service calls other services synchronously, which Phase 3 otherwise forbids. Justified as
  control plane, not workflow: no order transition depends on those calls.
- **Demo state is real state.** A run that fails halfway can leave a paused listener or an armed
  rejection behind — which is why `POST /demo/reset` exists and why it reports what it actually
  reset.
- A fifth service to run, deploy, and keep healthy, containing no business logic.
- Payment Service's rejection override is armed *before* its target order exists, so it is un-scoped
  for the duration of a run. ADR-002 calls this *"a demo-only wart, and the honest cost of not
  passing a flag through the business request."*

That last bullet is the one to remember. A known wart, named, with the tradeoff that produced it
stated. Compare it to the alternative — a `forcePaymentFailure` flag — and the wart is obviously the
better deal. But you only get to make that comparison if you wrote it down.

> **Where this pays off.** [Chapter 9](../09-production/README.md) puts the demo on the public internet. The
> `/api`–`/demo` split is what makes it possible to route the demo surface a visitor needs while
> leaving consumer-pause and payment-override endpoints cluster-internal and unreachable. A design
> decision made in Phase 0 for cleanliness turned out to be the security boundary.

---

[← The event contract](2-the-event-contract.md) · [Next: Sequencing and deferrals →](4-sequencing-and-deferrals.md)


# 1.4 — Sequencing, and what Phase 0 refused to decide

[← State and API contracts](3-state-and-api-contracts.md) · [Chapter 1 ↑](README.md) · [Chapter 2 →](../02-domain/README.md)

Phase 0's last two outputs are not artifacts. They are a build order, and a list of things
deliberately left undecided.

---

## Part A — Why Kubernetes waits until Phase 8

`docs/planning/sprint-1/implementation-phases.md`'s Phase 0 section ends with a five-word paragraph:

> Do not begin with Kubernetes.

This is ADR-007, and it is the decision most likely to feel like an anticlimax and most likely to
save you a week.

### The problem: infrastructure written against a moving target

Kubernetes is one of the technologies this project exists to demonstrate, so it is definitely getting
built. The question is *when*, and it is a sequencing question with a real trap on the far side.

The trap is this. The build plan **deliberately restructures the system twice**: a modular monolith
(Phase 1) becomes Kafka-connected modules in one process (Phase 2), which become four independently
deployable services (Phase 3). Deployments, Services, ConfigMaps, and probes written against the
monolith would be rewritten twice before they described anything real.

There is a second cost, which is the one you feel daily. Debugging domain logic through a pod restart
loop is dramatically slower than debugging it with a debugger attached in an IDE. Every iteration
becomes build → image → push → rollout → read logs. And Phases 1–6 are precisely where this project's
actual correctness risk lives — inventory concurrency, idempotency, transition ordering. Those are
the problems worth iterating fast on.

### The decision

Kubernetes lands in **Phase 8**, after boundaries are stable (Phase 3), reliability behavior exists
(Phase 4), and containers exist (Phase 7).

- **Phases 1–6:** services run from the IDE against PostgreSQL and Kafka in Docker Compose.
  Kubernetes is not installed, not required, and not referenced by any run instruction.
- **Phase 7:** a Dockerfile per service plus a Compose stack running the whole system.
- **Phase 8:** Deployments, Services, ConfigMaps, Secrets, probes, resource requests and limits,
  against local `kind`.
- **Deferred past Phase 8:** HorizontalPodAutoscaler, PodDisruptionBudget, NetworkPolicy, service
  mesh (an explicit non-goal).
- **The local path stays supported permanently.** Kubernetes never becomes the only way to run this
  project.

### Rejected alternatives

- **Kubernetes from Phase 1.** Highest fidelity; front-loads deployment problems. Rejected because it
  multiplies the cost of both planned restructurings — and with a good second argument: *"the
  deployment problems it front-loads are also the ones this project is least likely to get wrong; the
  domain and concurrency problems are the risky ones."* Sequence your work against where the risk
  actually is.
- **Skip Kubernetes; ship Compose only.** Perfectly adequate to run the system, and honest — a
  five-service demo does not need an orchestrator. Rejected because Kubernetes is an explicit
  portfolio goal *and* because parts of the demonstration genuinely need it: multiple replicas in one
  consumer group, pod restarts as a way to trigger consumer recovery, HPA behavior under load.
- **A managed cloud cluster (EKS/GKE) instead of `kind`.** More impressive on paper. Rejected on cost
  and reproducibility — `kind` is free, starts in a minute, and can be recreated identically in CI.
- **Helm instead of plain manifests.** Templating would remove duplication. Rejected as premature:
  *"plain YAML is what a reviewer can read without knowing Helm, and five nearly identical
  Deployments are not yet a duplication problem worth a templating layer."*

### The costs, recorded

ADR-007 is unusually good about naming what deferring actually costs:

- **Deployment problems surface late**, and some are only visible in a cluster: readiness gating
  during rolling updates, resource limits triggering OOM kills, and SSE connections dropping when a
  pod is replaced. All three of those happened. Two of them are in
  [Chapter 9](../09-production/README.md).
- **Two supported ways to run the system** from Phase 7 onward, which means two sets of configuration
  and startup documentation to keep accurate.
- **Nothing before Phase 8 proves the services are container-friendly.** Configuration must come from
  environment variables and nothing may depend on local filesystem state, *"or Phase 7 turns into a
  refactor — worth watching for from Phase 1, even though it is not verified until later."*

That last one is the practical takeaway from this ADR, and it is worth acting on from your very first
service: **write configuration as environment variables with sensible defaults from day one**, even
while you are running everything from an IDE. It costs nothing then and it is what makes Phase 7 a
packaging exercise instead of a rewrite. [Chapter 2](../02-domain/README.md) does this from the start.

### The related sequencing decision

The same reasoning produces the build order itself, pinned in
`docs/planning/project-overview.md`:

> **Build sequence**: modular monolith first (Phase 1), Kafka introduced in-process (Phase 2), then
> extracted into 4 independent services (Phase 3). Don't jump straight to separate services before
> Phase 3.

Prove the business workflow while it is still one process and one debugger away. *Then* make it
asynchronous. *Then* make it distributed. Each step changes exactly one thing about the system, so
when it breaks you know which change broke it.

This is the single most transferable idea in the project. Distributing a system you have not yet got
working is how you end up debugging your domain logic and your infrastructure simultaneously, unable
to tell which is lying to you.

> **Where the guide diverges from history.** Phases 2 and 3 were not actually separate steps — the
> real commit is `1f2bc50 introduce kafka and split up monorepo`, doing both at once.
> [Chapter 3](../03-kafka-and-services/README.md) follows the commit rather than the plan, and says so.

---

## Part B — What Phase 0 deliberately did not decide

An underrated half of contract-first work is knowing where to stop. Deciding everything up front is
just as much a failure as deciding nothing — it is guessing, with the guesses written down in an
authoritative-looking file.

Phase 0 left several things open, each for a stated reason.

**JSON Schema files for event payloads.** The event catalog says so directly:

> JSON Schema files for each payload (`docs/events/schemas/*.json`) are deliberately **not** part of
> Phase 0 [...] which places them at Phase 2, once payloads have been exercised by real producers and
> consumers.

A machine-readable schema for a payload that no producer has ever produced is a guess wearing
formalwear. The prose description is enough to build against; the formal schema is worth writing once
reality has had a chance to disagree with it.

**Everything about scaling.** Partition counts, replica counts, resource limits, autoscaler
thresholds. None of these can be chosen without measurements, and there was nothing to measure. They
are settled in [Chapter 8](../08-observability-and-scaling/README.md) against real numbers.

**The `FAILED` state's entry condition.** `FAILED` appears in the frozen state list with no
transitions into it. The state machine document formalizes transition 9 and flags exactly what it
did:

> This is the one state in this document whose entry condition Phase 0 supplied rather than
> formalized.

An admission that one row of the table is a Phase 0 invention rather than a Phase 0 transcription.
That row went unimplemented until Sprint 2 — which the ADR that shipped alongside it says out loud
rather than quietly closing.

**Deployment, entirely.** There is no Phase 0 decision about where this runs. It was made in Sprint 2
(ADR-010), when there was a working system and an actual reason to deploy it.

---

## Part C — The ADR as an artifact

Eleven ADRs, in `docs/adr/`, one file each, all the same shape:

```
# ADR-00N: <decision, stated as an imperative>

- **Status:** Accepted. <where it was implemented>
- **Date:** <when>

## Context          — the forces. What makes this a real question.
## Decision         — what was chosen, concretely enough to check code against.
## Alternatives considered
## Consequences and tradeoffs
    **Accepted costs.** / **What it buys.**
```

Four properties of this format are doing the work.

**The title is the decision, not the topic.** "Use Kafka for asynchronous order lifecycle events,"
not "Messaging." You can read the eleven filenames and know what the system is.

**Status records where it was implemented, and when that changed.** ADR-006's status line is four
sentences long because the decision shipped in stages and Sprint 2 changed its scope. A status that
says only "Accepted" tells you nothing about whether the code matches.

**Alternatives are argued in good faith.** This is the property that separates a useful ADR from a
justification. ADR-001 says synchronous REST is *"genuinely the right answer for a system of this
size."* ADR-004 says a shared schema has *"the strongest integrity guarantees."* ADR-007 says Compose
alone would be *"perfectly adequate to run the system, and honest."*

An ADR whose rejected options are all obviously bad has recorded nothing. The whole value is in the
close calls — and in an interview, "here's the strongest argument for the thing I didn't do" is a far
better answer than a list of reasons your choice was inevitable.

**Costs are stated as costs.** Not "considerations," not "future work." ADR-002 calls its own demo
override *"a demo-only wart."* ADR-001 says *"every read-your-writes expectation is gone."* You
cannot defend a design you have only ever described the upside of.

### When to write one

Write an ADR when a decision has a **real alternative** and a **cost you will forget**. Not for
choices with one obvious answer, and not for things the code already says clearly. Eleven records for
a project this size is about right — enough to cover every structural choice, few enough that reading
all of them is an afternoon rather than a project.

---

## Chapter 1 in one paragraph

Phase 0 produced no running code and decided seven things: where the service boundaries are and why
nothing crosses them synchronously (ADR-001); who owns which tables and why nobody reads anyone
else's (ADR-004); the envelope, topics, and keys every event uses; the nine order states and the nine
transitions between them; the REST surface of five services; the failure scenarios worth
demonstrating; and the order the whole thing gets built in (ADR-007). It also wrote down what it
deliberately did not decide. Everything after this chapter is downstream of those documents, and the
two places where the code has since drifted from them — `demo.events` in
[section 2](2-the-event-contract.md), `outbox_events` ownership in
[section 1](1-boundaries-and-ownership.md) — are drift in the *documents*, found by checking them
against the code rather than the other way round.

---

[← State and API contracts](3-state-and-api-contracts.md) · [Chapter 1 ↑](README.md) · [Chapter 2 — The domain, built synchronously →](../02-domain/README.md)


<hr style="page-break-after: always;"/>

# Chapter 2 — The domain, built synchronously

**Build history:** Phase 1. Commits `c32e5c6 add backend services` and `8a466ce add frontend application`.

The longest chapter in the guide, and the one everything else stands on. By the end of it you have a
working order-fulfillment system: a real database, real business rules, a real HTTP API, real tests,
and a small React console — running as **one process**, with no Kafka anywhere.

That last part is the point. Phase 1's goal, in the plan's own words, is to *"prove the business
workflow before distributing it."* Distributing a workflow you have not yet got working means
debugging your domain logic and your infrastructure at the same time, unable to tell which one is
lying to you.

---

## Sections

| # | Section | Covers | Status |
|---|---|---|---|
| 1 | [The project skeleton](1-project-skeleton.md) | Maven multi-module, Spring Boot fundamentals, dependency injection, auto-configuration, configuration as environment variables, the `common` module | written |
| 2 | [Persistence](2-persistence.md) | JPA and Hibernate, `open-in-view`, Flyway, `ddl-auto: validate`, the data model table by table, entities, repositories, ID generation | written |
| 3 | [The HTTP layer](3-the-http-layer.md) | Controllers, DTO/entity separation, Bean Validation, the shared error model and global exception handler, CORS | written |
| 4 | [The four domains](4-the-four-domains.md) | Order, inventory, payment and fulfillment business logic; all-or-nothing reservation; optimistic locking; the payment simulator; the temporary synchronous wiring Chapter 3 deletes | written |
| 5 | [Testing](5-testing.md) | Testcontainers, the singleton-container pattern, `@DynamicPropertySource`, what is worth testing here, and the frontend-testing gap | written |
| 6 | [The first frontend](6-the-first-frontend.md) | Vite, React, TypeScript, TanStack Query, the API wrapper, why polling, and what React Router later replaces | written |

---

## What "modular monolith" means here

The service boundaries from [Chapter 1](../01-design-contract/1-boundaries-and-ownership.md) are
real from the first line of code. Four packages, four database schemas, four migration histories, and
a rule that no package touches another's entities or tables.

What is *not* real yet is process separation. Everything runs in one JVM, which means:

- one `mvn spring-boot:run` starts the whole system,
- a debugger can step from `POST /api/orders` all the way to shipment creation,
- and a failure has one stack trace rather than four logs to correlate.

[Chapter 3](../03-kafka-and-services/README.md) puts Kafka between the packages and then pulls them into
separate processes. Because the boundaries were respected from the start, that is a build-file and
wiring change rather than a redesign — which is exactly the payoff ADR-007 predicted.

> **Not yet — and this one is temporary by design.** The workflow in this chapter is wired
> synchronously: `OrderService` calls inventory, which calls payment, which calls fulfillment, and
> `POST /api/orders` returns the *final* status. That contradicts
> `docs/openapi/order-service.yaml`, which says the endpoint returns `PENDING` immediately. The real
> project shipped exactly this deviation and documented it as deliberate and temporary. It is
> scaffolding, not a bug, and [section 4](4-the-four-domains.md) marks precisely which code Chapter 3
> deletes.

---

## Build it yourself

Ordered. Each step is buildable and testable before the next.

**Skeleton** — [section 1](1-project-skeleton.md)

1. Root `pom.xml`: `packaging=pom`, parent `spring-boot-starter-parent`, `java.version=21`, two
   modules (`services/common`, and one application module), and a `dependencyManagement` import of
   the Testcontainers BOM.
2. `services/common`: `packaging=jar`, **no** `spring-boot-maven-plugin`.
3. The application module: `spring-boot-starter-web`, `-data-jpa`, `-validation`, the PostgreSQL
   driver, `flyway-core` + `flyway-database-postgresql`, `spring-boot-starter-test`,
   `testcontainers:junit-jupiter`, `testcontainers:postgresql`, and the boot plugin.
4. `@SpringBootApplication` class at `com.orderfulfillment`, with `order/`, `inventory/`, `payment/`,
   `fulfillment/` beneath it.
5. `application.yml`: `spring.application.name`, datasource, `jpa.open-in-view: false`,
   `jpa.hibernate.ddl-auto: validate`, `server.port`. Every environment-varying value as
   `${VAR:local-default}`.
6. PostgreSQL in Docker, and the four schemas created.

**Persistence** — [section 2](2-persistence.md)

7. Four `db/migration` directories, one per domain, each starting at `V1__`. Write the DDL from
   [section 2](2-persistence.md): `numeric(10,2)` for money, `timestamptz` for time, `CHECK`
   constraints, `UNIQUE (order_id, sku)`, `version bigint` on `inventory_items`, and
   `source_event_id uuid NULL` on `order_status_history`.
8. `V2__seed_data.sql` for the four SKUs. Keep the quantities — they are what the scenarios need.
9. A startup component running one `Flyway` instance per schema. It is deleted in Chapter 3.
10. Entities: `@Enumerated(EnumType.STRING)` on every enum, `protected` no-arg constructors, setters
    only where mutation is legitimate, `@Version` on `InventoryItemEntity`.
11. Repositories as `JpaRepository` interfaces with derived query methods.
12. `V3__*_id_sequence.sql` per schema, and `IdGenerator` in `common` reading them via `JdbcClient`.

**HTTP** — [section 3](3-the-http-layer.md)

13. In `common`: `ApiError`, `ApiException` + `NotFoundException` / `ValidationApiException` /
    `ConflictException`, and `GlobalExceptionHandler` with **all six** handlers — including the 404
    and 405 cases.
14. DTO records per domain in a `dto` subpackage. Never return an entity.
15. Controllers under `/api`. `@Valid` on request bodies, `201 Created` + `Location` from
    `POST /api/orders`.
16. Bean Validation on request records, with `@Valid` on nested collections and an upper bound on
    every string and list.
17. `WebConfig` with CORS driven by `${APP_CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:*}`.

**Domains** — [section 4](4-the-four-domains.md)

18. `SkuPriceCatalog` and server-side pricing. The client never sends a price.
19. `InventoryReservationExecutor`: sum per SKU **first**, check every line, write only if every line
    passes, collect all shortages. Then `release`, filtering on `status = RESERVED`.
20. `PaymentBehaviorStore` (an `AtomicReference`) and the three-mode simulator. No flag on the
    business request.
21. `FulfillmentService.createShipment`, with `shipments.order_id UNIQUE`.
22. The temporary `SynchronousOrderWorkflow`. Mark it as scaffolding in a comment.

**Tests** — [section 5](5-testing.md)

23. `AbstractIntegrationTest`: a **singleton** `PostgreSQLContainer` in a static initializer (not
    `@Container`), `@DynamicPropertySource`, `RANDOM_PORT`, a real HTTP client, and an injected
    `JdbcClient`.
24. Tests for: the happy path, out of stock, payment rejection, duplicate SKUs rejected, unknown SKU
    rejected, an unmapped route returning 404, and concurrent reservations holding
    `reserved ≤ available` — with a conflict counter proving the race actually happened.

**Frontend** — [section 6](6-the-first-frontend.md)

25. `npm create vite@latest -- --template react-ts`, then TanStack Query.
26. `apiFetch` that **throws on `!response.ok`** with a typed `ApiRequestError` carrying the server's
    `ApiError` body. Base URLs from `import.meta.env.VITE_*`.
27. TypeScript types mirroring the OpenAPI spec, with `OrderStatus` as a string-literal union.
28. Three pages taking callback props, a `useState` view switch in `App.tsx`, `useQuery` with
    `refetchInterval: 4000` for the list, and `useMutation` + `invalidateQueries` for create.

**Done when:** a fresh clone, one `docker run` for PostgreSQL, `mvn spring-boot:run`, and `npm run
dev` let you place an order in the browser and watch it reach `FULFILLED`; out-of-stock and
payment-rejection paths both work; and `mvn test` passes from empty.

---

## Next

[Section 1 — The project skeleton](1-project-skeleton.md).


# 2.1 — The project skeleton

[← Chapter 2](README.md) · [Next: Persistence →](2-persistence.md)

A Maven build, a Spring Boot application, and a configuration discipline that costs nothing now and
saves a rewrite in [Chapter 7](../07-containers-and-kubernetes/README.md).

---

## The problem

You will *end up* with five Spring Boot applications and one library they all use. You do not start
there — [Chapter 1](../01-design-contract/4-sequencing-and-deferrals.md) explains why the sequence is
monolith first, Kafka second, separate services third. But three constraints apply from the first
commit:

- Each service must eventually **build and run independently**.
- They share real code — the event envelope, the error model, the idempotency ledger. Copying it five
  times means five divergent copies within a month.
- It is **one git repository**. Not five, not submodules. That is a pinned project decision.

Maven's multi-module build answers all three: an aggregator POM listing modules, a shared parent
pinning versions, and modules depending on each other by ordinary coordinates.

> **Primer — [Maven multi-module builds](../technology/maven/multi-module-builds.md)**
> Aggregator POMs, `<modules>` vs `dependencyManagement`, BOM imports, `relativePath`, and why a
> library module must not carry `spring-boot-maven-plugin`.

## This project's root POM

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
</parent>

<groupId>com.orderfulfillment</groupId>
<artifactId>order-fulfillment-systems-lab</artifactId>
<version>0.1.0</version>
<packaging>pom</packaging>

<properties>
    <java.version>21</java.version>
</properties>

<modules>
    <module>services/common</module>
    <module>services/order-service</module>
    ...
</modules>
```

That is the *finished* module list. At this point in the build there are two:

```xml
<modules>
    <module>services/common</module>
    <module>services/fulfillment-lab</module>   <!-- the modular monolith -->
</modules>
```

[Chapter 3](../03-kafka-and-services/README.md) replaces the second entry with four service modules. The
aggregator, the parent, the version management, and the `common` module are unchanged by that — which
is the point of setting them up properly now.

One `dependencyManagement` entry is added on top of Spring's, and its comment is worth reading in
full because it records a real Maven wrinkle:

```xml
<!-- Every service's own pom also declares this dependency directly (Maven does not
     propagate dependencyManagement's "import" entries transitively through more than
     one parent hop reliably across all tooling), but centralizing the version here
     keeps the four services' Testcontainers versions from drifting independently. -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.21.4</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

## What the service POM tells you

A service's dependency list is a readable statement of what the application *is*, because Spring Boot
configures whatever it finds on the classpath:

```xml
<dependency>...common</dependency>                           <!-- our shared library -->
<dependency>...spring-boot-starter-web</dependency>          <!-- HTTP + JSON + embedded server -->
<dependency>...spring-boot-starter-data-jpa</dependency>     <!-- JPA/Hibernate + transactions -->
<dependency>...spring-boot-starter-validation</dependency>   <!-- Bean Validation -->
<dependency>...postgresql (runtime)</dependency>
<dependency>...flyway-core, flyway-database-postgresql</dependency>
```

> **Primer — [Auto-configuration and component scanning](../technology/spring/auto-configuration.md)**
> How starters and conditional auto-configuration work, the `--debug` condition report, property
> precedence and relaxed binding, and the package trap that makes a shared module's beans invisible.

The real POM also carries `spring-boot-starter-kafka`, `spring-boot-starter-actuator`, and
`micrometer-registry-prometheus`. Those belong to Chapters 3 and 8.

## Spring, in one paragraph

Classes declare what they need in their constructor and never construct it; Spring builds the object
graph at startup. `OrderService` takes six collaborators and nothing anywhere calls
`new OrderService(...)`. Note the absence of `@Autowired` — a class with a single constructor needs
none.

```java
@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final SkuPriceCatalog priceCatalog;
    // ...
    public OrderService(OrderRepository orderRepository, SkuPriceCatalog priceCatalog, /* … */) {
```

> **Primer — [Dependency injection and stereotypes](../technology/spring/dependency-injection.md)**
> Why constructor injection rather than field injection, what `@Service`/`@Repository`/`@Component`
> actually differ in, `@Bean` methods, singleton scope and the thread-safety consequence, and how to
> read the common startup failures.

---

## Configuration, and a habit worth forming now

Two lines from `application.yml`, both worth copying as habits.

```yaml
spring:
  application:
    name: order-service
```

`spring.application.name` looks cosmetic. It is not — it becomes the `service.name` field in every
structured log line ([Chapter 8](../08-observability-and-scaling/README.md)) and the identity a metrics
scrape is labelled with. Set it on day one.

```yaml
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
```

`${VAR:default}` reads an environment variable and falls back if unset. This is the habit ADR-007
tells you to form from Phase 1 even though nothing verifies it until Phase 7:

> Nothing before Phase 8 proves the services are container-friendly. Configuration must come from
> environment variables and nothing may depend on local filesystem state, or Phase 7 turns into a
> refactor.

Anything that differs between your laptop, Compose, and Kubernetes — broker addresses, database URLs,
allowed CORS origins — gets `${VAR:sensible-local-default}` from the beginning. One line each, and it
is the difference between containerization being a packaging task and a rewrite.

> **Not yet.** The `kafka`, `outbox`, `retention`, `management`, and `logging.structured` blocks in
> the real `application.yml` do not exist yet. Chapters 3, 6, 4, and 8 add them respectively. What
> exists now is `application`, `datasource`, `jpa`, `flyway`, and `server.port`.

---

## The `common` module

A plain library — no `@SpringBootApplication`, no `main`, nothing to run, and deliberately **no**
`spring-boot-maven-plugin` (a fat JAR cannot be depended on as an ordinary library).

What belongs in it is worth being strict about, because a shared module is the easiest place in a
system to accidentally recreate the coupling that service boundaries exist to prevent.

**In:** the event envelope and payload records, the Kafka codec and publisher, topic and event-type
constants, the idempotency ledger, the error model and exception handler, correlation-ID plumbing, ID
generation. Each is either *a frozen contract expressed as code* or *infrastructure with no domain
opinion*.

**Out:** anything domain-specific. There is no shared `Order` class. Order Service's `OrderEntity` and
the `OrderCreatedPayload` in `common` are separate types describing related things, and that
separation is deliberate — the payload is a wire contract, the entity is private storage, and they
are free to diverge.

At this point `common` holds only the error model, correlation-ID plumbing, and `IdGenerator`. The
Kafka half arrives in [Chapter 3](../03-kafka-and-services/README.md) and [Chapter 4](../04-reliability/README.md).

---

## The shape you're aiming at

```
pom.xml                          aggregator, packaging=pom, parent=spring-boot-starter-parent
services/
├── common/pom.xml               packaging=jar, no main class, no boot plugin
│   └── src/main/java/com/orderfulfillment/common/
│       ├── ApiError.java  ApiException.java  GlobalExceptionHandler.java  …
│       └── IdGenerator.java
└── fulfillment-lab/pom.xml      packaging=jar, depends on common, spring-boot-maven-plugin
    ├── src/main/java/com/orderfulfillment/
    │   ├── FulfillmentLabApplication.java
    │   ├── order/          ┐
    │   ├── inventory/      │ four domain packages, one process
    │   ├── payment/        │
    │   └── fulfillment/    ┘
    └── src/main/resources/
        ├── application.yml
        └── db/migration/   V1__orders.sql, V1__inventory.sql, …
```

**Four packages, one application.** The boundaries from
[Chapter 1](../01-design-contract/1-boundaries-and-ownership.md) are real — separate packages,
separate schemas, separate migration files, and a rule that no package reaches into another's
entities. They are just not yet separate *processes*. That is exactly what "modular monolith" means:
the seams are drawn and respected, but everything runs in one JVM where a debugger can step across
the whole workflow.

The entry point is three lines:

```java
@SpringBootApplication
public class FulfillmentLabApplication {
    public static void main(String[] args) { SpringApplication.run(FulfillmentLabApplication.class, args); }
}
```

It sits at `com.orderfulfillment`, above all four domain packages — and above `common`, which is why
`common`'s beans are found by component scanning *now* and will need explicit help after the
Chapter 3 split moves each application class down into its own domain package.

> **A note on naming.** The real repository has no `fulfillment-lab` module — Phase 1's monolith was
> dissolved in Phase 3 and its packages became `services/order-service`,
> `services/inventory-service`, and so on. The name is this guide's, for a module that exists only
> until [Chapter 3](../03-kafka-and-services/README.md). Every *class* named from here on is real and keeps
> its name through the split; only its module changes.

---

[← Chapter 2](README.md) · [Next: Persistence →](2-persistence.md)


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


# 2.3 — The HTTP layer

[← Persistence](2-persistence.md) · [Next: The four domains →](4-the-four-domains.md)

Controllers, the DTOs they speak in, the validation that rejects bad input, and one error model
shared by all five services.

---

## Controllers

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderAccepted> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderAccepted accepted = orderService.createOrder(request);
        log.info("Order {} created", accepted.id());
        return ResponseEntity.created(URI.create("/api/orders/" + accepted.id())).body(accepted);
    }

    @GetMapping
    public OrderPage listOrders(@RequestParam(required = false) String status,
                                 @RequestParam(required = false) String customerId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return orderService.listOrders(status, customerId, page, size);
    }

    @GetMapping("/{orderId}")
    public OrderDetail getOrder(@PathVariable String orderId) {
        return orderService.getOrder(orderId);
    }
}
```

> **Primer — [Spring MVC controllers and exception handling](../technology/spring/web-mvc.md)**
> The annotation set, path-matching precedence, return types and status codes, `@RestControllerAdvice`,
> the four exceptions most projects mishandle, and the case for a `void` exception handler.

Three things about *this* controller are worth pulling out.

**The path starts with `/api`,** which is [ADR-002](../01-design-contract/3-state-and-api-contracts.md)
made physical. Demo endpoints live in an entirely separate controller class under `/demo`, in every
service that has them.

**It contains no business logic.** It binds input, calls one service method, and shapes the response.
Every rule — pricing, duplicate SKUs, what a valid status filter is — lives in `OrderService`. That is
not tidiness: logic in a controller is reachable only from an HTTP request, so it could not be reused
by the Kafka consumers of [Chapter 3](../03-kafka-and-services/README.md).

**`201 Created`, not `200 OK`.** For this API that is more than protocol correctness — it is the
honest status code. `POST /api/orders` does not return the outcome of the order; it returns that the
order now exists, in `PENDING`, and that everything interesting happens later. `200 OK` would suggest
the work is done. `201` plus a `Location` header says what actually happened, and matches the OpenAPI
spec's asynchrony note word for word.

---

## DTOs

`CreateOrderRequest` in; `OrderAccepted`, `OrderDetail`, and `OrderPage` out. All Java records, all in
a `dto` package, none of them ever a JPA entity.

This is a recurring pattern with a page of its own:

> **Pattern — [DTO / entity separation](../patterns/dto-entity-separation.md)**
> Why entities are never returned from a controller, the five things that go wrong when they are, why
> the mapping is hand-written rather than reflective, and where else it applies.

Read it now — the rest of the guide assumes it.

---

## Validation

```java
public record CreateOrderRequest(
        @NotNull @Size(min = 1, max = 64) String customerId,
        @NotEmpty @Size(min = 1, max = 20) @Valid List<CreateOrderItem> items
) { }

public record CreateOrderItem(
        @NotNull @Pattern(regexp = "^SKU-[0-9]{3}$") String sku,
        @NotNull @Min(1) @Max(100) Integer quantity
) { }
```

> **Primer — [Bean Validation](../technology/spring/bean-validation.md)**
> The constraint vocabulary, why `@Valid` on a collection is what makes it recurse, why boxed types
> rather than primitives, bounding untrusted input, and validating outside controllers.

### Where validation is *not*

Two checks in `OrderService` that no annotation could express:

```java
private void validateNoDuplicateSkus(List<CreateOrderItem> items) {
    long distinctCount = items.stream().map(CreateOrderItem::sku).distinct().count();
    if (distinctCount != items.size()) {
        throw new ValidationApiException("INVALID_ORDER", "A SKU may appear at most once per order");
    }
}
```

```java
BigDecimal price = priceCatalog.priceFor(item.sku());
if (price == null) {
    throw new ValidationApiException("UNKNOWN_SKU", "No price known for SKU " + item.sku());
}
```

The first is a cross-field invariant; the second needs domain data. Both are **business rules that
happen to produce a 400**, and both live where the rule lives, throwing a shared exception type the
HTTP layer knows how to render.

Note the division of labour on duplicates: `@Pattern` checks a SKU is *shaped* like a SKU;
`priceCatalog` checks it *exists*; `UNIQUE (order_id, sku)` in the schema guarantees uniqueness; and
`validateNoDuplicateSkus` exists purely so the client gets a readable message instead of a constraint
violation. Four layers, each doing a different job.

---

## The error model

Errors reach a client from places that know nothing about each other — your code throwing
deliberately, the validation layer, Jackson failing to parse, the dispatcher finding no handler, and
anything unexpected. Left alone each produces a differently-shaped response, and some leak stack
traces. One envelope, defined once in `common`, is shared by all five services:

```java
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        UUID correlationId
) { }
```

Two fields carry more weight than they look.

**`code`** is a *stable machine-readable* string — `ORDER_NOT_FOUND`, `UNKNOWN_SKU`,
`VALIDATION_ERROR`. `message` is for humans and may be reworded freely; `code` is part of the contract
and clients may branch on it. Having both means never choosing between a good error message and a
parseable one.

**`correlationId`** puts the request's trace identifier **in the error body**. When someone reports a
failure, the response they are looking at contains the exact value that finds every log line across
every service for that operation. Highest value per line in the whole error model, and it costs one
field.

### The exception hierarchy

```java
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
}

public class NotFoundException      extends ApiException { /* 404 */ }
public class ValidationApiException extends ApiException { /* 400 */ }
public class ConflictException      extends ApiException { /* 409 */ }
```

The status lives **on the exception**, so the throw site decides the outcome and no handler needs a
mapping table:

```java
orderRepository.findById(orderId)
        .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "No order with id " + orderId));
```

Unchecked on purpose — these propagate through layers that have nothing useful to do with them, and
checked exceptions would force every one of those layers to declare or wrap them.

### The handler

One `@RestControllerAdvice` in `common`, applying to every controller in every service. Build all of
these from the start:

| Handler for | Produces | Why it must exist |
|---|---|---|
| `ApiException` | its own status + code | The deliberate case |
| `MethodArgumentNotValidException` | 400 `VALIDATION_ERROR` | What `@Valid` throws |
| `HttpMessageNotReadableException` | 400 `VALIDATION_ERROR` | Malformed JSON body |
| `NoResourceFoundException` | **404** `NOT_FOUND` | Otherwise a nonexistent URL is a **500** |
| `HttpRequestMethodNotSupportedException` | **405** | Otherwise a wrong-method request is a **500** |
| `Exception` | 500 `INTERNAL_ERROR` | Catch-all |

The validation handler reports only the **first** field error — a deliberate simplification worth
knowing you made, since a form-driven client generally wants all of them at once.

> **We got this wrong.** The 404 and 405 cases were missing for most of this project's life. Both fell
> through to the catch-all, were reported as 500s, *and were logged at `ERROR`* — turning ordinary
> client mistakes into noise in the logs you would use to find real failures. Found during Sprint 2's
> bug hunt by making the requests, not by reading the code.
> [Chapter 10](../10-retrospective/README.md).

The catch-all has two halves and both are required:

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request);
}
```

**Tell the client nothing** beyond "unexpected server error" — no type, no message, no stack trace,
because those describe your internals. **Log everything**, at `ERROR`, with the exception attached.
The code comment records what happens when you get the second half wrong:

> this handler previously discarded the exception entirely — a 500 left zero trace anywhere, in any
> service's logs, which defeats the whole point of correlation-id tracing.

A 500 that appears in no log is the worst possible outcome: the client knows something broke and you
have no way to find out what.

> **Not yet.** The real handler has a seventh case, `AsyncRequestNotUsableException`, which exists
> only because of SSE. There is no SSE until [Chapter 5](../05-scenarios-and-frontend/README.md), where it is
> built — and [Chapter 10](../10-retrospective/README.md) has the story, because it was a live bug on the
> deployed demo.

---

## CORS

The frontend runs on a different origin from the services (`localhost:5173` against `localhost:8081`),
so the services must opt in to being read cross-origin. `WebConfig` does this, driven by one
configuration value:

```yaml
app:
  cors:
    allowed-origin-patterns: ${APP_CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:*}
```

Configured rather than hard-coded — the same discipline from
[section 1](1-project-skeleton.md) — which is what lets [Chapter 9](../09-production/README.md) point the
deployed frontend at a real hostname with no code change. The comment adds the rule that matters:
*"Never `*`, never combined with credentials."*

> **Primer — [CORS](../technology/http/cors.md)**
> What the same-origin policy actually protects, preflight requests, why "it works in `curl`" proves
> nothing, the Actuator handler-mapping trap, and when a reverse proxy is the better answer.

---

[← Persistence](2-persistence.md) · [Next: The four domains →](4-the-four-domains.md)


# 2.4 — The four domains

[← The HTTP layer](3-the-http-layer.md) · [Next: Testing →](5-testing.md)

The actual business logic: what each of the four domains does, and the temporary wiring that makes
them into a workflow before Kafka exists.

---

## Order Service — accepting an order

```java
public OrderAccepted createOrder(CreateOrderRequest request) {
    validateNoDuplicateSkus(request.items());
    List<OrderItemEntity> priced = priceItems(request.items());

    BigDecimal totalAmount = BigDecimal.ZERO;
    for (OrderItemEntity item : priced) {
        totalAmount = totalAmount.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
    }

    String orderId = idGenerator.nextOrderId();
    List<OrderItemEntity> associated = priced.stream()
            .map(i -> new OrderItemEntity(orderId, i.getSku(), i.getQuantity(), i.getUnitPrice()))
            .toList();

    OrderEntity order = persistence.createPendingOrder(orderId, request.customerId(), associated, totalAmount);

    return new OrderAccepted(orderId, order.getStatus().name(), order.getCreatedAt());
}
```

**Pricing happens server-side.** The client sends SKUs and quantities; it never sends a price. That is
the single most important line of defence in any commerce-shaped API, and it is why `CreateOrderItem`
has exactly two fields.

Prices come from `SkuPriceCatalog`, a hard-coded map of four entries:

```java
private static final Map<String, BigDecimal> PRICES = Map.of(
        "SKU-001", new BigDecimal("129.00"),
        "SKU-002", new BigDecimal("189.00"),
        "SKU-003", new BigDecimal("14.50"),
        "SKU-004", new BigDecimal("249.00"));
```

Its Javadoc records the boundary cost honestly:

> Order Service's static seeded SKU → price map (`docs/db-ownership.md`, "Where prices come from").
> Inventory Service holds stock/display_name only; no price column exists there. This is the
> project's only product catalog.

**Product data is split across two services** — `display_name` in Inventory, `unit_price` in Order —
because there is no Product Service, and the project's scope rules one out. In a real system this is
where you would say "we need a catalog service." Here it is a documented consequence of a deliberate
scope decision, which is a much better answer than pretending it isn't a seam.

Note also `new BigDecimal("129.00")` — constructed from a **string**. `new BigDecimal(129.00)` would
faithfully preserve the floating-point representation error you chose `BigDecimal` to avoid.

Two other things about this method. **The total is computed, never accepted.** And the whole thing is
a single call into `OrderPersistence.createPendingOrder`, which does the order row, the item rows, and
the first status-history row in one transaction — a boundary that matters more once
[Chapter 6](../06-outbox/README.md) adds a fourth write to it.

---

## Inventory Service — reserving stock

The most interesting logic in the project, because it is the only place where two callers genuinely
compete.

### The algorithm

`InventoryReservationExecutor.attemptReserve` is **all-or-nothing across every line of an order**:
either every line is reserved, or nothing is written and the order is rejected.

```java
// 1. Sum quantities per SKU, before checking anything
Map<String, Integer> requested = new LinkedHashMap<>();
for (OrderLine line : lines) {
    requested.merge(line.sku(), line.quantity(), Integer::sum);
}

// 2. Check every SKU against free stock, collecting shortages
List<Shortage> shortages = new ArrayList<>();
boolean anyUnknownSku = false;
Map<String, InventoryItemEntity> resolved = new LinkedHashMap<>();

for (Map.Entry<String, Integer> entry : requested.entrySet()) {
    InventoryItemEntity item = itemRepository.findById(entry.getKey()).orElse(null);
    if (item == null) {
        shortages.add(new Shortage(entry.getKey(), entry.getValue(), 0));
        anyUnknownSku = true;
        continue;
    }
    resolved.put(entry.getKey(), item);
    if (item.freeQuantity() < entry.getValue()) {
        shortages.add(new Shortage(entry.getKey(), entry.getValue(), item.freeQuantity()));
    }
}

// 3. Any shortage at all → reserve nothing
if (!shortages.isEmpty()) {
    String reason = anyUnknownSku ? "UNKNOWN_SKU" : "INSUFFICIENT_STOCK";
    return ReservationResult.failed(reason, shortages);
}

// 4. Otherwise apply every line
String reservationId = idGenerator.nextReservationId();
Instant now = Instant.now();
for (Map.Entry<String, Integer> entry : requested.entrySet()) {
    InventoryItemEntity item = resolved.get(entry.getKey());
    item.setReservedQuantity(item.getReservedQuantity() + entry.getValue());
    item.setUpdatedAt(now);
    reservationRepository.save(new InventoryReservationEntity(
            reservationId + "-" + entry.getKey(), orderId, entry.getKey(),
            entry.getValue(), ReservationStatus.RESERVED, now));
}
return ReservationResult.reserved(reservationId);
```

Four details are load-bearing.

**Check every line before writing any.** Steps 2 and 3 are fully separated from step 4. Reserving
line by line and stopping on the first shortage would leave partial reservations behind that nothing
in the system ever releases — quietly leaking stock on every out-of-stock order.

**Collect every shortage, not just the first.** The failure carries a list of
`(sku, requested, available)`, which is what lets the UI say exactly what was short by how much
rather than "unavailable."

**Reserving increments `reserved_quantity`; it does not decrement `available_quantity`.** Free stock
is the difference. This is what makes releasing distinguishable from restocking
([section 2](2-persistence.md)).

**Sum per SKU first — step 1.** This one is a bug fix, and the comment explains it precisely:

> An order carrying the same SKU on two lines used to be checked line-by-line against the
> *unmutated* free quantity, so 2 + 2 against a stock of 2 passed both checks and then applied both
> increments — reserving 4 of 2. It also collapsed to a single reservation row [...] so the release
> path would have handed back only half of what was taken, leaking stock permanently.

Two independent failures from one omission: an oversell, *and* a permanent stock leak on the
compensation path. Summing first makes the check and the write agree, and matches what
`UNIQUE (order_id, sku)` in the schema already assumed.

> **We got this wrong.** Build the summing from the start. [Chapter 10](../10-retrospective/README.md) has
> the story. Note that Order Service's own `validateNoDuplicateSkus` makes this unreachable *through
> the API* — but the executor has other callers, and a domain method should not depend on a
> validation in a different service for its correctness.

### The compensation path

`release(orderId)` is the opposite operation, and the only thing that ever gives stock back:

```java
List<InventoryReservationEntity> reservations =
        reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED);
if (reservations.isEmpty()) {
    return ReleaseResult.NONE;
}
for (InventoryReservationEntity reservation : reservations) {
    InventoryItemEntity item = itemRepository.findById(reservation.getSku()).orElseThrow();
    item.setReservedQuantity(item.getReservedQuantity() - reservation.getQuantity());
    item.setUpdatedAt(now);
    reservation.setStatus(ReservationStatus.RELEASED);
    reservation.setUpdatedAt(now);
}
```

Note that it filters on `status = RESERVED` and marks each row `RELEASED`. That status transition is
what makes the operation naturally near-idempotent: a second release finds no `RESERVED` rows and
returns `NONE`. Not a substitute for real idempotency ([Chapter 4](../04-reliability/README.md)), but the
right shape — and it matters more here than anywhere else in the system, because *a second release
would hand the same units back to stock again, inventing inventory out of nothing.*

### Concurrency: optimistic locking

Two orders want the last two `SKU-004`s. Both read `freeQuantity() == 2`, both decide they can
proceed, both write. Stock is oversold.

This is a **check-then-act race**, and it is not solved by a transaction alone: PostgreSQL's default
`READ COMMITTED` isolation lets both transactions read the same value and both commit.

Two families of answer:

- **Pessimistic** — take a lock when you read (`SELECT … FOR UPDATE`), so the second reader waits.
  Correct, and it serializes every reader of that row, including ones that were never going to
  conflict.
- **Optimistic** — don't lock. Read a version number with the row, and at write time update only if
  the version is unchanged. If someone got there first, the update matches zero rows and you are told.

Optimistic is right when conflicts are rare, which is the normal case for inventory. JPA implements
it with one annotation:

```java
@Version
private long version;
```

Hibernate then adds the version to every `UPDATE`'s `WHERE` clause and increments it:

```sql
UPDATE inventory_items SET reserved_quantity = ?, version = 6 WHERE sku = ? AND version = 5
```

Zero rows affected means someone else committed first, and Hibernate raises
`ObjectOptimisticLockingFailureException`. **A conflict is not a corruption — it is a detection.**
Nothing was oversold; you were simply told your read is stale.

At this point in the build, the honest response is to let it surface as a `409 Conflict` to the
caller, which is a legitimate answer to "two people tried to buy the last one." The caller retries or
gives up.

> **Not yet.** Once the caller is a Kafka consumer rather than an HTTP client
> ([Chapter 3](../03-kafka-and-services/README.md)), there is nobody to hand a 409 to — the consumer must
> resolve it itself. [Chapter 4](../04-reliability/README.md) adds the retry loop, the randomized backoff,
> the 25-attempt budget and the reasoning for why that loop is guaranteed to terminate. This is the
> project's single highest-scrutiny piece of code and it deserves its own treatment rather than a
> footnote here.

---

## Payment Service — a deterministic simulator

No real provider, no card data, no money. `docs/planning/project-overview.md` rules all three out
explicitly, and the ADRs are careful never to imply otherwise.

What the simulator needs to be is **deterministic and externally controllable**, because Scenario 3
has to reject a payment on demand and get the same result every time.

```java
return switch (behavior.mode()) {
    case DEFAULT_SUCCESS -> {
        repository.save(new PaymentAttemptEntity(attemptId, orderId, idempotencyKey,
                PaymentAttemptStatus.AUTHORIZED, amount, null, now));
        yield PaymentOutcome.authorized(attemptId);
    }
    case REJECT -> {
        PaymentFailureReason reason = behavior.failureReason() != null
                ? PaymentFailureReason.valueOf(behavior.failureReason())
                : PaymentFailureReason.CARD_DECLINED;
        repository.save(new PaymentAttemptEntity(attemptId, orderId, idempotencyKey,
                PaymentAttemptStatus.REJECTED, amount, reason, now));
        yield PaymentOutcome.rejected(attemptId, reason);
    }
    case RETRYABLE_ERROR -> {
        repository.save(new PaymentAttemptEntity(attemptId, orderId, idempotencyKey,
                PaymentAttemptStatus.PENDING, amount, null, now));
        yield PaymentOutcome.providerError(attemptId);
    }
};
```

Three modes, and the third is the interesting one. `DEFAULT_SUCCESS` and `REJECT` are the two business
outcomes. **`RETRYABLE_ERROR` is a different category entirely** — not "the payment was declined" but
"we could not find out whether it was declined." That distinction drives everything in
[Chapter 4](../04-reliability/README.md): a decline is a final answer and produces an event; a provider
error is a transient failure and should be retried.

The behavior itself lives outside the request, in `PaymentBehaviorStore`:

```java
@Component
public class PaymentBehaviorStore {
    private final AtomicReference<PaymentBehaviorDto> current =
            new AtomicReference<>(PaymentBehaviorDto.defaultSuccess());

    public PaymentBehaviorDto resolveFor(String orderId) {
        PaymentBehaviorDto behavior = current.get();
        if (behavior.orderId() != null && !behavior.orderId().equals(orderId)) {
            return PaymentBehaviorDto.defaultSuccess();
        }
        return behavior;
    }
}
```

This is [ADR-002](../01-design-contract/3-state-and-api-contracts.md) in its purest form. The order
request has no `forcePaymentFailure` flag; instead the *environment* is configured through
`PUT /demo/payment-behavior`, and the business path reads it without knowing why it is set. **Failure
injection as a property of the environment, not of the request** — which is also how real operational
failures arrive.

`AtomicReference` because this is a singleton bean read concurrently by every request thread
(see the [DI primer](../technology/spring/dependency-injection.md) on singleton scope). And in-memory
on purpose: the OpenAPI spec specifies that the override does not survive a restart.

`resolveFor` supports an order-scoped override falling back to a global one. That partial scoping is
the ADR's acknowledged *"demo-only wart"* — the override is armed *before* its target order exists, so
for the duration of a run it is un-scoped, and any order created meanwhile is affected.

---

## Fulfillment Service — the terminal step

The simplest domain:

```java
String shipmentId = idGenerator.nextShipmentId();
String trackingNumber = "TRK-" + String.format("%09d", Math.abs(shipmentId.hashCode()) % 1_000_000_000);
ShipmentEntity shipment = new ShipmentEntity(shipmentId, orderId, "CREATED", trackingNumber, Instant.now());
repository.save(shipment);
```

No carrier integration; tracking numbers are generated locally and mean nothing. `shipments.order_id`
carries a `UNIQUE` constraint — one shipment per order — which
[Chapter 4](../04-reliability/README.md) later leans on as a defence-in-depth backstop behind real
idempotency.

---

## The temporary wiring

You now have four domains and no workflow. Something has to call them in order.

```java
// TEMPORARY — deleted in Chapter 3.
@Service
public class SynchronousOrderWorkflow {

    @Transactional
    public OrderDetail process(CreateOrderRequest request) {
        String orderId = orderService.createOrder(request).id();
        OrderDetail order = orderService.getOrder(orderId);

        ReservationResult reservation = inventoryService.reserve(orderId, toLines(request.items()));
        if (reservation.failed()) {
            persistence.appendStatus(orderId, OrderStatus.REJECTED_OUT_OF_STOCK, null);
            return orderService.getOrder(orderId);
        }
        persistence.appendStatus(orderId, OrderStatus.INVENTORY_RESERVED, null);
        persistence.appendStatus(orderId, OrderStatus.PAYMENT_PENDING, null);

        PaymentOutcome payment = paymentService.authorize(orderId, order.totalAmount(), UUID.randomUUID());
        if (payment.rejected()) {
            persistence.appendStatus(orderId, OrderStatus.PAYMENT_FAILED, null);
            inventoryService.release(orderId);                 // compensation
            return orderService.getOrder(orderId);
        }
        persistence.appendStatus(orderId, OrderStatus.PAID, null);
        persistence.appendStatus(orderId, OrderStatus.FULFILLMENT_PENDING, null);

        fulfillmentService.createShipment(orderId);
        persistence.appendStatus(orderId, OrderStatus.FULFILLED, null);
        return orderService.getOrder(orderId);
    }
}
```

**This class does not exist in the repository and is not meant to survive.** It is scaffolding, and it
is worth writing anyway, because reading it teaches three things that the distributed version hides.

**The whole workflow is visible in one place.** Every transition from
[Chapter 1](../01-design-contract/3-state-and-api-contracts.md)'s table, in order, in twenty lines.
Screenshot it. Once Kafka arrives, this sequence exists nowhere — it becomes an emergent property of
four consumers, and `docs/architecture-diagram.md` is the closest thing to this listing that survives.

(`appendStatus` is the real `OrderPersistence` method, minus the `eventKey` parameter
[Chapter 4](../04-reliability/README.md) adds. `sourceEventId` is null throughout, which is correct — every
transition here is internal, caused by a line of code rather than an inbound event.)

**Compensation is explicit.** `inventoryService.release(orderId)` after a rejected payment is a
*compensating action*, not a rollback. Note that it sits inside a `@Transactional` method here, which
makes it look like it could be a rollback — and that illusion is exactly what
[Chapter 3](../03-kafka-and-services/README.md) destroys. Once the four domains have four databases and four
processes, there is no shared transaction to roll back, and the compensating step is all you have.

**`POST /api/orders` returns the terminal status.** Which contradicts
`docs/openapi/order-service.yaml`, and the real project shipped exactly this deviation, documenting it
as deliberate and temporary. It is the one place where this chapter is knowingly at odds with a frozen
contract, and it is resolved in the next chapter rather than papered over.

Two more things this wiring quietly relies on, both of which disappear:

- **One transaction across four domains.** Legal in a monolith, impossible afterwards.
- **Ordering for free.** `PAID` cannot arrive before `INVENTORY_RESERVED`, because one line of code
  runs after another. Kafka provides no such guarantee across topics — the subject of ADR-009 and
  [Chapter 4](../04-reliability/README.md).

> **What survives into Chapter 3.** All four domain services, unchanged, minus the parameters that do
> not exist yet. `InventoryService.reserve(orderId, lines)` becomes
> `reserve(orderId, lines, eventKey)`; `PaymentService.authorize(...)` and
> `FulfillmentService.createShipment(...)` gain the same. That is the payoff of keeping business logic
> out of controllers and out of this orchestrator: the domain code is called from a Kafka listener
> instead of a method, and does not otherwise change.

---

[← The HTTP layer](3-the-http-layer.md) · [Next: Testing →](5-testing.md)


# 2.5 — Testing

[← The four domains](4-the-four-domains.md) · [Next: The first frontend →](6-the-first-frontend.md)

Phase 1's exit criteria end with *"tests protect domain rules."* This section is about what that
means concretely, and about one library that changes the economics of integration testing entirely.

---

## The problem: what does a passing test prove?

The classic pyramid says many unit tests, fewer integration tests, fewest end-to-end. The reasoning is
economic — integration tests were historically slow, flaky, and needed infrastructure somebody had to
maintain.

That reasoning has a specific consequence people underrate. If your persistence layer is only ever
tested against mocks, then **every passing test is a statement about your mocks**. A repository test
with a mocked `EntityManager` proves your code calls the methods you think it calls. It cannot tell
you that:

- the entity mapping matches the migration,
- `@Version` actually produces the `WHERE version = ?` clause you are relying on,
- a `UNIQUE` constraint fires when you expect,
- `numeric(10,2)` round-trips your `BigDecimal` unchanged,
- your Flyway migrations apply cleanly in order from empty.

For a system whose interesting behavior *is* concurrency and persistence semantics, mocked tests
would test almost nothing that matters.

## The technology: Testcontainers

Testcontainers starts **real Docker containers** for the duration of a test run and hands you their
connection details. A real PostgreSQL. A real Kafka broker. Not an in-memory substitute, not a mock —
the same image you run in production.

That collapses the old trade-off. An integration test still costs seconds rather than milliseconds,
but it is no longer flaky, no longer dependent on shared infrastructure, and no longer a lie about
what it proves. The pyramid flattens: this project has **53 test classes, and the overwhelming
majority are integration tests**, because that is where the truth is.

---

## The base class

Every service has an `AbstractIntegrationTest`. Here is the shape, with the parts that matter:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("orderfulfillment")
                .withUsername("orderfulfillment")
                .withPassword("orderfulfillment");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    RestTestClient client;

    @BeforeEach
    void initClient() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }
}
```

Four decisions in there, each worth understanding.

### `@DynamicPropertySource` — the chicken-and-egg fix

Testcontainers assigns a **random host port** so parallel runs and local development do not collide.
But Spring needs `spring.datasource.url` when it builds the context, and the port is not known until
the container starts.

`@DynamicPropertySource` resolves this: it runs before the context is created and registers property
*suppliers* (note `POSTGRES::getJdbcUrl` — a method reference, not a value) that Spring calls at the
moment it needs them.

### A static initializer, not `@Container`

Testcontainers offers `@Testcontainers` + `@Container` annotations that manage lifecycle for you. This
project deliberately does not use them, and the comment explains why:

> that annotation pair restarts containers between test classes and reassigns ports, which can strand
> a cached Spring test context on a dead port. A singleton container started once in a static
> initializer (reaped by Testcontainers' Ryuk at JVM exit) avoids this.

This is a genuinely useful piece of hard-won knowledge. Spring's test framework **caches application
contexts** across test classes with identical configuration — a large performance win, since starting
a Spring context is the expensive part. But a cached context holds a connection pool pointed at the
port the container had *then*. Restart the container, get a new port, and the cached context is
pointing at nothing.

The **singleton container pattern** — one static container, started once, never stopped — matches the
context cache's lifetime. Cleanup is handled by **Ryuk**, a Testcontainers sidecar container that
reaps everything when the JVM exits, including after a crash or a killed test run.

### `RANDOM_PORT` and a real HTTP client

`WebEnvironment.RANDOM_PORT` starts a real embedded server on a random port, injected via
`@LocalServerPort`. Tests then make **real HTTP requests** through `RestTestClient`.

The alternative, `MockMvc`, exercises the Spring MVC stack without a server — faster, but it does not
test JSON serialization over the wire, does not test the servlet filter chain, and cannot test SSE at
all. Since [Chapter 5](../05-scenarios-and-frontend/README.md) needs SSE, the real-server choice pays for
itself.

### `JdbcClient` for assertions

```java
/** Direct reads of the processed_events ledger, which has no JPA entity by design. */
@Autowired
JdbcClient jdbcClient;
```

Asserting on tables that have no entity means plain SQL. This becomes essential from
[Chapter 4](../04-reliability/README.md) onward, where the most important assertions are about the ledger and
the outbox — infrastructure tables the application code deliberately does not map.

---

## What's worth testing at this stage

The tests that exist for Phase 1's behavior fall into four groups.

**Domain rules with real persistence.** `OrderServiceIntegrationTest` and
`InventoryServiceIntegrationTest`: create an order and check the total is computed from the catalog;
reserve stock and check the counters; reserve more than exists and check nothing was written.

**Concurrency, proven rather than asserted.** `InventoryServiceOptimisticLockTest` and
`InventoryConcurrencyIntegrationTest` fire genuinely simultaneous reservations at the same SKU and
assert the invariant `reserved ≤ available` holds.

There is a subtlety here worth stealing. A concurrency test that only asserts the invariant can pass
because *nothing ever raced* — the threads happened not to overlap, the invariant held trivially, and
you learned nothing. This project defends against that by counting conflicts:

```java
/**
 * Counts real {@code @Version} conflicts observed against the database. Exposed so
 * {@code InventoryConcurrencyIntegrationTest} can assert the conflict path was genuinely
 * exercised rather than assert an invariant that held only because nothing ever raced.
 */
private final AtomicLong optimisticLockConflicts = new AtomicLong();
```

**Assert that the dangerous path was taken, not just that the outcome was fine.** That principle
generalizes to every test of a race, a retry, or a fallback.

**Pure unit tests, where there is genuinely nothing to integrate.** `OrderStatusTest`,
`PaymentServiceTest`, `CreateOrderRequestValidationTest`. Validation annotations and enum logic need
no database, and testing them with one would be waste.

**Error contract tests.** That a bad request produces a 400 with the right `code`, that a missing
order produces `ORDER_NOT_FOUND`, that an unmapped route produces a 404 rather than a 500. The last
one exists as `UnmappedRouteIntegrationTest` — written *after* the bug in
[section 3](3-the-http-layer.md), which is the usual way regression tests come into being.

---

## Two habits worth forming here

**Test names should state the rule.** `reserveFailsWhenAnyLineIsShort` tells you what broke from the
failure report alone. `testReserve2` requires opening the file.

**A test for a bug goes in before the fix.** Every "we got this wrong" callout in this guide
corresponds to a test in the repository — `OrderOutOfOrderTransitionIntegrationTest`,
`UnmappedRouteIntegrationTest`, `OrderStreamBrokenConnectionIntegrationTest`. The test is the durable
part of the fix; without it the bug is one refactor away from returning.

---

## An honest gap

**There are no frontend tests.** No component tests, no browser tests, nothing. The `frontend/`
directory contains no test files at all.

That is a real gap rather than a deliberate scope decision, and it is worth knowing before anyone asks
about the project's testing story. The backend's coverage is genuinely strong — 53 integration test
classes against real infrastructure — and the frontend has none, which is a lopsided answer to "how do
you test this?"

The honest framing: the backend is where the project's interesting behavior lives, testing effort went
there, and the frontend was verified manually and in the browser. That is a defensible allocation and
an undefended flank, both at once.

---

[← The four domains](4-the-four-domains.md) · [Next: The first frontend →](6-the-first-frontend.md)


# 2.6 — The first frontend

[← Testing](5-testing.md) · [Chapter 2 ↑](README.md)

Three pages: create an order, list orders, view one order. That is the whole of Phase 1's frontend,
and it is deliberately small — the real console arrives in
[Chapter 5](../05-scenarios-and-frontend/README.md).

---

## What this frontend is for

Not a storefront. `docs/planning/project-overview.md` is explicit: *"Do not spend excessive time on
visual storefront polish."* At this stage the frontend has exactly one job — **prove the API is usable
by a real client**, which catches a category of problem that integration tests never will: a response
shape that is awkward to render, a missing field, an error body that cannot be displayed usefully.

## The stack

Vite, React 19, TypeScript, TanStack Query. Node 22. All pinned in
`docs/planning/project-overview.md` §0.

> **Primer — [React: components, state, and hooks](../technology/react/components-and-hooks.md)**
> The rendering model, `useState` and immutable updates, props and lifting state up, list keys,
> `useEffect` and cleanup, the rules of hooks, custom hooks.

> **Primer — [TanStack Query](../technology/react/tanstack-query.md)**
> Why server state is not client state, query keys and the cache, mutations and `invalidateQueries`,
> `staleTime` vs `gcTime`, polling vs pushing, and error handling.

**Vite** is the build tool and dev server. Two things it gives you that matter here: hot module
replacement fast enough that you stop thinking about it, and `import.meta.env` for build-time
configuration — the frontend equivalent of the `${VAR:default}` discipline from
[section 1](1-project-skeleton.md):

```ts
export const ORDER_SERVICE_BASE_URL = import.meta.env.VITE_ORDER_SERVICE_URL ?? 'http://localhost:8081';
```

Variables must be prefixed `VITE_` to be exposed to client code — a deliberate guard, since anything
exposed here ends up in the shipped bundle and is public. Never put a secret behind `VITE_`.

---

## The API layer

One thin module wraps `fetch`, and everything goes through it.

```ts
export async function apiFetch<T>(baseUrl: string, path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const body = await response.json();
  if (!response.ok) {
    throw new ApiRequestError(body as ApiError);
  }
  return body as T;
}
```

Three decisions in fifteen lines.

**It throws on `!response.ok`.** `fetch` famously does *not* — a 400 or a 500 resolves normally with
`ok: false`. A wrapper that forgets this hands an error body to the success path as if it were data,
and the bug surfaces as `undefined` in the UI rather than as an error. Every HTTP client you write
should start here.

**It throws a typed error carrying the server's `ApiError` body:**

```ts
export class ApiRequestError extends Error {
  readonly apiError: ApiError;
  constructor(apiError: ApiError) {
    super(apiError.message);
    this.apiError = apiError;
  }
}
```

This is the payoff for the shared error envelope in [section 3](3-the-http-layer.md). The frontend
gets `code`, `message`, and `correlationId` as structured data, so it can display the server's own
message rather than "Request failed," and branch on `code` where it needs to:

```ts
const errorMessage =
  mutation.error instanceof ApiRequestError ? mutation.error.apiError.message : mutation.error?.message;
```

Server-supplied message when the server answered; the raw error when it did not — a network failure,
a CORS rejection, unparseable JSON.

**204 is handled explicitly**, because `response.json()` on an empty body throws a parse error that
looks nothing like the actual problem.

## Types mirror the contract

```ts
// Mirrors docs/openapi/order-service.yaml — the Order Service's frozen contract.

export type OrderStatus =
  | 'PENDING'
  | 'INVENTORY_RESERVED'
  | 'REJECTED_OUT_OF_STOCK'
  | 'PAYMENT_PENDING'
  | 'PAID'
  | 'PAYMENT_FAILED'
  | 'FULFILLMENT_PENDING'
  | 'FULFILLED'
  | 'FAILED';

export interface OrderDetail extends OrderSummary {
  items: OrderItem[];
  statusHistory: OrderStatusHistoryEntry[];
}
```

Hand-written from the OpenAPI spec, with a comment naming the file they mirror.

The `OrderStatus` union is doing real work. A **string literal union** means TypeScript rejects a
typo at compile time and, more usefully, gives you **exhaustiveness checking**: a `switch` over
`OrderStatus` that forgets `FAILED` is a type error if you write it so the compiler can tell. That is
[Chapter 1](../01-design-contract/3-state-and-api-contracts.md)'s frozen enum, enforced on the client.

> **Worth flagging.** These types are *hand-written* from the spec, which means nothing detects
> divergence if the spec changes. Generating them from `docs/openapi/*.yaml` would close that gap.
> The project does not do this, and given a frozen contract and one frontend it is a defensible call —
> but it is the honest answer if anyone asks how the client stays in sync.

## Pages

Three, matching Phase 1's list: create, list, detail.

```tsx
export function OrdersListPage({ onSelectOrder, onCreateOrder }: Props) {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['orders'],
    queryFn: listOrders,
    refetchInterval: 4000,
  });

  return (
    <section>
      {isLoading && <p>Loading orders…</p>}
      {isError && <p className="error">{(error as Error).message}</p>}
      {data && data.content.length === 0 && <p>No orders yet.</p>}
      {data && data.content.length > 0 && (
        <table className="orders-table">
          <tbody>
            {data.content.map((order) => (
              <tr key={order.id} onClick={() => onSelectOrder(order.id)} className="order-row">
                <td>{order.id}</td>
                <td><StatusBadge status={order.status} /></td>
                <td>${order.totalAmount.toFixed(2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
```

**`refetchInterval: 4000` is the interesting line.** The frontend polls every four seconds, because an
order's status changes without the client doing anything — inventory, payment, and fulfillment all
move it — and there is currently no way to be told.

Polling is the honest answer *at this stage*. It is also visibly unsatisfying: four seconds of lag on
a workflow that completes in milliseconds, and a request every four seconds forever whether or not
anything changed.

> **Not yet.** [Chapter 5](../05-scenarios-and-frontend/README.md) replaces this with SSE
> (ADR-003) — the server pushes each status change as it happens. Feeling why polling is inadequate
> before reaching for the replacement is worth the detour; SSE is otherwise just an unexplained
> technology choice.

### Navigation, and what replaces it later

The pages take **callback props** rather than knowing about routing:

```tsx
interface Props {
  onSelectOrder: (orderId: string) => void;
  onCreateOrder: () => void;
}
```

At this stage the parent is a `useState` switch over three views. React Router arrives in Chapter 5,
and the real `App.tsx` records exactly why:

> Phase 5: seven pages replace the earlier state-based `view` switch in this file (list/create/detail
> only). A `useState` view switch does not scale to seven top-level pages plus nested [routes]
> (e.g. sharing a link straight to a scenario run) and back-button-navigable.

Note what the upgrade cost. Because the pages take callbacks and know nothing about URLs, adding the
router meant writing thin wrapper components and changing **nothing** inside the pages:

```tsx
function OrdersListRoute() {
  const navigate = useNavigate();
  return (
    <OrdersListPage
      onSelectOrder={(orderId) => navigate(`/orders/${orderId}`)}
      onCreateOrder={() => navigate('/orders/new')}
    />
  );
}
```

That is the same principle as keeping business logic out of controllers, on the other side of the
wire: a component that reports *what happened* rather than deciding *what it means* survives a change
of what it means.

### The create form

```tsx
const mutation = useMutation({
  mutationFn: createOrder,
  onSuccess: (accepted) => {
    queryClient.invalidateQueries({ queryKey: ['orders'] });
    onOrderCreated(accepted.id);
  },
});
```

After a successful create, `invalidateQueries(['orders'])` marks the list stale and TanStack Query
refetches — no manual cache patching, no reimplementing the server's sort order client-side.

The SKU dropdown is populated from Inventory Service (`useQuery({ queryKey: ['inventory'] })`), which
is the frontend making the ADR-004 boundary visible: **product names come from one service, prices
from another, and the client assembles the view.** That is the "no cross-domain joins" cost from
[section 1 of Chapter 1](../01-design-contract/1-boundaries-and-ownership.md), paid at the only place
that can pay it.

---

## Chapter 2 in one paragraph

You now have a working order-fulfillment system: four domain packages with real business rules, real
PostgreSQL persistence under Flyway, a validated HTTP API with one shared error model, integration
tests against real infrastructure, and a small React client that exercises it. It runs in one process,
completes a whole order inside a single HTTP request, and knows nothing about Kafka. Everything from
here is about taking that apart.

---

[← Testing](5-testing.md) · [Chapter 2 ↑](README.md) · [Chapter 3 — Kafka, and the split into services →](../03-kafka-and-services/README.md)


<hr style="page-break-after: always;"/>

# Chapter 3 — Kafka, and the split into services

**Build history:** Phases 2 and 3, in one commit — `1f2bc50 introduce kafka and split up monorepo`.

Two planned phases that turned out to be one piece of work. Phase 2 was to introduce Kafka in-process;
Phase 3 was to extract the services. In practice, once the domains communicated only through events,
they were already separate in every way that mattered and putting them in separate JVMs was
bookkeeping.

That is not a shortcut — it is the plan working. The reason to sequence it as two phases is that
*if* the extraction had been hard, you would want to know that before doing it.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [Events on the wire](1-events-on-the-wire.md) | Serializer choice and why not `JsonSerializer`, the codec and two-stage deserialization, the publisher, declaring topics, and the dual-write gap this leaves open |
| 2 | [Producing and consuming](2-producing-and-consuming.md) | `@KafkaListener` shape, group IDs and listener IDs, the workflow redistributed across four services, and the four things that just got harder |
| 3 | [Correlation IDs](3-correlation-ids.md) | Why a distributed system needs one, `runInScope`, reading from ambient scope with a loud failure, correlation vs. tracing |
| 4 | [The split](4-the-split.md) | Four modules, four schemas, what gets simpler, testing across a boundary, and the exit criterion worth performing by hand |

---

## What changes, in one sentence

`POST /api/orders` stops returning the answer.

Everything else follows from that. The HTTP response becomes an acknowledgement rather than a result;
the workflow stops existing as readable code; transactions stop spanning the workflow; ordering stops
being free; and duplicate delivery stops being an edge case.

## What does not change

Every domain class from [Chapter 2](../02-domain/README.md). `InventoryService.reserve` is called by a
Kafka listener instead of an orchestrator and is otherwise identical. That is the return on keeping
business logic out of controllers and out of the orchestrator — the domain never knew who was calling
it, so changing the caller costs nothing.

> **Not yet — three gaps this chapter deliberately leaves open.** Each is the subject of a later
> chapter, and each is worth *seeing* before it is fixed:
>
> - **Duplicate delivery double-reserves stock.** At-least-once is the guarantee; nothing deduplicates
>   yet. [Chapter 4](../04-reliability/README.md).
> - **A crash between commit and publish loses the event.** Documented in the event catalog as a known
>   limitation rather than hidden. [Chapter 6](../06-outbox/README.md).
> - **Events from different topics can be processed out of order.** `ShipmentCreated` can beat the
>   `PaymentAuthorized` that caused it. [Chapter 4](../04-reliability/README.md), and
>   [Chapter 10](../10-retrospective/README.md) for how it was found.

---

## Build it yourself

**Kafka running** — `apache/kafka` in KRaft mode, port 9092. One container, no ZooKeeper.

**Wire it up** — [section 1](1-events-on-the-wire.md)

1. Add `spring-boot-starter-kafka` to each module.
2. `application.yml`: `bootstrap-servers` as `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`, **String**
   serializers and deserializers both ways, `auto-offset-reset: earliest`, and
   `jackson.deserialization.fail-on-unknown-properties: false`.
3. In `common`: `EventEnvelope<T>` as a record, `EventTypes` and `KafkaTopics` constants, the eight
   payload records from the event catalog.
4. `EventCodec` — `decode` to `EventEnvelope<JsonNode>` with the version check inside it;
   `payloadAs` for the second stage. Plus `UnsupportedEventVersionException`.
5. `EventPublisher` — one `buildEnvelope` method, keyed sends, the explicit-`eventId` overload, and
   the `IllegalStateException` when no correlation ID is in scope.
6. `KafkaTopicConfig` — declare all eight topics explicitly at 3 partitions, replication factor 1.

**Correlation IDs** — [section 3](3-correlation-ids.md)

7. `CorrelationIdHolder` (two `ThreadLocal`s — the holder and the SLF4J MDC) with `set`, `get`,
   `clear`, and `runInScope`.
8. `CorrelationIdFilter` — accept `X-Correlation-Id` or generate, replace a malformed one, echo it
   back, **clear in a `finally`**.

**Consumers** — [section 2](2-producing-and-consuming.md)

9. A `*Consumers` constants class per service holding listener IDs *and* consumer names, both as
   compile-time constants.
10. Order Service publishes `OrderCreated` from `createOrder` and stops orchestrating.
11. Six listeners, all four steps each — decode, `runInScope`, filter by `eventType`, delegate:
    - Inventory ← `orders.events` (`OrderCreated`)
    - Payment ← `orders.events` (`PaymentRequested`)
    - Order ← `inventory.events` (`InventoryReserved`, `InventoryReservationFailed`)
    - Order ← `payments.events` (`PaymentAuthorized`, `PaymentRejected`)
    - Fulfillment ← `payments.events` (`PaymentAuthorized`)
    - Inventory ← `payments.events` (`PaymentRejected`)
    - Order ← `fulfillment.events` (`ShipmentCreated`)
12. **Delete `SynchronousOrderWorkflow`.**

**Split** — [section 4](4-the-split.md)

13. Four modules under `services/`, each with its own `pom.xml` and boot plugin. Move each domain
    package, its migrations, and its tests.
14. Four application classes at `com.orderfulfillment.<domain>`, plus `scanBasePackages` (or an
    auto-configuration) so `common`'s beans are still found.
15. Four `application.yml` files: distinct `spring.application.name`, `server.port` 8081–8084,
    `spring.flyway.schemas`. Delete the multi-schema Flyway runner.
16. Per-service `AbstractIntegrationTest`: singleton PostgreSQL **and** Kafka containers, an injected
    `EventPublisher` for simulating upstream services, a raw `KafkaTemplate` for malformed records,
    and a `rawConsumer(topic)` helper using a fresh random group per call.
17. Rewrite each service's tests to prove only its own contract — given these events in, these events
    out.
18. Frontend: five base URLs from `import.meta.env`. CORS configured on all four services.

**Done when:** `POST /api/orders` returns `PENDING` immediately; the order reaches `FULFILLED` with
nothing calling anything; out-of-stock and payment-rejection paths still work end to end, the latter
releasing stock through `InventoryReleased`; each service starts and stops independently; and
**stopping Inventory Service, creating an order, and starting it again completes the order** rather
than failing it.

---

## Next

[Section 1 — Events on the wire](1-events-on-the-wire.md).


# 3.1 — Events on the wire

[← Chapter 3](README.md) · [Next: Producing and consuming →](2-producing-and-consuming.md)

Turning the frozen envelope from [Chapter 1](../01-design-contract/2-the-event-contract.md) into
bytes, and back.

---

## What Kafka needs from you

Kafka stores a key and a value, both as bytes. It has no opinion about what is inside them. So before
anything can be published, two decisions have to be made: **what format**, and **who does the
converting**.

> **Primer — [Spring for Apache Kafka](../technology/kafka/spring-kafka.md)**
> What the starter auto-configures, serializer choices and their consequences, `auto-offset-reset`,
> producing and offset commits, `@KafkaListener` and the listener container, concurrency, and testing
> against a real broker.

## The serialization decision

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: earliest
```

**Strings both ways** — not Spring Kafka's `JsonSerializer`. The listener receives a `String` and
calls an explicit codec.

That is more typing, and it buys three things this project specifically needs:

- **The wire format is exactly the frozen envelope.** `JsonSerializer` adds a `__TypeId__` header
  naming the Java class, which would put `com.orderfulfillment.common.events.OrderCreatedPayload` on
  the wire — a package name is not part of `docs/events/event-catalog.md`, and renaming a class would
  become a wire-breaking change.
- **Deserialization failures happen in your code**, at a point you control, rather than inside the
  listener container before your method runs. Scenario 6 publishes a deliberately unprocessable
  record, and the failure needs to be an ordinary exception on a path you can reason about.
- **Version checking has somewhere to live.** The catalog's rule — an unknown `eventVersion` is a
  *non-retryable* failure — needs a hook, and an explicit codec is it.

One more line worth pointing at:

```yaml
  jackson:
    deserialization:
      fail-on-unknown-properties: false # envelope versioning rule (event-catalog.md §5): consumers must ignore unknown fields
```

This is the versioning rule made operational. *"Additive, optional payload field → no version bump.
Consumers must ignore unknown fields."* That promise is only keepable if deserialization is configured
to tolerate them — and Jackson's default is to throw. One line of configuration is the difference
between a versioning policy you wrote down and one that works.

---

## The codec

```java
@Component
public class EventCodec {

    private final ObjectMapper objectMapper;

    public EventEnvelope<JsonNode> decode(String json) {
        EventEnvelope<JsonNode> envelope = objectMapper.readValue(json, new TypeReference<>() { });
        if (envelope.eventVersion() != EventTypes.CURRENT_VERSION) {
            throw new UnsupportedEventVersionException(envelope.eventType(), envelope.eventVersion());
        }
        return envelope;
    }

    public <T> T payloadAs(EventEnvelope<JsonNode> envelope, Class<T> payloadType) {
        return objectMapper.treeToValue(envelope.payload(), payloadType);
    }
}
```

Twelve lines that encode the entire envelope idea.

**Two-stage deserialization.** `decode` parses to `EventEnvelope<JsonNode>` — the envelope fully
typed, the payload left as an unconverted JSON tree. That is not laziness; it is *necessary*. A single
topic carries more than one event type (`orders.events` carries both `OrderCreated` and
`PaymentRequested`), so the payload's Java type is unknowable until `eventType` has been read. Only
after the caller's `switch` picks a branch does `payloadAs` convert it.

This is what the generic parameter on `EventEnvelope<T>` is for, and its Javadoc says so:

> producers build an envelope over a concrete payload record; consumers first deserialize with
> `payload` typed as `JsonNode` [...] and then convert it to the concrete payload type once
> `eventType` is known, since a single topic can carry more than one event type.

**The version check is in `decode`,** so it runs before any consumer sees the record — one place, not
five. `UnsupportedEventVersionException` becomes the marker that
[Chapter 4](../04-reliability/README.md)'s error handler classifies as non-retryable: *"Retrying cannot fix a
schema it doesn't understand."*

**No try/catch.** Worth a note if you are used to older Jackson:

> Jackson 3 (Spring Boot 4.1's baseline — `tools.jackson.*`, not the older
> `com.fasterxml.jackson.databind`) makes `ObjectMapper`'s read/write methods throw the unchecked
> `tools.jackson.core.JacksonException` instead of a checked exception.

Note the package: `tools.jackson`, not `com.fasterxml.jackson`. Almost every Jackson example you will
find online uses the old one.

---

## The publisher

```java
@Component
public class EventPublisher {

    public UUID publish(String topic, String eventType, String aggregateId, Object payload) {
        return publish(topic, eventType, aggregateId, UUID.randomUUID(), payload);
    }

    public UUID publish(String topic, String eventType, String aggregateId, UUID eventId, Object payload) {
        String json = objectMapper.writeValueAsString(buildEnvelope(eventType, aggregateId, eventId, payload));
        kafkaTemplate.send(topic, aggregateId, json);
        return eventId;
    }

    public EventEnvelope<Object> buildEnvelope(String eventType, String aggregateId, UUID eventId, Object payload) {
        UUID correlationId = CorrelationIdHolder.get();
        if (correlationId == null) {
            throw new IllegalStateException("No correlationId in scope while publishing " + eventType
                    + " — every publish site must run within an HTTP request or a @KafkaListener that set one");
        }
        return new EventEnvelope<>(
                eventId, eventType, EventTypes.CURRENT_VERSION, Instant.now(), correlationId, aggregateId, payload);
    }
}
```

**One place builds the envelope.** Every outbound record in the system passes through
`buildEnvelope`, which is what makes "every event has these six fields, correctly populated" a
property of the system rather than a convention people follow.

**`kafkaTemplate.send(topic, aggregateId, json)`** — the key is the `aggregateId`, which is the
`orderId`. That single argument is the whole of
[Chapter 1](../01-design-contract/2-the-event-contract.md)'s partitioning decision, and forgetting it
would silently switch the system to round-robin distribution with no per-order ordering at all and no
error anywhere.

**The overload taking an explicit `eventId`** exists for one specific case, and it is a nice piece of
contract detail: `PaymentRequested`'s payload carries an `idempotencyKey` which *is* that event's own
`eventId`. You cannot reference an ID that a method is about to generate, so the caller generates it
first and passes it in.

**`correlationId` is read from ambient scope, not passed.** With a loud failure if it is missing.
That is a pattern in its own right:

> **Pattern — [Correlation ID propagation](../patterns/correlation-id-propagation.md)**
> How one identifier survives an HTTP boundary, a Kafka hop, and a thread change without every method
> signature growing a parameter — and the three ways that goes wrong.

Covered in full in [section 3](3-correlation-ids.md).

### The gap in `publish`

```java
kafkaTemplate.send(topic, aggregateId, json);
```

Not blocked on. `send` returns a `CompletableFuture` that nothing awaits, so this is fire-and-forget:
the record is batched and sent asynchronously, and a failure is logged by the Kafka client rather than
thrown here.

Combined with the fact that the caller's database transaction has usually already committed, that
leaves a real window: **a business change can exist with no event.** The catalog names it:

> Until Phase 6 (transactional outbox), publishers persist their business change and then publish —
> so a crash between commit and publish loses the event.

> **Not yet.** This is a known, documented gap, not an oversight, and it is
> [Chapter 6](../06-outbox/README.md)'s entire subject. Blocking on the future would narrow the window
> without closing it — the fix is structural.

### A stale comment worth knowing about

`EventPublisher`'s Javadoc says:

> Phase 6 closed that gap in Order Service only [...] Inventory, Payment and Fulfillment Service
> still publish this way, deliberately and documented.

**That is no longer true.** Sprint 2 moved all four services onto the outbox — `OutboxRecorder` and
`OutboxPublisher` exist in Inventory, Payment, and Fulfillment Service, and their own consumers'
Javadocs say so explicitly. The comment on `EventPublisher` was not updated. ADR-006 carries the
correction; this class does not.

`EventPublisher.publish` is consequently used far less than its documentation implies — mostly by
Scenario Service, which publishes duplicate and poison records deliberately, and by tests simulating
upstream services.

---

## Declaring the topics

```java
@Configuration
public class KafkaTopicConfig {

    private static final int PARTITIONS = 3;
    private static final int REPLICATION_FACTOR = 1;

    @Bean
    public NewTopic ordersEventsTopic() {
        return TopicBuilder.name(KafkaTopics.ORDERS_EVENTS).partitions(PARTITIONS).replicas(REPLICATION_FACTOR).build();
    }
    // …seven more: three domain topics, four DLQs
}
```

Kafka will happily auto-create a topic on first use, with broker-default settings. This declares all
eight explicitly instead, and the comments give both reasons:

> so partition count/replication are deterministic regardless of broker defaults

and, for the DLQs specifically:

> a DLQ that only exists because a broker auto-created it on first dead-letter would have
> broker-default partitioning, and Phase 4's whole point is that the failure path is as real and as
> deterministic as the happy path.

Auto-creation also silently hides typos: publish to `orders.event` and Kafka creates it, no consumer
ever reads it, and nothing anywhere reports an error. Declaring topics and referencing them through
`KafkaTopics` constants removes that failure mode entirely.

**Three partitions** is the number to remember. It is the ceiling on consumer parallelism per group,
and [Chapter 8](../08-observability-and-scaling/README.md) runs straight into it: a fourth replica in one
consumer group is assigned nothing and idles.

**Replication factor 1** means one broker and no redundancy — lose it, lose the data. A deliberate
scope decision (`project-overview.md` rules out "full production Kafka operations"), and one to state
rather than let someone assume otherwise.

`TopicBuilder` beans are applied at startup by Spring Boot's auto-configured `KafkaAdmin`. Note that
Kafka will *increase* a partition count on an existing topic but never decrease it, and increasing it
changes which partition existing keys hash to — so this number is much easier to choose correctly than
to change.

---

[← Chapter 3](README.md) · [Next: Producing and consuming →](2-producing-and-consuming.md)


# 3.2 — Producing and consuming

[← Events on the wire](1-events-on-the-wire.md) · [Next: Correlation IDs →](3-correlation-ids.md)

Deleting `SynchronousOrderWorkflow` and letting the workflow emerge from four listeners instead.

---

## The shape of a consumer

Every `@KafkaListener` in this project has the same four-step structure. Learn it once:

```java
@KafkaListener(id = InventoryConsumers.ORDER_CREATED_LISTENER_ID,
        topics = KafkaTopics.ORDERS_EVENTS, groupId = GROUP_ID)
public void onMessage(String message) {
    EventEnvelope<JsonNode> envelope = eventCodec.decode(message);          // 1. decode envelope
    CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));  // 2. enter scope
}

private void handle(EventEnvelope<JsonNode> envelope) {
    if (!EventTypes.ORDER_CREATED.equals(envelope.eventType())) {           // 3. is this ours?
        return;
    }
    OrderCreatedPayload payload = eventCodec.payloadAs(envelope, OrderCreatedPayload.class);
    inventoryService.reserve(payload.orderId(), toLines(payload.items()));  // 4. delegate to the domain
}
```

1. **Decode the envelope**, payload left as `JsonNode`.
2. **Enter correlation scope** ([section 3](3-correlation-ids.md)).
3. **Filter by `eventType`** — because a topic carries more than one, and most listeners want a
   subset.
4. **Convert the payload and call the domain service.** The listener itself contains no business
   logic.

Step 4 is the payoff from [Chapter 2](../02-domain/4-the-four-domains.md). `InventoryService.reserve`
does not change when its caller becomes a Kafka listener instead of an orchestrator — because it never
knew about its caller in the first place. A listener is a **second entry point** into the same domain,
exactly as a controller is.

### The `groupId`

```java
private static final String GROUP_ID = "inventory-service";
```

One group per service. That is what makes fan-out work: Order Service and Fulfillment Service both
consume `payments.events`, in **different** groups, so each receives every record independently and
neither knows the other exists. Put them in one group and they would split the partitions and each see
roughly half — a subtle, data-losing bug with no error attached.

### The `id`

```java
static final String INVENTORY_EVENTS_LISTENER_ID = "inventory-events";
```

A stable name for the listener *container*, distinct from the group ID. This is what makes a listener
addressable at runtime through `KafkaListenerEndpointRegistry` — which is how
[Chapter 4](../04-reliability/README.md) pauses a consumer for Scenario 5. Give every listener an explicit
`id`; the auto-generated ones are not stable across restarts.

`OrderConsumers` and `InventoryConsumers` collect these as compile-time constants, and the Javadoc
explains why the two namespaces are kept apart:

> the **listener id** is the `@KafkaListener` id — one per inbound topic [...] the **consumer name**
> is the `processed_events.consumer_name` column, qualified by service (`"order.inventory-events"`).
>
> Both are compile-time constants and must never be derived from anything that varies between
> restarts: a ledger row written under one name and looked up under another would not deduplicate.

The second namespace belongs to [Chapter 4](../04-reliability/README.md) — but define both now, because
"never derived from anything that varies between restarts" is much easier to honour from the start
than to retrofit.

---

## The workflow, redistributed

Here is the whole of `SynchronousOrderWorkflow`, redistributed across four services. Nothing calls
anything.

### Order Service — `POST /api/orders`

Persists the order as `PENDING` and publishes `OrderCreated` to `orders.events`. Returns. **This is
where the HTTP request ends**, and where the OpenAPI spec's asynchrony note finally becomes true:

```java
/**
 * Entry point for POST /api/orders. Persists the order as PENDING, records OrderCreated for
 * publication, and returns — it does not wait for inventory, payment, or fulfillment. That
 * happens because Inventory/Payment/Fulfillment now react to Kafka events rather than being
 * called directly from here, so this class no longer knows or cares how the order eventually
 * resolves. This finally matches docs/openapi/order-service.yaml's POST /api/orders description;
 * Phase 1's synchronous version (which returned the actual terminal status) is documented as a
 * deliberate, temporary deviation.
 */
```

*"no longer knows or cares how the order eventually resolves"* is the sentence to hold onto. It is the
entire architectural change in eight words.

### Inventory Service — consuming `orders.events`

Reacts to `OrderCreated`, reserves, publishes `InventoryReserved` or `InventoryReservationFailed` to
`inventory.events`.

Note the filter, and the comment on it:

```java
// orders.events also carries PaymentRequested, which Inventory Service has no use for.
if (!EventTypes.ORDER_CREATED.equals(envelope.eventType())) {
    return;
}
```

This is the cost of domain-oriented topics from
[Chapter 1](../01-design-contract/2-the-event-contract.md): a consumer sees everything on the topic
and discards what is not its business. Cheap — a string comparison per record — and the price of
having four topics instead of eight.

### Order Service — consuming `inventory.events`

Drives transitions 2 and 3, and on success transition 4 (publishing `PaymentRequested`).

Note the `switch` and its `default`:

```java
private void handle(EventEnvelope<JsonNode> envelope) {
    switch (envelope.eventType()) {
        case EventTypes.INVENTORY_RESERVED -> onInventoryReserved(envelope);
        case EventTypes.INVENTORY_RESERVATION_FAILED -> onInventoryReservationFailed(envelope);
        // InventoryReleased has no Order Service consumer in v1 (event-catalog.md §3) — ignored,
        // and filtered here before the ledger is touched: a skipped record has no side effect to
        // deduplicate.
        default -> { /* not one of ours */ }
    }
}
```

The `default` branch is doing something worth naming: it makes **an unrecognized event type a no-op
rather than an error**. That is what allows a new event type to be added to a topic without breaking
every existing consumer — the same forward-compatibility idea as ignoring unknown JSON fields, one
level up.

### Payment Service — consuming `orders.events`

Reacts to `PaymentRequested`, runs the simulator, publishes `PaymentAuthorized` or `PaymentRejected`
to `payments.events`.

Note that Payment Service and Inventory Service **both** consume `orders.events`, in different groups,
each filtering for a different event type. Neither is aware of the other.

### Three consumers of `payments.events`

This is the most interesting topic in the system, and the clearest illustration of what a log buys
you:

- **Order Service** consumes `PaymentAuthorized` → `PAID` → `FULFILLMENT_PENDING`, and
  `PaymentRejected` → `PAYMENT_FAILED`.
- **Fulfillment Service** consumes `PaymentAuthorized` → creates a shipment → publishes
  `ShipmentCreated`.
- **Inventory Service** consumes `PaymentRejected` → releases the reservation → publishes
  `InventoryReleased`.

**One record, three independent readers, three unrelated reactions, zero coordination.** Payment
Service knows about none of them. Adding a fourth — a notification service, say — would require
nothing from Payment Service at all.

That is the fan-out ADR-001 cited as a benefit: *"`PaymentAuthorized` is consumed by two services in
different consumer groups for different reasons, with neither aware of the other — fan-out that costs
nothing to add."*

It is also where the compensation path lives. Inventory releasing stock on `PaymentRejected` is the
compensating action from [Chapter 2](../02-domain/4-the-four-domains.md) — except now there is no
shared transaction it could have been part of, which was always the point.

### Order Service — consuming `fulfillment.events`

`ShipmentCreated` → `FULFILLED`. Terminal.

---

## What just got harder

Four things the monolith gave you for free are now gone. Naming them is most of what this chapter is
for.

**The workflow is not written down anywhere.** There is no file you can read to learn the sequence.
It is an emergent property of four listeners' subscriptions. `docs/architecture-diagram.md` is the
closest surviving artifact, and it is a diagram rather than code — which is exactly why Phase 0
insisted on producing it.

**A transaction covers one service, not the workflow.** Inventory's reservation commits whether or not
payment later succeeds. Undoing it requires an explicit compensating event, and there is a window
during which stock is reserved for an order that is about to fail.

**Ordering guarantees are much weaker than they look.** Keying by `orderId` orders one order's records
within *one topic's* partition. Order Service consumes **three** topics, and Kafka guarantees nothing
between them.

> **Not yet — and this one bites hard.** Order Service consumes `payments.events` and
> `fulfillment.events` independently. `ShipmentCreated` can be processed *before* the
> `PaymentAuthorized` that caused it, because they are on different topics with different partitions
> and different offsets. At this point in the build nothing prevents that.
> [Chapter 4](../04-reliability/README.md) builds the guard (ADR-009);
> [Chapter 10](../10-retrospective/README.md) has the story of finding it in Phase 10, live, under load.

**Duplicate delivery is now normal.** At-least-once means every consumer will eventually see the same
record twice — from an uncommitted offset after a crash, or a rebalance mid-batch.

> **Not yet.** Right now a redelivered `OrderCreated` reserves stock a second time. The
> `ProcessedEventLedger` in the real consumers is [Chapter 4](../04-reliability/README.md); build these
> listeners without it and you will be able to demonstrate the problem before building the fix, which
> is worth doing at least once.

---

## Verifying it

At the end of this section, `POST /api/orders` should return `PENDING` immediately and the order
should reach `FULFILLED` a moment later without anything calling anything.

Two things worth watching while you get there:

**Consumer group state.** `kafka-consumer-groups.sh --describe --group inventory-service` shows
partition assignment, current offset, and lag. If a consumer is receiving nothing, this is where you
look first — and `auto-offset-reset: latest` (the Kafka default, which this project overrides to
`earliest`) is the single most common reason.

**The topics themselves.** `kafka-console-consumer.sh --topic orders.events --from-beginning` prints
the actual JSON envelopes. Seeing your own frozen envelope come back off the wire is the fastest way
to confirm that [section 1](1-events-on-the-wire.md) is wired correctly.

---

[← Events on the wire](1-events-on-the-wire.md) · [Next: Correlation IDs →](3-correlation-ids.md)


# 3.3 — Correlation IDs

[← Producing and consuming](2-producing-and-consuming.md) · [Next: The split →](4-the-split.md)

A short section about one field, because it is the difference between a debuggable distributed system
and an undebuggable one.

---

## Why now

In [Chapter 2](../02-domain/README.md) an order was one HTTP request, one thread, one stack trace. If
something went wrong you had all of it in front of you.

That is now gone. A single order produces log lines in four processes, on four different threads, none
of which is the thread that handled the HTTP request. The stack trace of a failure in Fulfillment
Service tells you nothing about the order that caused it, and nothing about which of the fifty orders
in flight it belongs to.

ADR-001 listed this as an accepted cost and named the mitigation in the same sentence:

> Debugging spans process boundaries, which is why correlation IDs are a required envelope field
> rather than a nice-to-have.

**Required**, not optional. `correlationId` is one of the six mandatory envelope fields from
[Chapter 1](../01-design-contract/2-the-event-contract.md), and `EventPublisher` throws rather than
publish an event without one.

---

## The mechanism

One UUID, generated once by whoever starts a workflow — Order Service on `POST /api/orders`, or
Scenario Service at the start of a run — and **copied by every consumer onto every event it publishes
in reaction.**

That copying rule is the whole thing. Order Service stamps `OrderCreated`. Inventory Service consumes
it, and when it publishes `InventoryReserved` it carries the same value forward rather than generating
a new one. So does Payment, so does Fulfillment. One order's entire event chain, across four services,
shares one identifier.

Three transports carry it — an HTTP header inbound, an envelope field between services, and two
`ThreadLocal`s inside each process (one for application code, one for the logging framework's MDC).

> **Pattern — [Correlation ID propagation](../patterns/correlation-id-propagation.md)**
> The full mechanism: `CorrelationIdFilter` for HTTP, `runInScope` for Kafka listeners, why
> `EventPublisher` reads from ambient scope rather than taking a parameter, the three ways this goes
> wrong (stale `ThreadLocal`s on pooled threads, async boundaries, setting one scope but not the
> other), and how it relates to real distributed tracing.

---

## The two lines that matter in this chapter

**Every `@KafkaListener` wraps its work:**

```java
public void onMessage(String message) {
    EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
    CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
}
```

An HTTP filter cannot help here — a consumer thread has no request. The listener establishes the scope
itself, from the envelope it just read. Without this line, everything the handler does is
untraceable, and any event it publishes fails outright.

**And `EventPublisher` fails loudly if nobody did:**

```java
UUID correlationId = CorrelationIdHolder.get();
if (correlationId == null) {
    throw new IllegalStateException("No correlationId in scope while publishing " + eventType
            + " — every publish site must run within an HTTP request or a @KafkaListener that set one");
}
```

This is the design decision worth understanding, because it is a trade rather than a free win.

Reading from ambient scope means `EventPublisher.publish` takes no correlation-ID parameter, and
neither does anything between the entry point and the publish site. **That is what makes the pattern
survivable.** Threading the ID explicitly would mean adding a parameter to every service method,
every repository call, every helper — which is where most attempts at this quietly die, because
somebody eventually adds a code path that does not thread it.

The cost is an invisible dependency: this code only works because something upstream set a
`ThreadLocal`. The exception is what makes that cost acceptable. A publish site outside any scope
fails immediately, in development, naming the rule it broke — instead of writing an event with a null
correlation ID that nobody notices until a trace comes up empty three weeks later.

**An implicit dependency with a loud failure is a completely different thing from an implicit
dependency with a silent one.** That generalizes well beyond this project.

---

## What it gets you, concretely

```
docker compose logs | grep d89512f7-b544-4170-b66b-2e93f475ea8f
```

Every log line, from all five services, belonging to one order — in order. The HTTP request that
created it, Inventory's reservation, Payment's authorization, Fulfillment's shipment, and each of
Order Service's status transitions.

The same value is also in:

- **the `ApiError` response body** ([Chapter 2](../02-domain/3-the-http-layer.md)), so a user
  reporting a failure hands you the exact search term;
- **every event envelope**, so [Chapter 5](../05-scenarios-and-frontend/README.md)'s Event Explorer can
  group a workflow's events;
- **the response's `X-Correlation-Id` header**, echoed back to the caller.

> **Not yet.** At this point the grep works because the ID is in the log *message*. It is not yet a
> structured *field*. [Chapter 8](../08-observability-and-scaling/README.md) adds ECS structured logging,
> which puts MDC entries under `labels.correlationId` automatically — turning a text grep into a
> queryable field. The MDC half of `runInScope` is written now precisely so that upgrade is a
> configuration change rather than a code change.

---

## What this is not

It gives you **correlation**, not **tracing**. You can find every line belonging to one operation. You
cannot get a timing waterfall showing where the time went, because there are no spans, no parent/child
relationships, and no duration data.

Real distributed tracing — OpenTelemetry, Zipkin, Micrometer Tracing — provides all of that, and
propagates automatically through W3C `traceparent` headers. Building this by hand instead is a
deliberate scope decision: one envelope field and two `ThreadLocal`s, versus a collector, a backend,
and an agent. Worth being able to say plainly, along with what you would reach for if the system
needed more.

---

[← Producing and consuming](2-producing-and-consuming.md) · [Next: The split →](4-the-split.md)


# 3.4 — The split

[← Correlation IDs](3-correlation-ids.md) · [Chapter 3 ↑](README.md)

One process becomes four. This is the part that sounds like the hard bit and turns out not to be —
which is the whole argument of ADR-007 and the reason the build order is what it is.

---

## What actually has to change

Once the four domains communicate only through Kafka, they no longer call each other's methods. The
only thing keeping them in one JVM is that they happen to be in one JVM.

So the extraction is mechanical:

1. **Four modules instead of one.** `services/order-service`, `services/inventory-service`,
   `services/payment-service`, `services/fulfillment-service`, each with its own `pom.xml`, each
   depending on `common`, each with the `spring-boot-maven-plugin`.
2. **Four application classes**, each moving down from `com.orderfulfillment` to
   `com.orderfulfillment.<domain>` so its component scan covers only its own package.
3. **Four `application.yml` files**, each with its own `spring.application.name`, its own
   `server.port` (8081–8084), and its own `spring.flyway.schemas`.
4. **The migrations move with their domain**, into each module's own
   `src/main/resources/db/migration`.
5. **Delete `SynchronousOrderWorkflow`.** Nothing has called it since
   [section 2](2-producing-and-consuming.md).

The domain classes themselves — `OrderService`, `InventoryService`, `PaymentService`,
`FulfillmentService`, every entity, every repository, every controller, every listener — move between
modules **unchanged**. That is the payoff being collected.

## The two things that get simpler

**Flyway.** [Chapter 2](../02-domain/2-persistence.md) needed a hand-written runner because one JVM
migrated four schemas. Now each JVM owns one, and Spring Boot's ordinary auto-configuration is enough:

```yaml
spring:
  flyway:
    schemas: order_service
    # Phase 3 simplification: Spring Boot's ordinary built-in Flyway auto-configuration is enough
    # now that each service's JVM only ever migrates its own schema — the Phase 1/2 multi-schema
    # SchemaMigrationRunner existed only because one JVM drove four schemas at once.
```

Delete the runner.

**Component scanning gets a wrinkle, not a simplification.** Each application class now sits at
`com.orderfulfillment.order` and no longer scans `com.orderfulfillment.common`, which is a sibling.
`EventCodec`, `EventPublisher`, `GlobalExceptionHandler`, and `CorrelationIdFilter` all become
invisible until you say otherwise — via `scanBasePackages`, or by registering `common`'s beans as an
auto-configuration. See the
[auto-configuration primer](../technology/spring/auto-configuration.md) for both options.

## What stays shared

One PostgreSQL server, four schemas. ADR-004 rejected a database container per service for local
development as disproportionate:

> four database containers to start, four connection configurations, four sets of credentials, and
> four times the memory, all to enforce a boundary that one schema per service plus a code review
> already enforces.

**Nothing in the code changes if you later split the server**, because no query ever crosses a schema.
The boundary is real; the deployment topology is a configuration detail. Being able to say that — and
to point at *why* it is true — is a much better answer than either "we share a database" or "we have
four databases."

Also shared: the `common` module, and one Kafka cluster.

---

## Testing across a boundary

This is the genuinely interesting problem the split creates, and the project's answer is worth
copying.

**The problem.** `OrderServiceIntegrationTest` used to create an order and assert it reached
`FULFILLED`, because all four domains were in the JVM under test. Now they are not. Order Service
alone cannot fulfil anything.

Three options:

- **Start all four services in the test.** Highest fidelity, and it makes every service's test suite
  depend on every other service's code — recreating in the tests exactly the coupling the split
  removed.
- **Mock the Kafka interactions.** Fast, and it proves nothing about the wire format, which is the
  contract that actually matters.
- **Start one service, and simulate the others by publishing the events they would have published.**

The third is what this project does:

> unlike the Phase 1/2 monolith's single integration-test base that exercised all four domains in one
> JVM, this base only ever starts Order Service itself — Inventory/Payment/Fulfillment's own
> reactions are simulated by publishing the same wire-format events they would have produced, using
> the same `EventPublisher` bean this service uses for its own outbound events, so the JSON shape is
> identical to what a real upstream service would send.

The detail that makes it work is **using the production `EventPublisher`** rather than hand-writing
test JSON. A hand-written fixture drifts from the envelope the moment anything changes; publishing
through the same code path the real producer uses means the test exercises the actual frozen contract.

So each service's tests prove: *given these events on the wire, this service does the right thing and
publishes these events in response.* Which is precisely what its contract says, and no more.

The base class also gains a real Kafka broker alongside PostgreSQL:

```java
KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));
KAFKA.start();
```

and a **raw consumer** for asserting on what the service published:

```java
/** Raw consumer for asserting what this service published, independent of its own listener
 * container's consumer group. */
Consumer<String, String> rawConsumer(String topic) {
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-observer-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    // ...
}
```

A **unique group per call** is the key detail. A fixed group would compete with the application's own
consumers for partitions and would carry committed offsets between tests. A fresh random group reads
the whole topic from the beginning and interferes with nothing.

And one deliberate escape hatch:

```java
/**
 * For publishing records {@link EventPublisher} deliberately cannot produce — an envelope with
 * an eventVersion the codec rejects, or a payload that will not deserialize. Phase 4's
 * poison-message scenario needs a genuinely malformed record on the wire, not a mocked failure.
 */
@Autowired
KafkaTemplate<String, String> kafkaTemplate;
```

*"A genuinely malformed record on the wire, not a mocked failure"* is the standard the whole project
holds itself to, applied to tests.

---

## The frontend

Five base URLs instead of one:

```ts
export const ORDER_SERVICE_BASE_URL = import.meta.env.VITE_ORDER_SERVICE_URL ?? 'http://localhost:8081';
export const INVENTORY_SERVICE_BASE_URL = import.meta.env.VITE_INVENTORY_SERVICE_URL ?? 'http://localhost:8082';
// …
```

This is where the client feels ADR-004 directly: the order list comes from one service and the SKU
catalog from another, and nothing joins them but the browser. It is also why every service needs CORS
configured — five origins in development.

> **Where this changes.** [Chapter 9](../09-production/README.md) puts all five behind a single hostname with
> `/svc/{service}/…` path prefixes, at which point there is no cross-origin request at all and CORS
> exists only for local development. `docs/architecture-diagram.md` calls this out as *"a deployment
> property rather than an architectural one"* — the arrows are the same either way.

---

## The exit criteria, and what they actually prove

Phase 3's criteria are behavioral rather than structural, which is the right way to write them:

> - services can be independently stopped/restarted,
> - order processing still works after recovery,
> - service boundaries are understandable.

The second is the one to actually test, by hand, once: **stop Inventory Service, create an order,
watch it sit at `PENDING`, start Inventory Service, watch it complete.**

That single exercise demonstrates the entire argument for the architecture. In the synchronous version
of [Chapter 2](../02-domain/4-the-four-domains.md), an inventory outage would have *failed* that order
with a 500. Here it *delayed* it, the record waited on the topic, and the work resumed on recovery
with nothing lost and nobody notified.

It is also Scenario 5 (Consumer Outage and Recovery) in embryo — [Chapter 4](../04-reliability/README.md)
turns it into a controllable, repeatable demonstration rather than something you do by hand with two
terminals.

---

[← Correlation IDs](3-correlation-ids.md) · [Chapter 3 ↑](README.md) · [Chapter 4 — Reliability →](../04-reliability/README.md)


<hr style="page-break-after: always;"/>

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


# 4.2 — Retry and the dead-letter queue

[← Idempotent consumers](1-idempotent-consumers.md) · [Next: Inventory contention →](3-inventory-contention.md)

Idempotency handles a record arriving twice. This section handles a record that **cannot be
processed at all**.

---

## The problem: a failure blocks everything behind it

A consumer reads a record and throws. Its offset is not committed, so the record is redelivered. It
throws again. And again.

That is correct behavior for a *transient* failure — a lock conflict, a connection blip — and
catastrophic for a permanent one. Because Kafka delivers records from a partition **in order**, a
record that can never succeed blocks every record behind it in that partition, forever. One malformed
payload takes out every order whose key hashes to the same partition.

So two decisions are needed:

1. **Is this failure worth retrying?**
2. **What happens when it is not, or when retries run out?**

Neither has a universally right answer, which is why this is a designed policy rather than a default.

---

## Retryable vs. non-retryable

The distinction is not "how bad is it" but **"could the identical input succeed on a second attempt?"**

Some failures provably cannot:

```java
private static final List<Class<? extends Exception>> NON_RETRYABLE = List.of(
        UnsupportedEventVersionException.class,
        JacksonException.class,
        NonTransientDataAccessException.class,
        IllegalArgumentException.class);
```

Each earns its place:

- **`UnsupportedEventVersionException`** — required to be non-retryable by the event catalog's
  versioning rule: *"Retrying cannot fix a schema it doesn't understand."*
- **`JacksonException`** — the bytes are not the envelope we expect. *"The same bytes will not parse
  differently in 500ms."*
- **`NonTransientDataAccessException`** — Spring's own name for *"this will fail the same way if you
  try again"*: constraint violations, bad SQL, impossible domain data.
- **`IllegalArgumentException`** — a malformed value that reached the domain layer.

The third is the elegant one. Spring's data-access exception hierarchy already splits into
`TransientDataAccessException` and `NonTransientDataAccessException`, so classifying on the base type
inherits a distinction Spring maintains for every database driver.

And the sibling that is **deliberately absent**:

> Its sibling `TransientDataAccessException` (which `ObjectOptimisticLockingFailureException` extends)
> is deliberately *not* here.

An optimistic-lock conflict is the archetypal retryable failure — it means someone else committed, so
a fresh read will see different state. [Section 3](3-inventory-contention.md) depends on that.

### The default direction, and why

> Everything else defaults to retryable. That is the safer default for an *unrecognised* failure: a
> wrongly-retried permanent failure costs 3.5 seconds and still ends in the DLQ with its metadata
> intact, whereas a wrongly-non-retried transient failure discards real work.

**Asymmetric costs, so the default follows the cheaper mistake.** That reasoning transfers to almost
every classifier you will ever write, and stating it is what turns a default from an accident into a
decision.

---

## The retry budget

```java
private static final int MAX_RETRIES = 3;
private static final long INITIAL_INTERVAL_MS = 500L;
private static final double MULTIPLIER = 2.0;
private static final long MAX_INTERVAL_MS = 2_000L;
```

Three retries after the initial delivery — four deliveries, spaced 0.5s / 1s / 2s, about 3.5 seconds
total.

These numbers are argued rather than picked, and the argument is the useful part:

> - Retrying blocks the partition — every later record for those orders waits. A budget an order of
>   magnitude larger would turn one poison record into a visible outage of the whole partition, which
>   is a worse failure than dead-lettering promptly.
> - The retryable class here is genuinely transient (lock contention, a connection blip); such
>   failures clear in milliseconds-to-seconds, so extra attempts buy nothing.
> - Scenario 6 asks a reviewer to *watch* retries happen and then see the record land in the DLQ.
>   3.5 seconds is long enough to see in a UI and short enough to sit through.

Two general points and one specific to this project. The first is the one people miss: **a retry
budget is a decision about how long you are willing to block a partition**, not just about how many
chances to give a record.

**Exponential, not fixed**, and the reason is sharper than "exponential is standard":

> the point of backoff is to sample a different moment, not to wait a fixed amount.

A retry 500ms later and a retry 3.5s later are asking about *different* system states. Fixed
intervals ask the same question repeatedly.

**Jitter is switched off, deliberately:**

```java
backOff.setJitter(0L); // deterministic spacing: the retry timing is part of what Scenario 6 shows
```

Note that this runs *against* general practice — jitter normally prevents synchronized retry storms.
Here the retries are being demonstrated, and a reviewer watching a timeline should see 0.5, 1, 2, not
0.4, 1.3, 1.8. A deliberate deviation with a stated reason, which is the only acceptable kind.

---

## Wiring it up

The whole of a service's share:

```java
@Configuration
public class InventoryKafkaReliabilityConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(ConsumerErrorHandlerFactory factory) {
        return factory.create(KafkaTopics.INVENTORY_DLQ);
    }
}
```

Nine lines per service. The policy lives once in `common`; the only per-service input is the
destination topic.

> Shared rather than copy-pasted because the parts that matter — which exceptions are worth retrying,
> how many times, and what metadata the dead-lettered record carries — must be the same in all four
> services for the DLQ inspector and the reliability claims to mean one thing.

That is the test for whether something belongs in a shared module: **would divergence make a
system-level claim false?** If yes, share it. If no, duplication is usually cheaper than coupling.

One Spring Boot detail makes it this small:

> Spring Boot's Kafka auto-configuration applies a single `CommonErrorHandler` bean to the listener
> container factory, so this one bean covers both of this service's listeners without either of them
> having to name it.

And the routing rule from [Chapter 1](../01-design-contract/2-the-event-contract.md), restated where
it applies:

> Both dead-letter to `inventory.dlq` even though they consume `orders.events` and `payments.events`
> respectively: the DLQ belongs to the failing consumer, not to the publisher of the record it choked
> on.

---

## The recoverer

```java
DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
        kafkaTemplate,
        // Partition -1 leaves the partition unset on the outbound record, so the producer
        // partitions it by key (= orderId), keeping per-order ordering inside the DLQ too.
        (record, exception) -> new TopicPartition(dlqTopic, -1));
```

`DeadLetterPublishingRecoverer` runs when the retry budget is exhausted (or the failure is classified
non-retryable), publishing the record to the destination the resolver picks.

**Partition `-1`** is a small decision with two justifications. Spring's default resolver sends to the
*same partition number* the record came from, which assumes the DLQ has at least as many partitions
as every source topic. Leaving it unset lets the producer partition by key, which keeps per-order
ordering inside the DLQ too — so a DLQ inspector reading one partition sees an order's failures in
order.

### The failure metadata

Spring already writes `kafka_dlt-*` headers: original topic, partition, offset, timestamp, consumer
group, exception class, message, stack trace. This project adds five more:

```java
public static final String DELIVERY_ATTEMPTS  = "x-delivery-attempts";
public static final String FAILURE_CLASS      = "x-failure-class";
public static final String FAILURE_MESSAGE    = "x-failure-message";
public static final String RETRYABLE          = "x-failure-retryable";
public static final String DEAD_LETTERED_AT   = "x-dead-lettered-at";
```

Each exists for a reason:

**`x-delivery-attempts`** — Spring does not provide it, and Scenario 6 requires *"the error
inspectable and the retry count shown"*. Supplied by a `DeliveryAttemptTracker` registered as a retry
listener on the error handler.

**`x-failure-class` / `x-failure-message`** — the **root cause**, as opposed to Spring's own
`kafka_dlt-exception-fqcn`, which reports the wrapper:

> The wrapper is the same for every failure and so says nothing; the root cause is the answer to "why
> did this record fail", and it is what the classifier actually acted on.

**`x-failure-retryable`** — which arm the classifier took:

> so the DLQ record can state which arm it took rather than leaving a reader to infer it from a count.

That is a genuinely good instinct. A reader could *deduce* it from `x-delivery-attempts` being 1 versus
4 — but deduction requires knowing the policy, and the policy can change. Record the decision, not
just its evidence.

**`x-dead-lettered-at`** — when it was dead-lettered, as distinct from when it was produced.

And the record goes to the log as well as the header:

```java
log.error("Dead-lettering {}-{}@{} to {} after {} delivery attempt(s) ({} failure)",
        record.topic(), record.partition(), record.offset(), dlqTopic, attempts,
        retryable ? "retryable" : "non-retryable", exception);
```

> an operator looking at the service's own logs should not have to go and read the dead-letter topic
> to find out why.

Note the exception is passed as the trailing argument — SLF4J attaches the full stack trace rather
than calling `toString()` on it.

### Classification, exposed twice

```java
public static boolean isRetryable(Throwable throwable) {
    for (Throwable t = throwable; t != null; t = t.getCause() == t ? null : t.getCause()) {
        for (Class<? extends Exception> nonRetryable : NON_RETRYABLE) {
            if (nonRetryable.isInstance(t)) return false;
        }
    }
    return true;
}
```

**Causes are walked**, because a listener exception arrives wrapped in
`ListenerExecutionFailedException`. Checking only the top-level type would classify every failure as
retryable.

Note the `t.getCause() == t ? null : t.getCause()` guard — a self-referential cause would otherwise
loop forever. Rare, and cheap to defend against.

---

## What the DLQ is and is not

**It is** a place a record goes so it stops blocking the partition, carrying enough metadata to
diagnose it later.

**It is not** an automatic recovery mechanism. Nothing reprocesses a DLQ record. Getting a
dead-lettered order moving again is a human decision, and the reliability doc is explicit that this
is a known boundary.

**One thing it *is* wired to**, though — and this is Sprint 2's addition:

```java
static final String DEAD_LETTER_LISTENER_ID = "orders-dlq";
static final String DEAD_LETTER_GROUP_ID = "order-service-dlq";
```

`OrderDeadLetterConsumer` listens on Order Service's own `orders.dlq` and marks the order `FAILED` —
transition 9 of the state machine. That closes the loop the original design left open: an order whose
event could not be processed no longer sits at whatever status it happened to reach, pretending to be
in progress.

Two details in that listener are worth stealing:

**A distinct consumer group** from the domain listeners, so it does not compete for partitions or
offsets with them.

**No ledger claim**, and the reason is sharp:

> a dead-letter record has no reliable `eventId` to key one on — the poison-bytes case that is the
> most common reason a record reaches the DLQ is exactly the case where the envelope may not parse at
> all.

Idempotency instead comes from the state machine's own terminal-state guard: once an order is
`FAILED`, a redelivered dead-letter record for it classifies as stale and writes nothing. **Reusing
an existing invariant instead of adding a second mechanism** — see
[section 4](4-out-of-order-transitions.md) for how that guard works.

> **We got this wrong.** Transition 9 was in the frozen state machine from Phase 0 and went
> unimplemented until Sprint 2. ADR-009's accepted costs named it as a known gap rather than letting
> it pass silently. [Chapter 10](../10-retrospective/README.md).

---

## Demonstrating it

**Scenario 6 (Poison Message / DLQ)** publishes a record that cannot be processed and asserts it
lands in the expected DLQ. The test must publish a **genuinely** malformed record — which is why the
test base injects a raw `KafkaTemplate` alongside `EventPublisher`:

> For publishing records `EventPublisher` deliberately cannot produce — an envelope with an
> `eventVersion` the codec rejects, or a payload that will not deserialize. Phase 4's poison-message
> scenario needs a genuinely malformed record on the wire, not a mocked failure.

Assert on all three: the record is on the DLQ topic, `x-failure-retryable` says `false`,
`x-delivery-attempts` says `1`. That last one proves the *non-retryable path* was taken rather than
the record merely having failed four times — the same "assert the dangerous path was taken" principle
from [Chapter 2](../02-domain/5-testing.md).

---

[← Idempotent consumers](1-idempotent-consumers.md) · [Next: Inventory contention →](3-inventory-contention.md)


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


# 4.4 — Out-of-order transitions

[← Inventory contention](3-inventory-contention.md) · [Next: Pausing consumers →](5-pausing-consumers.md)

The third of [Chapter 3](../03-kafka-and-services/README.md)'s open gaps, and the one that took
longest to find. This is ADR-009, and it is the best story in the project.

---

## The problem, stated exactly

From [Chapter 1](../01-design-contract/2-the-event-contract.md), repeated because everything here
follows from it:

> Kafka guarantees ordering **within a partition** of **one** topic. It guarantees nothing between
> topics.

Now look at where Order Service's status comes from. ADR-009 opens with it:

> Order status is owned exclusively by Order Service, but inside that service it is written by
> **three independently-consumed Kafka topics** — `inventory.events`, `payments.events` and
> `fulfillment.events` — each with its own listener, its own partitions and its own offsets.

Three listeners, three topics, three offset positions, no ordering relationship between any of them.

And the topic that makes it bite is `payments.events`, because of a fan-out that is otherwise a
feature:

- **Order Service** consumes `PaymentAuthorized` → `PAID` → `FULFILLMENT_PENDING`.
- **Fulfillment Service** consumes the same `PaymentAuthorized` → creates a shipment → publishes
  `ShipmentCreated` to `fulfillment.events`.
- **Order Service** consumes `ShipmentCreated` → `FULFILLED`.

Two of Order Service's three inputs are racing. `ShipmentCreated` travels a *different topic* from
`PaymentAuthorized`, and nothing sequences them.

## What actually happened

> `OrderPersistence` wrote whatever status its caller handed it, reading the order row only to mutate
> it. The transition table of `docs/order-state-machine.md` §3 existed as prose and as documentation
> comments; **no code consulted it**.

So under load, in Phase 10:

1. `PaymentAuthorized` and `ShipmentCreated` both arrive at Order Service, on different topics.
2. `ShipmentCreated` is processed **first**, writing `FULFILLED` straight out of `PAYMENT_PENDING` —
   skipping `PAID` and `FULFILLMENT_PENDING` entirely.
3. The late `PaymentAuthorized` is then processed, and **overwrites the terminal `FULFILLED` back to
   `FULFILLMENT_PENDING`.**

A completed order silently reverting to in-progress. It required real concurrency to reproduce and
never appeared in a functional test, because in a functional test the events arrive in the order you
sent them.

> **We got this wrong.** Found in Phase 10's scaling work — by load, not by review. The full story is
> in [Chapter 10](../10-retrospective/README.md). Everything below is the fix, and the build-along
> builds it from the start.

---

## Three things the fix needs

Notice the bug has three separable failure modes, and a single guard would not address all of them:

1. A transition that **moves the order backwards** must be dropped.
2. A transition that **leaves a terminal state** must never be applied.
3. A transition that is **legitimately in the future** — its predecessor simply has not arrived yet —
   must *not* be dropped. `ShipmentCreated` really did happen. Discarding it would strand the order at
   `FULFILLMENT_PENDING` forever.

That third case is what makes this more than a validity check. **Some invalid-right-now transitions
are valid later**, and telling those apart is the whole design.

---

## The classifier

```java
enum Verdict { APPLY, STALE, AHEAD }

static Verdict classify(OrderStatus current, OrderStatus target) {
    if (VALID_PREDECESSORS.getOrDefault(target, Set.of()).contains(current)) {
        return Verdict.APPLY;
    }
    // Already there: a redelivery that got past the ledger, or a deferred row drained twice.
    if (current == target) {
        return Verdict.STALE;
    }
    // Nothing leaves a terminal state. This is the half of the guard that stops a late
    // PaymentAuthorized from reverting FULFILLED.
    if (current.isTerminal()) {
        return Verdict.STALE;
    }
    Integer currentProgress = PROGRESS.get(current);
    Integer targetProgress = PROGRESS.get(target);
    if (currentProgress != null && targetProgress != null && targetProgress < currentProgress) {
        return Verdict.STALE;
    }
    return Verdict.AHEAD;
}
```

Two data structures behind it, and the distinction between them matters:

**`VALID_PREDECESSORS`** — the frozen transition table from
[Chapter 1](../01-design-contract/3-state-and-api-contracts.md), transcribed with its row numbers as
comments. This *is* the contract.

**`PROGRESS`** — a monotonic ordinal along the happy path (`PENDING`=1 … `FULFILLED`=6), and its
Javadoc is careful to say what it is not:

> used only to tell an *earlier* transition arriving late (which must never undo later progress) from
> a *later* transition arriving early (which is legitimate and merely premature). It is **not part of
> the frozen contract**; it is a derived ordering over §3's happy-path chain.

Marking a derived helper as *not the contract* is a small act of documentation hygiene that pays off
the next time someone edits the state machine. Absent for the three failure outcomes, which branch off
the chain rather than sitting on it.

The order of the checks is deliberate: valid → already there → terminal → backwards → otherwise
premature. `AHEAD` is the fallthrough, which is the safe direction — an unclassifiable transition gets
parked and re-examined rather than discarded.

---

## Deferral

```java
case AHEAD -> {
    defer(order, status, sourceEventId);
    yield StatusTransitionResult.asDeferred();
}
```

A `deferred_transitions` row (migration `V5`) parks the target status and its `sourceEventId`. The
event has been consumed, its offset will be committed, its ledger row is written — the *work* is
durable even though the *transition* has not been applied.

That last point is why deferral needs a table rather than a queue or an in-memory buffer: the record
is gone from Kafka's perspective, so if the parked transition is lost, nothing will ever re-deliver
it.

## Draining

```java
private void drainDeferred(OrderEntity order) {
    for (int pass = 0; pass < MAX_DRAIN_PASSES; pass++) {
        boolean appliedAny = false;
        List<DeferredTransitionEntity> parked = deferredTransitionRepository
                .findByOrderIdAndStatusOrderByIdAsc(order.getId(), DeferredTransitionStatus.PENDING);
        if (parked.isEmpty()) return;

        for (DeferredTransitionEntity deferred : parked) {
            switch (OrderTransitions.classify(order.getStatus(), deferred.getTargetStatus())) {
                case APPLY -> {
                    writeStatus(order, deferred.getTargetStatus(), deferred.getSourceEventId());
                    deferred.resolve(DeferredTransitionStatus.APPLIED, Instant.now());
                    appliedAny = true;
                }
                case STALE -> {
                    log.warn("Abandoning deferred {} for order {}: order is at {}, which it can never follow", …);
                    deferred.resolve(DeferredTransitionStatus.ABANDONED, Instant.now());
                }
                case AHEAD -> { /* still waiting on its predecessor — leave it parked */ }
            }
        }
        if (!appliedAny) return;
    }
    log.error("Deferred-transition drain for order {} did not settle in {} passes; …", …);
}
```

**Every successful transition drains.** Applying a status may unblock something parked, so the drain
runs after every `APPLY`.

**The loop repeats** because applying one parked transition can unblock another. Concretely: with
`PAID` and `FULFILLED` both parked, applying `PAID` enables `FULFILLMENT_PENDING`, which enables
`FULFILLED` — a chain that resolves in one drain across several passes.

**It uses the same classifier**, so parked transitions are subject to exactly the same rules as
arriving ones. One definition of validity, two entry points.

**Three terminal outcomes for a parked row** — `APPLIED`, `ABANDONED`, or still `PENDING`. The
`ABANDONED` case matters: a transition can become permanently impossible, and marking it explicitly
means the table does not accumulate rows nobody can explain.

**The pass bound:**

> Safety stop on the drain loop. [...] this bounds it well above the longest legal chain (six
> statuses) so a hypothetical cycle cannot spin a transaction forever.

10 passes against a maximum legal chain of 6, and an `ERROR` log if it ever hits the bound. **A bound
derived from the domain, plus loud failure if the derivation was wrong** — the same shape as
[section 3](3-inventory-contention.md)'s 25 attempts.

---

## The lock the whole thing rests on

`classify` reads the current status and then decides. That is check-then-act, and it has exactly the
same hazard as the inventory race — except here the answer is pessimistic rather than optimistic:

```java
/**
 * {@code SELECT ... FOR UPDATE} on one order row. Every status transition takes this lock first
 * (ADR-009), which serializes the three independently-consumed topics that write
 * {@code orders.status} against each other for a given order — without it, "read current status,
 * decide, write" is a check-then-act race between two consumer threads (or two Order Service
 * replicas) and the guard could be evaluated against a status another transaction is about to
 * change. Per-order only: different orders never contend.
 */
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select o from OrderEntity o where o.id = :id")
Optional<OrderEntity> findByIdForUpdate(@Param("id") String id);
```

**Pessimistic here, optimistic for inventory** — and the reason is the access pattern, not a
preference:

- Inventory conflicts are **rare** (four SKUs, most orders not competing) and the operation is a
  simple increment. Detect-and-retry costs nothing on the uncontended path.
- Order-status conflicts are **expected** — three topics deliberately write the same row, for every
  single order. And the operation is not an increment but a multi-step decision that reads state,
  classifies, writes, and drains a second table. Retrying all of that on conflict would be far more
  expensive than waiting.

*"Per-order only: different orders never contend"* is what keeps the lock cheap. It serializes the
three listeners for **one** order, not the topic.

Two orders never wait on each other, so this does not become a throughput ceiling — which
[Chapter 8](../08-observability-and-scaling/README.md)'s high-volume scenario would have found
immediately if it did.

---

## Retention, again

`deferred_transitions` accumulates resolved rows, so Sprint 2 added
`DeferredTransitionRetentionScheduler` alongside the `processed_events` one — same 7-day window,
purging only `APPLIED` and `ABANDONED` rows.

**Only resolved rows.** A `PENDING` row is still waiting for a predecessor that may yet arrive;
purging it would silently drop a real transition.

---

## What this buys, precisely

> an order whose events arrive out of order now converges to the correct terminal state *slightly
> later* rather than jumping ahead, and an invalid transition is now dropped at WARN instead of being
> written.

**Converges** is the right word and worth using. The system is eventually consistent by design; this
makes the convergence *monotonic*. An order's status never goes backwards, never skips a step, and
never leaves a terminal state — but it may lag reality briefly while a predecessor is in flight.

That is a much better property than "always correct instantly," because the latter is not achievable
across three independently-consumed topics without either global ordering (which would cost the
fan-out) or synchronous coordination (which would cost the architecture).

One consequence the changelog flags for anyone writing tests against this:

> Anything that relied on Order Service accepting a status write from an arbitrary current state
> (including tests that skip `InventoryReserved`) will need to drive the real sequence.

Two existing tests asserted an invalid state-machine path and had to be corrected. **Tests can encode
a bug**, and a fix that breaks tests is not automatically a regression.

---

## Why not retry instead?

The obvious alternative — let the premature transition fail and be redelivered — was considered and
rejected:

> Retry/backoff was rejected as the mechanism: the existing budget (~3.5 s) is shorter than the
> observed race, and it blocks the partition for what is not a failure.

Two independent reasons, and the second is the stronger one. **A premature transition is not a
failure.** Nothing went wrong; two things simply arrived in an unhelpful order. Routing it through
error handling would block a partition, consume a retry budget, and eventually dead-letter a perfectly
valid event — treating a normal consequence of the architecture as a fault.

---

[← Inventory contention](3-inventory-contention.md) · [Next: Pausing consumers →](5-pausing-consumers.md)


# 4.5 — Pausing consumers

[← Out-of-order transitions](4-out-of-order-transitions.md) · [Chapter 4 ↑](README.md)

A short section about a small class, included because it is the clearest illustration in the project
of what "the scenarios must be real" actually costs — and buys.

---

## What Scenario 5 demands

Scenario 5 (Consumer Outage and Recovery) stops a consumer, lets a backlog build, restarts it, and
shows the backlog drain. `docs/scenarios.md` is unusually specific about the implementation:

> a genuine Spring Kafka listener-container pause, not a discarded message or a simulated delay.

Three ways to make the UI *look* right, two of which are forbidden:

- **Drop the records** while "paused." The UI shows a gap. It is a lie — the records are gone, and
  nothing drains on resume.
- **Buffer them in memory** and replay on resume. Closer, and still a lie: the backlog is in your
  heap, not on the topic, and it dies with the process.
- **Actually pause the consumer.** Records stay on the topic, the offset stays where it is, and the
  consumer resumes from it.

Only the third demonstrates anything about Kafka, and the difference is invisible in a screenshot —
which is exactly why the rule is written down.

---

## `pause()`, not `stop()`

```java
public ConsumerState pause(String consumerName) {
    MessageListenerContainer container = require(consumerName);
    container.pause();
    awaitPausedState(container, true);
    return toState(container);
}
```

`KafkaListenerEndpointRegistry` is Spring Kafka's registry of listener containers, keyed by the `id`
you gave each `@KafkaListener` — the reason [Chapter 3](../03-kafka-and-services/README.md) insisted
those be explicit, stable constants.

Both `stop()` and `pause()` halt processing. The choice between them is the interesting part:

> `stop()` would also halt processing, but it leaves the consumer group, triggering a rebalance on the
> way out and another on the way back — so a multi-instance deployment would reassign the paused
> instance's partitions to a running one and **quietly process the "backlog" anyway**, which is the
> opposite of what the scenario demonstrates.

A demo that works on your laptop and silently stops working at two replicas. It would not error; the
backlog would simply never appear, and you would be left wondering why the scenario looked different
in production.

`pause()` keeps the consumer in the group with its partitions assigned, and just stops delivering
records. It also matches the frozen OpenAPI vocabulary, where the field is `paused`.

---

## Waiting for the pause to take effect

```java
private static final Duration EFFECTIVE_TIMEOUT = Duration.ofSeconds(10);
private static final long POLL_PARK_NANOS = 20_000_000L; // 20 ms

private static void awaitPausedState(MessageListenerContainer container, boolean paused) {
    long deadline = System.nanoTime() + EFFECTIVE_TIMEOUT.toNanos();
    while (container.isContainerPaused() != paused && System.nanoTime() < deadline) {
        LockSupport.parkNanos(POLL_PARK_NANOS);
    }
    if (container.isContainerPaused() != paused) {
        log.warn("Kafka listener '{}' did not reach paused={} within {}; reporting its actual state", …);
    }
}
```

`container.pause()` is a **request**. It takes effect on the container's next poll, so the method
returns before anything has actually paused.

That gap is a real bug in waiting:

> A pause takes effect on the container's next poll, so the call returns only once the pause is real —
> otherwise Scenario 5 could publish its first order into the gap and see it processed by a consumer
> it believes it has already paused.

A scenario that pauses a consumer and immediately creates an order would race the pause, and the
symptom would be *intermittent*: the scenario mostly works, and occasionally the first order sails
through. The worst kind of bug to chase after the fact.

Two properties of the wait are worth copying:

**It is bounded.** Ten seconds, then give up rather than hang.

**On timeout it reports the observed state, not an optimistic one.** The method returns
`toState(container)` — whatever the container actually says — rather than assuming success. A demo
control that lies about having paused something is worse than one that admits it failed.

---

## Idempotent by contract

```java
/** Idempotent: pausing an already-paused listener succeeds, per the frozen OpenAPI description. */
```

Pausing a paused listener succeeds. Resuming a running one succeeds. This is the OpenAPI contract
being honoured rather than a convenience — and it matters because `POST /demo/reset` calls resume
unconditionally to clean up after a run, without needing to know what state anything is in.

---

## Why this is `/demo`, and why that matters later

`ConsumerControl` lives in `common`, and each service exposes it through a `DemoConsumerController`
under `/demo/consumers` — never mixed into `/api`.

ADR-002 explains why the control has to live *inside* the service it affects, even though that puts
demo code in every service:

> a listener cannot be paused from outside its own process anyway. The `/demo` prefix inside each
> service is the smaller compromise: the demo code is local to the service, but visibly quarantined.

**Scenario Service orchestrates but cannot execute.** It makes an HTTP call to
`POST /demo/consumers/{id}/pause` on the target service, which is the one synchronous
service-to-service call the architecture permits — control plane, not workflow.

> **Where this pays off.** [Chapter 9](../09-production/README.md) puts the demo on the public
> internet. `/demo/consumers` is exactly the endpoint you do **not** want a stranger reaching, and the
> ingress allowlist keeps it cluster-internal while the scenario endpoints a visitor needs stay
> routable. That is only possible because the split was made by construction in Phase 0.

Also worth remembering from ADR-002:

> Demo state is real state. A run that fails halfway can leave a paused listener [...] behind, which is
> why `POST /demo/reset` exists and why it reports what it actually reset.

A genuinely paused consumer stays paused. That is the price of it being real, and the reset endpoint
is the thing that pays it.

---

## What it demonstrates

Stop the consumer, create orders, watch them sit at `PENDING`, resume, watch them complete.

The observable that makes it land is **consumer lag** — the number of records between the consumer's
committed offset and the end of the partition. It climbs while paused and drains on resume, and it is
a real Kafka metric rather than an application counter.
[Chapter 5](../05-scenarios-and-frontend/README.md) surfaces it in the System Health page;
[Chapter 8](../08-observability-and-scaling/README.md) uses the same number to show what adding
replicas does.

This is also the same exercise as [Chapter 3](../03-kafka-and-services/README.md)'s exit criterion —
stop Inventory Service, create an order, start it again — turned into something repeatable and
observable rather than something you do by hand with two terminals. That is the whole idea of the
scenario engine, which is [Chapter 5](../05-scenarios-and-frontend/README.md).

---

[← Out-of-order transitions](4-out-of-order-transitions.md) · [Chapter 4 ↑](README.md)


<hr style="page-break-after: always;"/>

# Chapter 5 — The scenario engine and the live frontend

**Build history:** Phase 5 — `b363d42 add scenario service and demo frontend` (and `0d7b4ea`, its
follow-up).

The chapter where the project becomes something you can show someone. Four services and a Kafka
cluster are not a portfolio piece; a page where a stranger clicks "Poison Message" and watches a real
record fail, retry, and land in a dead-letter topic is.

Phase 5's exit criterion is the demanding one:

> A reviewer can understand and exercise the system without reading the source code.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [The scenario service](1-the-scenario-service.md) | Why a fifth service exists, the run lifecycle, `@Async` and the proxy trap, the runner abstraction and toolkit, observing real outcomes instead of sleeping |
| 2 | [Server-Sent Events](2-server-sent-events.md) | ADR-003 and why not WebSockets, the two streams, `SseEmitter` mechanics, per-emitter synchronization, cleanup that can itself throw, the client wrapper |
| 3 | [The eight scenarios](3-the-eight-scenarios.md) | Each scenario, what it demonstrates, and how it stays real; `POST /demo/reset` and why it needed its own inventory operation |
| 4 | [Observing the system](4-observing-the-system.md) | The event projection and its honesty boundary, real consumer lag from the admin API, the interleaved run timeline, System Health |
| 5 | [The console](5-the-console.md) | React Router and why it arrived now, query-client defaults tuned for a console, and the Mermaid renderer's two real bugs |

---

## The fifth service

The Phase 0–3 story is about four services. This chapter adds a fifth that owns **no business data**,
participates in **no workflow**, and exists entirely because
[ADR-002](../01-design-contract/3-state-and-api-contracts.md) refused to let a `forcePaymentFailure`
flag onto the order API.

It is also the largest module in the repository — 71 Java files against Order Service's 56. The
demonstration is bigger than any one domain, which is exactly what you would expect of a project whose
product is the demonstration.

---

## Two ideas worth carrying out

**Adding an observer costs the observed nothing.** The event projection reads all eight topics in its
own consumer group. No producer changed, no consumer noticed, no delivery was diverted. That is the
log-versus-queue property from [Chapter 1](../01-design-contract/1-boundaries-and-ownership.md)
paying for itself in a feature nobody designed for in Phase 0.

**Refusing to display what you cannot observe.** The Event Explorer shows publication but not
consumption, because consumption happens inside another service's transaction against a schema this
one may not read. A fabricated "consumed at, 43ms, 0 retries" would look better and be false. Being
able to explain what a page deliberately omits, and why, is a stronger answer than a richer page you
cannot defend.

---

## Build it yourself

**Scenario Service** — [section 1](1-the-scenario-service.md)

1. A fifth module, port 8085, schema `scenario_service`. `spring-boot-starter-web`, `-data-jpa`,
   `-kafka`, plus the `common` dependency.
2. `V1__scenario_runs.sql` (`scenario_runs`, `scenario_run_timeline`) and `V2__events.sql`.
3. HTTP clients for the other four services, base URLs from configuration
   (`ServiceUrlsProperties`), built on `RestClient`.
4. `ScenarioRunner` interface, `ScenarioToolkit` parameter object, `AbstractScenarioRunner` with
   `createOrder` and `recordHttp` helpers that record the **real** status code.
5. `ScenarioExecutionService` (validate, mint `runId` + `correlationId`, persist, return) and a
   **separate** `ScenarioRunExecutor` bean carrying `@Async` — self-invocation would run it
   synchronously.
6. `CorrelationIdHolder.runInScope` around the whole run, logging the start line **inside** the scope.
7. `OrderStatusWatcher` polling the real order API; `ConsumerLagService` over `AdminClient`, failing
   soft.
8. Controllers under `/demo` only: `/demo/scenarios`, `/demo/scenario-runs`, `/demo/events`,
   `/demo/reset`.

**SSE** — [section 2](2-server-sent-events.md)

9. `GET /api/orders/stream` returning an `SseEmitter`, backed by a registry that broadcasts with a
   per-connection `orderId` filter, registers all three lifecycle callbacks, runs a 15-second
   keep-alive on a daemon thread, **synchronizes every send on the emitter instance**, and wraps
   `completeWithError` so cleanup cannot throw.
10. `GET /demo/scenario-runs/{runId}/stream` backed by `RunEventHub`, keyed by run, emitting
    `timeline-entry` and `run-status` — **only after the underlying write has committed**.
11. `TimelineRecorder`: persist, then publish; per-run synchronized sequence assignment; `detail` as
    an open map containing only observed fields.
12. The `void` `AsyncRequestNotUsableException` handler in `GlobalExceptionHandler`.
13. Client: a `subscribeToStream` wrapper over native `EventSource` returning an unsubscribe function,
    used as a `useEffect` cleanup.

**Scenarios** — [section 3](3-the-eight-scenarios.md)

14. Eight `ScenarioRunner` components, plus a `ScenarioCatalog` of definitions.
15. `POST /demo/reset` — seed inventory, clear demo state, resume every consumer, clear payment
    behavior — **reporting what it actually reset**.
16. `restoreForDemo` on Inventory Service, zeroing `reserved_quantity` and `available_quantity`
    together, bypassing the business guard that reset would otherwise trip.
17. `IdleResetScheduler`, defaulting to 15 minutes.

**Observation** — [section 4](4-observing-the-system.md)

18. `EventProjectionConsumer` on all eight topics in its **own** consumer group, recording only
    publication facts — no fabricated consumption phase.
19. `EventQueryService` and a paged `GET /demo/events`.

**Console** — [section 5](5-the-console.md)

20. React Router with seven top-level routes plus two nested detail routes; keep the existing pages
    unchanged behind thin route wrappers.
21. `QueryClient` defaults of `retry: 0` and `networkMode: 'always'`.
22. `MermaidDiagram` with a dynamic `import('mermaid')`, module-level one-time initialization, and a
    shared promise chain serializing every render.

**Done when:** a reviewer with only the URL can run all eight scenarios, watch each run's interleaved
timeline update live, browse every event with real topic/partition/offset, see a DLQ record's failure
metadata, pause a consumer and watch lag climb and drain, and read the architecture — without opening
the source.

---

## Next

[Section 1 — The scenario service](1-the-scenario-service.md).


# 5.1 — The scenario service

[← Chapter 5](README.md) · [Next: Server-Sent Events →](2-server-sent-events.md)

The fifth service, which owns no business data and exists entirely because of one Phase 0 decision.

---

## Why it exists

[ADR-002](../01-design-contract/3-state-and-api-contracts.md) forbade the cheap way to make a payment
fail:

```
POST /api/orders {"forcePaymentFailure": true}
```

Once the business API cannot carry scenario parameters, the scenario logic has to live *somewhere*.
That somewhere is Scenario Service, and it is a **control plane** — it orchestrates the system through
the same public APIs any client uses, and configures failure conditions through each service's own
`/demo` endpoints.

Two rules govern everything it does:

> **Scenario behavior is real.** Each scenario drives genuine HTTP requests, genuine Kafka records, and
> genuine persistence.
>
> **Scenarios use the normal APIs.** A scenario creates orders through `POST /api/orders`, the same
> endpoint any client uses.

It is also the one place the architecture permits synchronous service-to-service HTTP, and ADR-002 is
careful about why that is not a contradiction:

> Justified as control plane rather than workflow: no order transition depends on those calls, and a
> scenario has to be able to report a deterministic start.

**No order transition depends on those calls.** That is the test. The workflow is still entirely
event-driven; what is synchronous is the arrangement of the environment beforehand.

---

## The shape of a run

A run has three phases and they happen on two different threads.

```
POST /demo/scenarios/{name}
  → validate, mint runId + correlationId, persist the run as RUNNING, return  ← HTTP thread ends here
  → @Async: the runner executes                                               ← background thread
  → complete: mark COMPLETED or FAILED, publish the final status
```

The response returns as soon as the run exists, carrying a `runId` — the same asynchronous shape as
`POST /api/orders`, for the same reason. A scenario takes seconds; an HTTP request should not.

### The executor is its own bean

```java
/**
 * The actual background execution of one scenario run, on its own bean (so {@code @Async} goes
 * through a real Spring AOP proxy — self-invocation from {@link ScenarioExecutionService} would
 * silently run synchronously instead).
 */
@Component
public class ScenarioRunExecutor {

    @Async("scenarioExecutor")
    public void executeAsync(ScenarioRunner runner, String runId, String scenarioName, UUID correlationId) {
```

The **same proxy trap** as `@Transactional` from [Chapter 4](../04-reliability/README.md), with a
nastier failure mode. A self-invoked `@Transactional` method runs without a transaction; a
self-invoked `@Async` method runs **synchronously** — the HTTP request blocks for the whole scenario,
and nothing errors. It just gets slow, in a way that looks like a performance problem rather than a
missing annotation.

Any time you see a one-method class in a Spring codebase whose only apparent purpose is to be called
from elsewhere, this is usually why.

### Correlation scope is established here

```java
CorrelationIdHolder.runInScope(correlationId, () -> {
    // Phase 9: this is where a scenario's correlationId is minted — logged here, inside
    // the scope, so it's the first line of the trace a human would grep for across all
    // 5 services' logs.
    log.info("Starting scenario run {} ({})", runId, scenarioName);
    runner.run(ctx);
});
```

A third entry point for the [correlation-ID pattern](../patterns/correlation-id-propagation.md),
alongside the HTTP filter and the Kafka listeners. Scenario Service **mints** the ID for a run; every
order it creates carries it in an `X-Correlation-Id` header, and from there it propagates through
every event the workflow produces.

So one `correlationId` spans an entire scenario run — every HTTP call, every order, every event,
across five services. That is what makes a run's timeline assemblable at all.

Note also *where* the log line is: inside the scope, as the first statement, so it is the first line
of the trace rather than an untagged line just outside it.

---

## The runner abstraction

```java
public interface ScenarioRunner {
    String scenarioName();
    void run(ScenarioRunContext ctx);
}
```

Eight implementations, one per scenario, discovered by Spring and looked up by name. Adding a ninth
scenario means adding one `@Component` and a catalog entry.

`AbstractScenarioRunner` supplies the shared plumbing — and its Javadoc is honest about how thin it
is:

> Shared plumbing every `ScenarioRunner` needs — thin wrappers around HTTP-with-timeline-recording.

```java
protected OrderServiceClient.OrderCreationResult createOrder(
        String runId, String sku, int quantity, String customerId) {
    OrderServiceClient.OrderCreationResult result = orderServiceClient.createOrder(customerId, items);
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("statusCode", result.statusCode());
    if (result.orderId() != null) {
        detail.put("orderId", result.orderId());
    }
    timelineRecorder.append(runId, TimelineKind.HTTP, "POST /api/orders", detail);
    return result;
}
```

Two things about this small method.

**It records the real status code**, not an assumed one — *"the run timeline shows the HTTP 201
returning before the downstream events."* That ordering is the point of the timeline: it makes the
asynchrony visible. The 201 lands first, and the events that actually fulfil the order arrive
afterwards.

**`orderId` is added conditionally.** If the creation failed there is no order ID, and the field is
**absent** rather than null or empty. That is the timeline schema's own rule, and it recurs throughout
this service: *do not fabricate these fields.*

### The toolkit

```java
protected AbstractScenarioRunner(ScenarioToolkit toolkit) {
    this.orderServiceClient = toolkit.orderServiceClient();
    this.consumerControlClient = toolkit.consumerControlClient();
    // …six more
}
```

`ScenarioToolkit` is a **parameter object**: one injected dependency carrying eight collaborators, so
every runner has a one-argument constructor and adding a ninth collaborator does not touch eight
subclasses.

Worth noting as a deliberate exception to the rule from the
[DI primer](../technology/spring/dependency-injection.md) that a long constructor is useful pressure
against a class doing too much. That pressure is valuable when the constructor belongs to *one* class;
here it would just be eight identical edits. The exception is defensible precisely because the reason
for the original rule does not apply.

---

## Waiting for real outcomes

A scenario has to know when it is done, and there is exactly one dishonest way to do that: sleep for a
plausible duration and declare success.

Two components exist so it does not:

**`OrderStatusWatcher.awaitTerminal(runId, orderId)`** polls Order Service's real API until the order
reaches a terminal state, recording each observed transition as a timeline entry.

**`ConsumerLagService`** reads real consumer-group lag from the broker's admin API:

> Real consumer-group lag, read straight from the broker via the admin API — the same computation
> `kafka-consumer-groups.sh --describe` performs [...] so a scenario run can report a real, observed
> backlog instead of a guess, the same way `OrderStatusWatcher` reports real order-status transitions
> instead of a scripted wait.

And a detail in `totalLag` worth copying:

> Returns `0` if the group has no committed offsets yet [...] or if the broker call fails — this is a
> measurement aid, not a correctness gate, so a transient admin-API hiccup should not fail the
> scenario run.

**Classify each dependency as observation or correctness**, and let observation fail soft. A metrics
call that can fail a run is a metrics call that will eventually fail a run.

The same instinct appears in `DuplicateEventScenario`, which polls for its own event to appear in the
projection before republishing it — and *throws* rather than proceeding on a guess:

```java
throw new IllegalStateException(
        "OrderCreated for " + orderId + " was not observed by the event projection in time");
```

Here the dependency *is* correctness — you cannot republish a record you have not read — so it fails
hard, with a message that says exactly what did not happen.

---

## Its own persistence

Three tables in `scenario_service`: `scenario_runs`, `scenario_run_timeline`, and `events` (the
projection, [section 4](4-observing-the-system.md)).

Note what is **not** there: no orders, no reservations, no payments, no shipments. Scenario Service
observes and orchestrates; it owns no business data, and per ADR-004 it cannot read anyone else's
schema. Everything it knows, it learned through a public API or off a Kafka topic — which is a real
constraint with a real consequence, explored in [section 4](4-observing-the-system.md).

---

[← Chapter 5](README.md) · [Next: Server-Sent Events →](2-server-sent-events.md)


# 5.2 — Server-Sent Events

[← The scenario service](1-the-scenario-service.md) · [Next: The eight scenarios →](3-the-eight-scenarios.md)

[Chapter 2](../02-domain/6-the-first-frontend.md) left the frontend polling every four seconds. This
is the replacement, and it is ADR-003.

---

## Why polling is not good enough

The frontend polls because an order's status changes without the client doing anything. That works
and it visibly quantizes time — which matters here more than usual, because
`frontend-design.md`'s Scenario Run Detail page is *"explicitly a live timeline: entries should
appear as the run progresses, with sub-second timestamps visible."*

ADR-003's rejection of polling is precise:

> polling visibly quantizes it: a 1.4-second run rendered from 1-second polls loses exactly the
> ordering detail the page exists to show.

The whole point of the timeline is to show that the HTTP 201 returned *before* the events that
fulfilled the order. Sample that at 1Hz and the asynchrony — the thing being demonstrated —
disappears.

## Why SSE and not WebSockets

> What the client needs is narrow: order status transitions, scenario progress, timeline entries, and
> occasional health changes. **All server-to-client.** The client's own actions — create an order, run
> a scenario — are ordinary REST calls that already have a natural request/response shape.

Nothing needs to travel the other way. And the design docs anticipated the temptation:

> WebSockets are acceptable if chosen deliberately, "but should not be added simply for resume keyword
> value."

ADR-003's own summary is the answer to give if asked:

> Choosing WebSockets here would be the resume-keyword decision the design doc warns against — and
> being able to explain *why not* is worth more in an interview than having used them.

> **Primer — [Server-Sent Events](../technology/http/server-sent-events.md)**
> The wire format, `EventSource` and automatic reconnection, SSE vs. WebSockets, the HTTP/1.1
> connection limit, keep-alives, `SseEmitter` mechanics and its four traps, and deployment
> considerations.

Two other rejections worth remembering. **Long polling** — strictly worse for this shape, with no
compensating advantage. **Kafka straight to the browser** — rejected because *"it would put topic
structure into the client, give the frontend a consumer group to manage, and make the browser a
participant in the messaging topology instead of an observer of the system's own API."*

And one thing deliberately **not** streamed:

> Health data uses ordinary polling of Actuator endpoints, not a stream: it changes rarely and a stale
> health tile is a much smaller problem than a stale order timeline.

Choosing the mechanism per use case rather than adopting one everywhere.

---

## Two streams

| Endpoint | Events | Closes |
|---|---|---|
| `GET /api/orders/stream` (Order Service) | `order-status-changed`, optionally filtered by `orderId` | Never; 30-minute emitter timeout |
| `GET /demo/scenario-runs/{runId}/stream` (Scenario Service) | `timeline-entry`, `run-status` | When the run finishes |

Both frozen in Phase 0, both declaring `text/event-stream` and naming their event types in the
contract.

---

## The order stream

`OrderEventStreamRegistry` is where four separate concerns meet, and each is worth understanding.

### Broadcast, not subscribe

```java
/**
 * docs/openapi/order-service.yaml's {@code orderId} query parameter is a per-connection filter, not
 * a per-topic subscription — there is no Kafka-style partitioning here, so the simplest correct
 * thing is to broadcast every transition to every connected emitter and let each connection's own
 * {@code orderId} filter (recorded at {@link #register}) decide whether to forward it. Documented
 * judgment call: with the handful of concurrent demo viewers this project expects, broadcasting is
 * simpler and no less correct than maintaining a per-order subscriber index.
 */
```

An O(connections) scan per transition instead of an index. For a handful of viewers that is nothing,
and the alternative is a second data structure to keep consistent as connections come and go.

**A documented judgment call with its scope stated** — *"the handful of concurrent demo viewers this
project expects"* — is much better than either an unexplained simple implementation or a premature
index. It also tells you exactly when to revisit it.

### Emitters clean themselves up

```java
emitter.onCompletion(() -> emitters.remove(emitter));
emitter.onTimeout(() -> { emitter.complete(); emitters.remove(emitter); });
emitter.onError(ex -> emitters.remove(emitter));
```

All three callbacks, always. A client that closes a tab or loses its network is pruned without
intervention.

And the timeout is a feature rather than a limit:

> Generous but bounded: EventSource reconnects automatically, so a periodic forced reconnect is
> harmless and keeps a stuck/half-open TCP connection from pinning an emitter forever.

**Because the client reconnects for free, the server can afford to be ruthless.** A half-open TCP
connection — one where the peer is gone but no FIN arrived — is otherwise invisible and permanent.

### Keep-alives

```java
private static final Duration KEEP_ALIVE_INTERVAL = Duration.ofSeconds(15);
// …
emitter.send(SseEmitter.event().comment("keep-alive"));
```

A comment frame every 15 seconds on a dedicated daemon thread. Ignored by the parser, indistinguishable
from traffic to every proxy in between. An order stream can legitimately be silent for minutes, and
without this those connections would be closed by infrastructure and re-established on a cycle.

### Per-emitter synchronization

This is the interesting one, and it is a real bug that was really hit.

```java
synchronized (emitter) {
    try {
        emitter.send(SseEmitter.event().name("order-status-changed").data(message));
    } catch (IOException | IllegalStateException ex) {
        emitters.remove(emitter);
        completeWithErrorQuietly(emitter, ex);
    }
}
```

The class Javadoc states the hazard exactly:

> `SseEmitter#send` is not safe to call concurrently from multiple threads on the same emitter
> instance [...] any of Order Service's Kafka listener container threads (inventory/payment/fulfillment
> events each run on their own thread) can call `broadcast` for an unfiltered connection at roughly
> the same moment, and the keep-alive tick runs on yet another, independent thread on a fixed schedule
> [...] two threads' calls to the same `SseEmitter`'s underlying writer can interleave mid-write and
> corrupt the SSE byte stream — **observed as a client-side parser reconstructing a garbled or
> duplicated event.**

Note the symptom: **no server-side error at all.** The server logs look perfect. The bug appears in
the browser as a corrupted event, which is the kind of thing you spend a long time blaming on the
client.

Note also *what* is synchronized: **the emitter instance**, not the registry. One connection's writes
serialize; other connections are unaffected. A global lock would let one slow client block delivery to
everyone.

> **We got this wrong.** This is the SSE-under-concurrency defect from Sprint 2 goal 2. Three
> independent Kafka listener threads plus a scheduled keep-alive is four writers per connection, and
> the original implementation had no synchronization at all.
> [Chapter 10](../10-retrospective/README.md).

### Cleanup that can itself fail

```java
/**
 * {@code SseEmitter#completeWithError} can itself throw once the client's connection has broken
 * badly enough that the async context is no longer usable [...] letting a second exception escape
 * here does not just fail to clean up one dead SSE connection — it fails that unrelated caller's own
 * HTTP request (e.g. a {@code POST /api/orders} whose transaction had already committed
 * successfully).
 */
private void completeWithErrorQuietly(SseEmitter emitter, Exception cause) {
    try {
        emitter.completeWithError(cause);
    } catch (RuntimeException cleanupEx) {
        log.debug("Ignoring failure while completing an already-broken SSE emitter", cleanupEx);
    }
}
```

This is the subtlest bug in the project, and the causal chain is worth following slowly.

`broadcast` runs on the thread that produced the event — for a status change, the thread that just
committed the business transaction, because `OrderStatusStreamListener` is a
`@TransactionalEventListener` running in the caller's own thread.

So: someone's `POST /api/orders` commits successfully. The commit triggers a broadcast **on that same
thread**. One connected SSE client happens to be dead. The send throws, the cleanup throws again, and
the second exception propagates up through the broadcast into the POST's own request handling.

**A dead SSE connection belonging to a completely unrelated viewer fails a successful order creation.**
Coupling through a shared thread that nothing in either piece of code makes visible.

> **We got this wrong.** Found in Sprint 2's bug hunt under a high-volume concurrent-SSE-fan-out test.
> [Chapter 10](../10-retrospective/README.md).

The general lesson: **cleanup code on an error path must not be able to throw.** It runs when things
are already broken, which is exactly when the assumptions it relies on do not hold.

And the companion fix in `GlobalExceptionHandler` from
[Chapter 2](../02-domain/3-the-http-layer.md) — the `void` handler for
`AsyncRequestNotUsableException`, because no JSON error body can be written onto a committed
`text/event-stream` response.

---

## The run stream

`RunEventHub` is the same idea with a different keying:

```java
private final Map<String, List<SseEmitter>> emittersByRun = new ConcurrentHashMap<>();

public SseEmitter subscribe(String runId) {
    SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
    List<SseEmitter> emitters = emittersByRun.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
    // …the same three callbacks
}
```

Indexed **by run** rather than broadcast, because a run ID is a natural partition and a viewer only
ever cares about one. `CopyOnWriteArrayList` because the list is read on every emit and written only
when a viewer connects or leaves.

The discipline that matters is the ordering:

> Emits `timeline-entry` for every timeline row as it is actually persisted, and `run-status` on a
> status change — **never before the underlying write has committed**, matching the same
> real-time-only-after-it's-real discipline the OpenAPI doc asks for.

And `TimelineRecorder` enforces it:

> Appends one timeline entry at a time, persists it, then pushes it over SSE — in that order, so a
> subscriber never sees an entry that isn't durable yet.

**Persist, then publish.** A subscriber that sees an entry and then refreshes must not find it gone. It
is the same ordering constraint the transactional outbox exists to enforce for Kafka
([Chapter 6](../06-outbox/README.md)) — here the stream is not durable, so publishing *after* the
commit is sufficient.

`TimelineRecorder` also handles concurrency explicitly:

> Two independent threads append to the same run concurrently in general (the scenario harness thread
> for HTTP/STATE_CHANGE entries, and one or more Kafka listener threads for EVENT entries), so
> sequence assignment is synchronized per run id.

Per **run**, not globally — the same instinct as synchronizing per emitter.

---

## The client side

```ts
export function subscribeToStream(
  url: string,
  handlers: { onMessage: (eventName: string, data: string) => void; onOpen?: () => void; onError?: (event: Event) => void },
  eventNames: string[],
): () => void {
  const source = new EventSource(url);
  const listeners = eventNames.map((name) => {
    const listener = (event: MessageEvent) => handlers.onMessage(name, event.data);
    source.addEventListener(name, listener as EventListener);
    return { name, listener };
  });
  return () => {
    for (const { name, listener } of listeners) {
      source.removeEventListener(name, listener as EventListener);
    }
    source.close();
  };
}
```

Native `EventSource`, no library — a pinned decision. And the function's own Javadoc names its scope:

> Deliberately dumb: no reconnection/backoff policy beyond what EventSource itself does (it
> auto-reconnects on a dropped connection by default), no buffering. Callers that need a polling
> fallback [...] wire that themselves via `onError`.

**It returns an unsubscribe function**, which is exactly the shape React's `useEffect` cleanup wants:

```tsx
useEffect(() => subscribeToStream(url, handlers, ['order-status-changed']), [url]);
```

Without that, every remount leaks a connection — and with a 6-connection-per-origin browser limit, a
leak becomes a hang rather than a slowdown. See the
[React primer](../technology/react/components-and-hooks.md) on effect cleanup.

---

## What SSE does not solve

ADR-003 records the costs, and two are real:

> SSE is one-directional by definition. If a genuine need for client-to-server streaming appears, this
> decision has to be revisited rather than extended.

> Each open stream holds a server connection [...] it interacts with Kubernetes rolling updates (a pod
> restart drops streams, and `EventSource` reconnects to whichever pod it lands on).

That second one is not hypothetical — it is exactly what
[Chapter 9](../09-production/README.md) runs into on the deployed demo.

---

[← The scenario service](1-the-scenario-service.md) · [Next: The eight scenarios →](3-the-eight-scenarios.md)


# 5.3 — The eight scenarios

[← Server-Sent Events](2-server-sent-events.md) · [Next: Observing the system →](4-observing-the-system.md)

The scenarios *are* the portfolio. Everything else in the project exists so that these eight can be
real.

---

## What "real" means, operationally

Each scenario is one `@Component` implementing `ScenarioRunner`. Three rules apply without exception:

1. **Orders are created through `POST /api/orders`** — the same endpoint any client uses, with no
   scenario parameter.
2. **Failure conditions are configured through `/demo` endpoints**, before the order exists. The
   business path never knows why it is failing.
3. **Outcomes are observed, never assumed.** No scenario sleeps for a plausible duration and declares
   success.

---

## 1 — Standard Fulfillment → `FULFILLED`

The happy path. Set payment behavior to `DEFAULT_SUCCESS`, create an order, wait for a terminal state.

The value is not the outcome, which is unsurprising. It is the **timeline**: `POST /api/orders`
returning `201` first, then `OrderCreated`, `InventoryReserved`, `PaymentRequested`,
`PaymentAuthorized`, `ShipmentCreated` arriving after it, with sub-second timestamps.

That ordering *is* the demonstration. The HTTP response and the work are visibly separate things.

## 2 — Out of Stock → `REJECTED_OUT_OF_STOCK`

Order more of a SKU than exists. `SKU-002` is seeded at 5 for this.

Demonstrates a **business rejection with nothing to compensate**: no payment was attempted, so there
is nothing to undo. The contrast with Scenario 3 is the point of having both.

## 3 — Payment Rejection → `PAYMENT_FAILED` + stock released

Arm `PUT /demo/payment-behavior` with `REJECT`, then create an order.

The most architecturally interesting of the first three, because the interesting part happens *after*
the order reaches its terminal state. `PaymentRejected` goes to `payments.events`; Inventory Service
consumes it independently and releases the reservation, publishing `InventoryReleased`.

**Compensation, visible.** There is no transaction to roll back, so undoing the reservation is an
explicit event-driven step performed by a different service — and the timeline shows it happening
after the order was already `PAYMENT_FAILED`.

This scenario also carries ADR-002's acknowledged wart:

> Payment Service's rejection override is armed before its target order exists, so it is un-scoped for
> the duration of a run.

The honest cost of not passing a flag through the business request.

## 4 — Duplicate Event Delivery → no duplicate side effect

Run a normal order, then republish its real `OrderCreated` record — same `eventId`, same key, same
bytes:

> a genuine second Kafka record, not a UI label, so Inventory Service's own idempotency check (its
> `processed_events` ledger) is what actually suppresses the second reservation.

The scenario first **waits for its own event to appear in the projection**, so it republishes the
actual record rather than a reconstruction — and throws with a clear message if it never does.

Note the dependency on a Phase 0 envelope rule: *"a duplicate delivery of the same logical event
reuses the same `eventId`."* Without that, this scenario could not exist, and neither could
deduplication.

## 5 — Consumer Outage and Recovery → backlog processed after resume

Pause Inventory Service's listener via `/demo/consumers/{id}/pause`, create orders, watch them sit at
`PENDING`, resume, watch them complete.

A **genuine listener-container pause** ([Chapter 4](../04-reliability/5-pausing-consumers.md)) — not
dropped records, not a simulated delay. The records stay on the topic and the offset stays put.

The observable that makes it land is **consumer lag** ([section 4](4-observing-the-system.md)), read
from the broker rather than counted by the application: it climbs while paused, drains on resume.

## 6 — Poison Message / DLQ → record lands in the expected DLQ

Publish a record that cannot be processed — a bad `eventVersion`, or a payload that will not
deserialize — via a raw `KafkaTemplate`, because `EventPublisher` deliberately cannot produce one.

Demonstrates the whole failure path from [Chapter 4](../04-reliability/2-retry-and-dlq.md):
classification as non-retryable, immediate dead-lettering rather than four blocked deliveries, and
failure metadata a human can read. Since Sprint 2 it also demonstrates the consequence — the order
moves to `FAILED` rather than sitting at whatever status it had reached.

`x-delivery-attempts: 1` on the DLQ record is the tell that the **non-retryable** path was taken. A
retryable failure would show 4.

## 7 — Inventory Contention → reserved never exceeds available

Several orders race for `SKU-004`, seeded at 2 precisely for this.

The success condition is an **invariant**, not an outcome: some orders succeed, some are rejected, and
`reserved ≤ available` holds throughout. Which orders win is genuinely nondeterministic, and a
scenario asserting a specific winner would be asserting a race.

This is the visible face of [Chapter 4](../04-reliability/3-inventory-contention.md)'s optimistic-lock
retry loop — and the integration tests behind it additionally assert the conflict counter is non-zero,
so a run that happened not to race cannot pass silently.

## 8 — High-Volume Batch → throughput and lag observable

Create many orders quickly and report real throughput, latency, and consumer lag.

The only scenario whose success condition is a **measurement** rather than a state. It is what
[Chapter 8](../08-observability-and-scaling/README.md) uses to show what adding consumer replicas
actually does — and, just as usefully, what it does not do past three partitions.

---

## `POST /demo/reset` — not a scenario

Restores seed inventory, clears demo state, and resets any consumer pause or payment-behavior override
a run left behind.

It exists because of ADR-002's most under-appreciated line:

> **Demo state is real state.** A run that fails halfway can leave a paused listener or an armed
> rejection behind, which is why `POST /demo/reset` exists and why it reports what it actually reset.

**And why it reports what it actually reset** — the response says what was done rather than returning
an unconditional success.

Reset is also why `restoreForDemo` exists on Inventory Service as a separate operation from the
business `PUT`, and the reasoning is a nice illustration of a demo endpoint being *correctly* different
rather than sloppily so:

> Deliberately bypasses the `availableQuantity >= reservedQuantity` guard that
> `updateAvailableQuantity` enforces: reservations are only released on the payment-failure
> compensation path (never on successful fulfillment), so `reservedQuantity` accumulates without bound
> over a long-running demo and will routinely exceed any seed value. The production PUT correctly
> rejects that as an oversold state; this demo-only endpoint's whole job is to atomically zero both
> fields together so a "reset" actually means what it says.

The business rule is right, and inapplicable to a reset. Rather than weakening the business rule, a
demo-only operation with its own semantics lives under `/demo`. That is the `/api`–`/demo` split
earning its keep on a case nobody anticipated in Phase 0.

> **We got this wrong.** `restoreForDemo` did not exist initially, and reset used the business PUT —
> which meant reset silently failed with a 409 once `reservedQuantity` had accumulated, wedging the
> live demo. Commit `1a81745`. [Chapter 10](../10-retrospective/README.md).

An **idle reset scheduler** also runs reset automatically after 15 minutes of inactivity, so a public
demo left in a strange state by one visitor is clean for the next
([Chapter 9](../09-production/README.md)).

---

## Testing them

Phase 4's exit criterion again: *"Each advertised failure scenario is backed by an automated
integration test."*

Every scenario has one — `StandardOrderScenarioIntegrationTest`,
`DuplicateEventScenarioIntegrationTest`, `PoisonMessageScenarioIntegrationTest`, and so on — plus
`ScenarioConflictIntegrationTest` for concurrent-run handling and `DemoResetIntegrationTest` for
reset.

These live in Scenario Service and exercise the **orchestration**: that a run reaches `COMPLETED`,
that the timeline contains the expected entries in the expected order, and that the primary order
reaches the expected terminal status. The *mechanisms* — idempotency, DLQ routing, the contention
invariant — are tested in the services that own them
([Chapter 4](../04-reliability/README.md)).

**Two layers testing two different things.** A scenario test failing means the demonstration is
broken; a mechanism test failing means the system is. Keeping them separate means a failure tells you
which.

---

[← Server-Sent Events](2-server-sent-events.md) · [Next: Observing the system →](4-observing-the-system.md)


# 5.4 — Observing the system

[← The eight scenarios](3-the-eight-scenarios.md) · [Next: The console →](5-the-console.md)

Phase 5's exit criterion is unusually demanding:

> A reviewer can understand and exercise the system without reading the source code.

That requires showing what is happening *inside* a distributed system to someone who cannot attach a
debugger to it. Two mechanisms do the work — an event projection, and real broker metrics.

---

## The event projection

`EventProjectionConsumer` subscribes to **all eight topics** — four domain, four DLQ — and writes what
it sees into Scenario Service's own `events` table, backing the Event Explorer page.

### It is a separate consumer group, deliberately

> in its own consumer group (`scenario-service-projection`) so it never competes for partitions or
> offsets with a real domain consumer, and never affects delivery to the services that actually own
> the business logic.

This is the log-versus-queue property from
[Chapter 1](../01-design-contract/1-boundaries-and-ownership.md) being cashed in. **Adding an observer
to the system costs the observed nothing.** No producer changed, no existing consumer noticed, no
delivery was diverted. In a queue-based system the same feature would require either a fan-out
exchange configured in advance or a change at every publisher.

It is also why the observer being *down* is harmless: it falls behind and catches up, and the business
never knew.

### The honesty boundary

This is the best single illustration of the project's standards, and it is worth reading in full:

> **Honesty boundary (per the timeline schema's own rule, "do not fabricate these fields"):** this
> consumer records only what a direct Kafka consumer can genuinely observe about a *published* record
> — topic, partition, offset, eventId, correlationId, aggregateId, and the publisher (from the frozen
> topic-ownership table). It does deliberately **not** record a "consumed" phase, a `durationMs`, or a
> `retryCount`: those live inside each service's own `processed_events` row, which this service may
> not read (db-ownership.md's one-owner rule forbids cross-schema queries). Fabricating a
> plausible-looking consumption entry here would violate the "absent, not zero or empty" rule the
> timeline schema states explicitly.

Unpack what is being declined. A UI showing "consumed at 12:04:31.221, 43ms, 0 retries" would look
*better* than one showing only publication. It would also be **made up** — this service cannot observe
consumption, because consumption happens inside another service's transaction against another
service's schema.

Three separate principles converge on the same answer:

- **The ownership boundary is real.** ADR-004 forbids the cross-schema read that would supply the
  data.
- **Absent, not zero.** A missing field is honest; a zero is a claim.
- **Don't claim what you don't provide.** The same rule that bans the phrase "exactly-once."

The cost is a slightly less impressive Event Explorer. The benefit is that everything it shows is
true — and being able to explain *what the page deliberately does not show, and why* is a far stronger
answer than a richer page you cannot defend.

### The publisher lookup

```java
private static final Map<String, String> PRODUCER_BY_TOPIC = Map.of(
        KafkaTopics.ORDERS_EVENTS, "order-service",
        // …
        // A DLQ record is written by the failing *consumer*, not the original publisher
        // (event-catalog.md §2's routing-target note).
        KafkaTopics.INVENTORY_DLQ, "inventory-service",
        // …
);
```

Derived from the frozen topic-ownership table rather than from anything in the record. That is only
possible because *"a service publishes only to its own domain topic"* is a rule — the topic name is
sufficient to name the publisher, with the DLQ rows following the same routing rule
([Chapter 1](../01-design-contract/2-the-event-contract.md)).

A Phase 0 rule that looked like tidiness turning out to make a Phase 5 feature trivial is a recurring
pattern in this project.

### It resolved an open question

`db-ownership.md` §4 originally listed *"The Event Explorer's backing store has no owner yet"* as an
open item — Phase 0 knew the page needed data and deliberately did not guess where it should live.
Phase 5 answered it, and the resolution is recorded in `docs/CHANGELOG-contracts.md`.

**Leaving a question open, marking it, and answering it when there is enough information** is the
practice [Chapter 1](../01-design-contract/4-sequencing-and-deferrals.md) describes, working exactly
as intended.

---

## Consumer lag

```java
/**
 * Real consumer-group lag, read straight from the broker via the admin API — the same computation
 * {@code kafka-consumer-groups.sh --describe} performs (per-partition latest offset minus the
 * group's last committed offset, summed).
 */
```

Not an application counter. Not an estimate. The same number the Kafka CLI reports, obtained the same
way — `AdminClient`, `listOffsets` for the partition ends, `listConsumerGroupOffsets` for the group's
committed positions, subtract and sum.

**Lag is the single most informative number about a Kafka consumer**, because it is the only one that
answers "is this keeping up?" Throughput without lag tells you how fast something is going, not
whether that is fast enough.

Two design choices worth copying:

**It fails soft.**

> Returns `0` if the group has no committed offsets yet [...] or if the broker call fails — this is a
> measurement aid, not a correctness gate, so a transient admin-API hiccup should not fail the
> scenario run.

**It uses its own `AdminClient`**, created from `spring.kafka.bootstrap-servers` and closed via
`DisposableBean`. An admin client is a different thing from a producer or consumer — it talks to the
cluster's metadata and coordinator APIs rather than to topics.

Lag is what makes Scenario 5 legible (it climbs while paused, drains on resume) and what makes
Scenario 8 meaningful in [Chapter 8](../08-observability-and-scaling/README.md) (adding replicas
should drain it faster — until you hit three partitions).

---

## The run timeline

Three kinds of entry, from two different sources:

| `TimelineKind` | Recorded by | Example |
|---|---|---|
| `HTTP` | the scenario harness thread | `POST /api/orders` → 201 |
| `EVENT` | Kafka listener threads, via the projection | `InventoryReserved` on `inventory.events` |
| `STATE_CHANGE` | `OrderStatusWatcher` | `PENDING` → `INVENTORY_RESERVED` |

Interleaving them is what makes the timeline worth looking at. A reviewer sees the 201 return, then
events arriving afterwards, then status changes following the events — the causal chain of an
asynchronous system, in order, with real timestamps.

Two implementation details:

**Sequence assignment is synchronized per run.** Several threads append concurrently — the harness
thread and one or more listener threads — so ordering is imposed explicitly rather than hoped for.

**`detail` is an open map, populated only with what was observed:**

> `detail` is intentionally an open map: per the ScenarioTimelineEntry schema, only fields the caller
> actually observed are included — never a fabricated placeholder.

The same rule as the projection's honesty boundary, applied at field granularity.

---

## System Health

Polled, not streamed — ADR-003's deliberate exception:

> Health data uses ordinary polling of Actuator endpoints, not a stream: it changes rarely and a stale
> health tile is a much smaller problem than a stale order timeline.

Each service exposes `/actuator/health` with liveness and readiness groups. The frontend polls all
five and renders a tile each, alongside consumer states from `/demo/consumers`.

> **Not yet.** Actuator is configured here but is properly the subject of
> [Chapter 8](../08-observability-and-scaling/README.md), which adds metrics, Prometheus, structured
> logging, and the probe endpoints Kubernetes targets. It also covers a CORS trap specific to Actuator
> that made these health tiles fail in the browser while working perfectly under `curl`.

---

## What a reviewer can now do

Against the exit criterion — without reading any source:

- **See the architecture**, rendered from Mermaid on the Architecture page.
- **Create an order** and watch its status change live over SSE.
- **Run any of eight scenarios** and watch an interleaved timeline of HTTP calls, events, and status
  changes as they happen.
- **Browse every event** the system has published, with real topic, partition, and offset.
- **Watch a DLQ record appear** with readable failure metadata.
- **Pause a consumer**, watch lag climb, resume, watch it drain.
- **See what is not shown, and why** — the Event Explorer's missing consumption phase is a documented
  boundary, not a gap.

That last one is the one most portfolio projects cannot offer.

---

[← The eight scenarios](3-the-eight-scenarios.md) · [Next: The console →](5-the-console.md)


# 5.5 — The console

[← Observing the system](4-observing-the-system.md) · [Chapter 5 ↑](README.md)

Three pages become seven, and the frontend stops being a client and starts being the product surface.

---

## Not a storefront

`project-overview.md` sets the frame:

> The frontend should therefore be primarily oriented around: demonstrating normal order processing;
> deliberately exercising failure and edge cases; exposing what is happening inside the distributed
> system; making the architecture understandable to recruiters and engineers quickly.

Four goals, none of which is "sell something." The fake catalog exists only to make orders realistic.

| Page | Purpose |
|---|---|
| Overview | The system at a glance; entry point |
| Orders | List, create, and inspect orders with live status |
| Scenarios | The eight scenarios, with descriptions |
| Scenario Run | The live interleaved timeline — the centerpiece |
| Event Explorer | Every event published, with topic/partition/offset |
| System Health | Per-service health and consumer states |
| Architecture | Rendered diagrams of the system |

---

## React Router, and why it arrived now

```
// Phase 5: seven pages replace the earlier state-based `view` switch in this file (list/create/
// detail only). A `useState` view switch does not scale to seven top-level pages plus nested
// detail routes (order detail, scenario-run detail) that should be independently deep-linkable
// (e.g. sharing a link straight to a scenario run) and back-button-navigable. React Router is a
// small, well-understood addition for exactly this — not adopted for its own sake, and nothing
// else in the app needed lifted global state that would argue for a heavier state library.
```

Three things worth taking from that comment.

**The trigger is named, not assumed.** Not "React apps use a router" but *deep-linkable and
back-button-navigable* — two concrete capabilities that a `useState` switch cannot provide and that
this application specifically needs. Sharing a URL that opens straight to a scenario run is a real
requirement for something meant to be shown to people.

**The scope is bounded.** *"Nothing else in the app needed lifted global state that would argue for a
heavier state library."* Adding a router did not open the door to Redux or Zustand — server state
belongs to TanStack Query ([Chapter 2](../02-domain/6-the-first-frontend.md)) and local state to
`useState`, and neither of those changed.

**It was added when it was needed**, not up front. Three pages did not need a router; seven pages plus
two nested detail routes do.

### The pages did not change

```tsx
function OrdersListRoute() {
  const navigate = useNavigate();
  return (
    <OrdersListPage
      onSelectOrder={(orderId) => navigate(`/orders/${orderId}`)}
      onCreateOrder={() => navigate('/orders/new')}
    />
  );
}
```

`OrdersListPage` still takes `onSelectOrder` and `onCreateOrder` callbacks and still knows nothing
about URLs. Adding routing meant writing thin wrapper components — the pages themselves were
untouched.

That is the payoff for a decision made in [Chapter 2](../02-domain/6-the-first-frontend.md): a
component that **reports what happened** rather than **deciding what it means** survives a change in
what it means. The same principle as keeping business logic out of controllers, on the other side of
the wire.

### One query-client default worth noticing

```tsx
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 0, networkMode: 'always' } },
});
```

`retry: 0` overrides TanStack Query's default of three attempts with backoff. For a console whose job
is to **show you what the backend is doing**, silently retrying a failed request is exactly wrong — a
service being down is information the page should display immediately, not smooth over.

`networkMode: 'always'` disables the offline-detection short-circuit, for the same reason: against
`localhost` or a cluster-internal address, the browser's online/offline heuristic is not a useful
signal.

**Defaults tuned to what the application is for**, rather than accepted because they are the defaults.

---

## The Architecture page

`docs/architecture-diagram.md` contains Mermaid diagrams. The Architecture page renders them in the
browser, so the diagram a reviewer sees is the diagram in the repository rather than a screenshot that
drifts.

`MermaidDiagram.tsx` is fifty lines that solve two real problems.

### Lazy loading

```tsx
// Loaded lazily (dynamic import) so the ~600KB mermaid bundle only loads when the Architecture page
// is actually visited, not as part of the main bundle every page pays for.
const mod = await import('mermaid');
```

Mermaid is larger than the rest of the application combined. A dynamic `import()` makes Vite emit it
as a separate chunk fetched on demand — so six of the seven pages never download it.

**Code splitting at the point of use**, triggered by a genuine cost rather than applied everywhere by
reflex.

### Serializing renders

```tsx
// mermaid.initialize()/mermaid.render() share global module state inside the mermaid package.
// When multiple MermaidDiagram instances mount at once (as happens here — the Architecture page
// renders several diagrams in one pass) their render() calls overlap, and one of them silently
// hangs forever (no resolve, no reject) instead of producing an SVG or an error.
let mermaidInitialized = false;
let renderQueue: Promise<unknown> = Promise.resolve();

function queueMermaidRender(id: string, source: string): Promise<{ svg: string }> {
  const task = renderQueue.then(async () => { /* initialize once, then render */ });
  // …
}
```

A third-party library with **global module state** that is not safe to call concurrently. Several
components mount together, several `render()` calls overlap, and one promise **never settles** — no
resolve, no reject, no error. A diagram that simply never appears.

The fix is a **promise chain as a queue**: each render appends to `renderQueue`, so calls serialize
across every instance regardless of when they mount. Plus module-level initialization exactly once.

Two things generalize:

**A promise that never settles is worse than one that rejects.** There is nothing to catch, nothing to
log, and no error boundary fires. The component just sits there.

**This is the same hazard as `SseEmitter#send`** from [section 2](2-server-sent-events.md) — a
resource that is not safe to use concurrently, being used concurrently — and the same shape of fix,
serializing at the right granularity. One in Java on the server, one in TypeScript in the browser.
Concurrency bugs do not care what language you are writing in.

Also worth noting: `securityLevel: 'strict'` when initializing. Mermaid renders arbitrary text into
SVG, and strict mode disables inline scripts and click handlers in diagram source.

---

## Chapter 5 in one paragraph

The fifth service exists because Phase 0 refused to put a `forcePaymentFailure` flag on the order API,
and it orchestrates the whole demonstration through public APIs and quarantined `/demo` controls
without owning a single row of business data. SSE replaces polling because the timeline is the
product and polling quantizes exactly the ordering it exists to show. An event projection in its own
consumer group observes all eight topics without the observed services knowing, and declines to
display anything it cannot honestly observe. And the console turns all of it into seven pages a
reviewer can use without reading a line of source — which was the exit criterion.

---

[← Observing the system](4-observing-the-system.md) · [Chapter 5 ↑](README.md) · [Chapter 6 — The transactional outbox →](../06-outbox/README.md)


<hr style="page-break-after: always;"/>

# Chapter 6 — The transactional outbox

**Build history:** Phase 6 — `a919731 transactional outbox`, Order Service only. Extended to the other
three services in Sprint 2 — `a045fe8`.

The shortest chapter with the highest ratio of insight to code. Three small classes and one table
close a failure mode that has no error message, no failed request, and no alert — an order that
silently never progresses because the event announcing it was never sent.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [The dual-write problem](1-the-dual-write-problem.md) | The window and why it is silent, why publishing first is worse, the mistake ADR-006 made about redelivery self-healing, and the alternatives worth naming |
| 2 | [The implementation](2-the-implementation.md) | The table, building the envelope at transaction time, ordered serial dispatch, duplicates-not-losses, age-bounded retry, the `jsonb` round-trip, and four tuned numbers |
| 3 | [The rollout](3-the-rollout.md) | Why one service first, how ADR-006 recorded its own correction, what Sprint 2 changed in each service, and the duplication nobody decided about |

---

## The one sentence to remember

> A lost-event problem becomes a duplicate-event problem, and duplicates are the one thing
> ADR-005's idempotent consumers already handle.

The outbox does not give you exactly-once delivery. **It converts an unhandleable failure into one you
already solved.** Anyone who describes an outbox as achieving exactly-once has skipped this step — and
being able to say precisely what it does instead is the point of the chapter.

## The second-order lesson

[Section 1](1-the-dual-write-problem.md) contains the best example in the project of two correct
designs interacting badly.

ADR-005 requires the idempotency claim to commit **inside** the business transaction. Correct.
ADR-006 originally scoped the outbox to one service, on the reasoning that the other three would
self-heal through redelivery. Plausible.

Together they are wrong: the ledger claim short-circuits the redelivery, so the "self-healing" never
happens and the order strands permanently at a later status instead of at `PENDING`.

**Neither ADR is individually incorrect. The interaction was unexamined** — and it was not visible
from either document alone. That is worth more as a general lesson than the outbox pattern itself.

---

## Build it yourself

Per service — four services, identically.

1. `V4__outbox_events.sql` (`V6` in Inventory, which already had five): `id bigserial`,
   `aggregate_id text`, `event_type text`, `payload jsonb`, `created_at timestamptz`,
   `published_at timestamptz NULL`, `status text`.
2. `OutboxEventEntity`, `OutboxEventRepository` (with `findByStatusOrderByIdAsc(status, Pageable)`),
   and an `OutboxStatus` of `PENDING`/`PUBLISHED`/`FAILED`.
3. **`OutboxRecorder`** — `@Transactional(propagation = MANDATORY)`, building the envelope via
   `EventPublisher.buildEnvelope` **at transaction time**, serializing it into `payload`, and
   returning the `eventId` (with the caller-supplied-id overload for `PaymentRequested`).
4. **`OutboxDispatcher`** — `@Transactional`, `findByStatusOrderByIdAsc` with `FOR UPDATE`, sending
   **one row at a time** and blocking on each acknowledgement with `.get(timeout)`, marking each
   `PUBLISHED`. On failure: `markFailed` + `ERROR` + `continue` if the row has aged past
   `fail-after-ms`, otherwise `WARN` + **`break`** so the topic is not reordered. Re-serialize the
   `jsonb` on the way out.
5. **`OutboxPublisher`** — a *separate bean* carrying
   `@Scheduled(fixedDelayString = "${…poll-interval-ms:50}")`, with a narrow `catch` around the call.
6. Configuration: `poll-interval-ms: 50`, `batch-size: 100`, `send-timeout-ms: 10000`,
   `fail-after-ms: 300000` — each with a comment saying what it bounds.
7. **Replace every publish site.** `EventPublisher.publish` → `OutboxRecorder.record`, inside the
   existing `REQUIRES_NEW` transaction that already holds the business change and the ledger claim:
   - Order Service — `createPendingOrder` (`OrderCreated`) and
     `appendInventoryReservedTransition` (`PaymentRequested`)
   - Inventory Service — `attemptReserve` and `release`
   - Payment Service — `authorize`
   - Fulfillment Service — `createShipment`
8. **Remove publishing from the consumers entirely.** A listener should no longer send anything.
9. `*OutboxIntegrationTest` per service: assert the business row and the `outbox_events` row commit
   together; assert the row reaches `PUBLISHED` and the record appears on the topic with the **same
   `eventId`** the transaction recorded; assert a row older than `fail-after-ms` whose send fails is
   marked `FAILED` and does not block later rows.

**Done when:** every publish site in every service writes to `outbox_events` rather than to Kafka
directly; no listener sends anything; killing a service between its commit and the next dispatcher tick
loses nothing (the row publishes on restart); and the events on the wire are byte-for-byte the
envelopes the business transactions recorded.

---

## Next

[Section 1 — The dual-write problem](1-the-dual-write-problem.md).


# 6.1 — The dual-write problem

[← Chapter 6](README.md) · [Next: The implementation →](2-the-implementation.md)

The second of [Chapter 3](../03-kafka-and-services/README.md)'s open gaps, deliberately left open for
five chapters so that it could be understood before being fixed.

---

## The gap, restated

`EventPublisher.publish` sends to Kafka **after** the business transaction has committed:

```java
kafkaTemplate.send(topic, aggregateId, json);
```

Not even blocked on. So:

```
BEGIN; INSERT INTO orders …; COMMIT;   ← durable
                                        ← process dies here
kafkaTemplate.send(OrderCreated);       ← never happens
```

ADR-006 states the consequence exactly:

> The order exists, is visible over `GET /api/orders/{orderId}`, and will never progress — no consumer
> was ever told about it. **Nothing retries, because from the database's point of view the work
> succeeded.**

That last clause is what makes it nasty. There is no error, no failed request, no dead-letter record,
no alert. The order sits at `PENDING` forever, and the only way to find it is to go looking.

The event catalog carried the same warning from Phase 0:

> Until Phase 6 (transactional outbox), publishers persist their business change and then publish —
> so a crash between commit and publish loses the event.

**A known limitation, written down at the time.** That is what makes it a documented trade rather than
a bug — and it is also why this chapter can exist as a fix rather than an incident.

> **Pattern — [The transactional outbox](../patterns/transactional-outbox.md)**
> The dual write in general, why 2PC and Kafka transactions cannot help, CDC as the answer for a real
> system, the table shape, and the five implementation details that matter.
>
> **Read it before continuing.** This chapter covers what is specific to this codebase.

---

## Why the obvious inversion is worse

Publish first, then commit? ADR-006:

> Removes the lost-event case by introducing a phantom-event case: consumers act on a state change the
> publisher then rolls back. **Rejected as strictly worse — a lost event leaves an order stuck, while
> a phantom event corrupts other services' state.**

Worth having ready as an answer. Both orderings have a window; they differ in **whose problem the
window becomes**. Losing an event is a local failure — one order, stuck, findable. A phantom event is a
distributed failure: Inventory Service has reserved stock for an order that does not exist, and no
amount of looking at Order Service reveals it.

**When both options are wrong, prefer the one whose failure stays local.**

---

## The mistake ADR-006 made, and corrected

This is the best example in the project of a decision being revisited on its merits, and it is worth
following closely.

Phase 6 scoped the outbox to **Order Service only**, on this reasoning:

> The other publishers lose an event that a redelivery can regenerate, because their publishes are
> themselves reactions to consumed events — if `InventoryReserved` is lost, the `OrderCreated` that
> caused it can be reprocessed.

Plausible. Inventory Service publishes *in reaction to* consuming `OrderCreated`; if its publish is
lost, surely Kafka redelivers `OrderCreated` and Inventory publishes again?

**No — and the reason is a mechanism added in a different chapter.**

> That is **not true of this implementation**, and the mistake matters. Every event-driven publish site
> in all four services claims its `processed_events` row *inside* the business transaction (ADR-005
> requires exactly that), so a redelivery is short-circuited by the ledger before it can republish
> anything: the event is not regenerated, it is **silently skipped**. A crash between such a commit
> and its publish strands the order just as permanently as a lost `OrderCreated` does — only at a
> later status.

Trace it:

1. Inventory Service consumes `OrderCreated`, reserves stock, **claims the ledger row in the same
   transaction**, commits.
2. Crash before `InventoryReserved` is published.
3. Kafka redelivers `OrderCreated` — the offset was never committed.
4. The consumer checks the ledger. **Already processed.** Skip.
5. The order is stuck at `PENDING` forever.

The idempotency mechanism from [Chapter 4](../04-reliability/README.md) — which is entirely correct,
and required — **removes the self-healing property that Phase 6's scoping decision assumed**. Two
individually correct designs interacting to produce a failure neither has on its own.

That is the thing worth taking away. **Neither ADR is wrong; the interaction was unexamined.** And it
was not discoverable by reading either document alone — you have to hold both mechanisms in your head
at once and ask what happens in the gap between them.

The same reasoning also expanded the fix *within* Order Service. Phase 6 routed **both** of its publish
sites through the outbox, not just the first:

> a crash after this commit but before a post-commit publish would leave a redelivered
> InventoryReserved to be discarded as a duplicate [...] stranding the order at PAYMENT_PENDING
> exactly as a lost OrderCreated strands it at PENDING.

And what redelivery *does* still cover:

> the narrower case of a consumer that crashes **before** committing anything at all.

Redelivery protects work that never started. It cannot protect work that finished but failed to
announce itself — because the ledger, correctly, cannot tell those apart.

---

## The alternatives, and the one you should name

ADR-006's rejected options are covered in the
[pattern page](../patterns/transactional-outbox.md); two are worth pulling out here because they are
what an interviewer will ask about.

**"Why not just do nothing? It's a demo."**

> Simplest, and honestly adequate for a demo whose processes rarely die at the wrong microsecond.
> Rejected because the dual-write problem is one of the project's headline talking points, and
> **demonstrating the fix is worth considerably more than describing the problem.**

An honest answer that says the quiet part: the pattern is here partly *because it is worth
demonstrating*. That is a legitimate reason in a portfolio project, and stating it is better than
inventing a scale requirement that does not exist.

**"Why not Debezium / CDC?"**

Rejected on two stated grounds — the operational cost of Kafka Connect plus a connector, and the fact
that CDC events are shaped by table structure, so producing the designed envelope would need a
transformation step. Plus a third, stated plainly:

> It would also hide the mechanism the project is trying to demonstrate — the outbox's value here is
> partly pedagogical. **Worth naming as the answer for a real system with many publishers.**

Naming the better answer for a different context is a stronger position than defending your choice as
universally correct. "For a real system with many publishers I'd reach for CDC; here the outbox is
explicit, has no extra infrastructure, and shows the mechanism" is a complete answer.

---

[← Chapter 6](README.md) · [Next: The implementation →](2-the-implementation.md)


# 6.2 — The implementation

[← The dual-write problem](1-the-dual-write-problem.md) · [Next: The rollout →](3-the-rollout.md)

Three small classes per service — a recorder, a dispatcher, and a scheduler — plus one table.

---

## The table

```sql
CREATE TABLE outbox_events (
    id           bigserial PRIMARY KEY,
    aggregate_id text NOT NULL,
    event_type   text NOT NULL,
    payload      jsonb NOT NULL,
    created_at   timestamptz NOT NULL,
    published_at timestamptz NULL,
    status       text NOT NULL
);
```

`V4__outbox_events.sql` in each service, identical DDL in four schemas — the same shape, and the same
reasoning, as `processed_events`.

**`payload` holds the complete envelope**, not just the domain payload. That is what lets the
dispatcher be dumb: it reads a row, sends the bytes, and marks it. It needs to know nothing about
event types or envelope construction.

**`id` is a `bigserial`.** Publication order is `ORDER BY id ASC`, which is commit order — the property
everything downstream depends on.

---

## The recorder: build the envelope now

```java
@Transactional(propagation = Propagation.MANDATORY)
UUID record(String eventType, String aggregateId, Object payload) {
    return record(eventType, aggregateId, UUID.randomUUID(), payload);
}
```

Two decisions, both explained in its Javadoc.

**The envelope is built here, at business-transaction time:**

> so `eventId`, `occurredAt` and `correlationId` describe the moment the change actually happened, and
> are identical no matter how many times the dispatcher has to resend the row.

Three consequences follow from that one choice:

- **`occurredAt` is a business time**, not a publication time — honouring the envelope contract's
  *"when the publishing service decided the event happened — not when it was written to Kafka."*
  With an outbox that gap can be seconds; with a broker outage, minutes.
- **A resend carries the same `eventId`**, so consumer-side deduplication works. If the dispatcher
  stamped IDs at send time, every resend would look like a new event and
  [Chapter 4](../04-reliability/README.md)'s ledger would be useless against exactly the duplicates
  this pattern creates.
- **The caller learns the ID before the send** — which `PaymentRequested` needs, because its
  `idempotencyKey` *is* its own event ID.

It also reuses `EventPublisher.buildEnvelope` rather than constructing an envelope itself, so there
remains exactly one place the frozen envelope is built.

**`MANDATORY`, for the same reason as the ledger:**

> an outbox insert in a transaction of its own would reintroduce exactly the dual-write window this
> class exists to close, so calling it without one fails loudly at the call site.

The second appearance of this annotation in the project, both times enforcing "this write must join
the caller's transaction, and there had better be one."

### At the call site

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
OrderEntity createPendingOrder(String orderId, String customerId, List<OrderItemEntity> items, BigDecimal totalAmount) {
    orderRepository.save(order);
    orderItemRepository.saveAll(items);
    historyRepository.save(new OrderStatusHistoryEntity(orderId, OrderStatus.PENDING, null, now));
    // …
    outboxRecorder.record(EventTypes.ORDER_CREATED, orderId, new OrderCreatedPayload(orderId, customerId, eventItems));
    return order;
}
```

Four writes, one transaction: the order, its items, its first history row, and the event. All of it, or
none of it.

And a structural point in the Javadoc worth copying:

> The event is built here rather than by the caller precisely so it cannot be forgotten: **there is no
> longer any code path that creates an order without also committing its event.**

The transaction boundary and the "an order always has an event" invariant are the same boundary.
`OrderService.createOrder` cannot get this wrong, because it no longer has the opportunity.

---

## The dispatcher: send in order, one at a time

```java
@Transactional
int publishPendingBatch() {
    List<OutboxEventEntity> pending =
            outboxRepository.findByStatusOrderByIdAsc(OutboxStatus.PENDING, PageRequest.of(0, batchSize));
    int published = 0;
    for (OutboxEventEntity row : pending) {
        try {
            JsonNode envelopeNode = objectMapper.readTree(row.getPayload());
            kafkaTemplate.send(KafkaTopics.ORDERS_EVENTS, row.getAggregateId(), wireForm(envelopeNode))
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            row.markPublished(Instant.now());
            published++;
        } catch (Exception ex) {
            if (expired(row)) {
                row.markFailed();
                log.error("Outbox row {} … could not be published within {} — marking FAILED; "
                        + "this event was never sent and needs manual attention", …);
                continue;   // aged out; skipping it unblocks everything queued behind it
            }
            log.warn("Outbox send failed for row {} …; leaving PENDING for retry", …);
            break;          // stop the batch: sending later rows first would reorder the topic
        }
    }
    return published;
}
```

### Ordering is the whole design

> Rows are sent strictly oldest-first and one at a time, **blocking on each broker acknowledgement
> before the next send**, because ADR-001's per-partition ordering guarantee is only worth anything if
> this publisher preserves the order the transactions committed in. That is also why a send failure
> stops the batch instead of skipping ahead.

Note `.get(sendTimeoutMs, …)` — the opposite of `EventPublisher`'s fire-and-forget. Here the send is
awaited, because the next send must not start until this one is acknowledged.

**This is the pattern's real cost: publication is serial per service.** Not per partition, per
*service*. Every event queues behind every earlier event. For this system's volume that is irrelevant;
at high throughput it is the first thing you would have to address, and the usual answer is to
partition the outbox by aggregate and dispatch each partition independently.

**`break` on failure, not `continue`.** Skipping a failed row to publish a later one would deliver
events out of commit order — turning a delay into a correctness problem.

### The transaction spans the sends

> The transaction spans the sends on purpose: it holds `FOR UPDATE` on the batch, so a second instance
> of this service waits its turn rather than interleaving sends. Whatever was published before a
> failure still commits — the loop returns normally rather than throwing.

Two properties from one decision. **Multi-instance safety** — two replicas both polling would otherwise
interleave sends and reorder the topic; the row lock serializes them without any coordination service.
And **partial progress is kept**, because the loop returns rather than throwing, so five successful
sends out of ten commit their `PUBLISHED` marks.

This is also why `batch-size` is bounded — the batch size is how long one instance can hold that lock.

### Duplicates, not losses

> The send and the `PUBLISHED` mark are not atomic — a crash in between resends the row on the next
> tick. That is ADR-006's stated trade: **a lost-event problem becomes a duplicate-event problem, and
> duplicates are the one ADR-005's idempotent consumers already handle.** At-least-once, never
> exactly-once.

The dual write did not vanish. It moved — from between PostgreSQL and Kafka to between Kafka and one
`UPDATE` in PostgreSQL. And the new failure mode is one the system already solved a chapter ago.

**This is the most important sentence in the chapter.** The outbox does not achieve exactly-once. It
converts an unhandleable failure into a handled one. Anyone claiming an outbox gives exactly-once
delivery has skipped this step.

### Retry bounded by age

> The frozen schema has no retry-count column, so retries are bounded by *age* instead: a row whose
> send fails stays `PENDING` and is retried on every tick until it is older than `fail-after-ms`, at
> which point it is marked `FAILED`, logged at ERROR, and skipped so it cannot block the queue forever.

Five minutes by default. The reasoning:

> A broker outage shorter than that window therefore costs nothing but latency; a genuinely
> unpublishable row (or an outage longer than the window) surfaces as a FAILED row for a human to look
> at.

**A constraint turned into a design.** No retry-count column, so age becomes the budget — and age is
arguably the better measure anyway, since what you care about is how long an event has been undelivered,
not how many times you tried.

And:

> Nothing here ever deletes or rewrites `payload`, so a FAILED row remains a complete record of the
> event that should have been published.

`FAILED` is not a tombstone. It is evidence, and it is the only evidence there is.

### The `jsonb` round-trip

```java
/**
 * PostgreSQL's {@code jsonb} is a decomposed binary format, not the text that was inserted: it drops
 * insignificant whitespace, reorders object keys and collapses duplicates, so reading the column back
 * gives {@code {"eventId": "…"}} where the producer wrote {@code {"eventId":"…"}}. [...] records on
 * {@code orders.events} should look the same whichever service produced them, so the row is
 * re-serialized compactly on its way out. This changes formatting only.
 */
private String wireForm(JsonNode envelopeNode) {
    return objectMapper.writeValueAsString(envelopeNode);
}
```

A genuinely surprising detail, and a good thing to know: **`jsonb` does not store your text.** It
parses to a binary representation and re-serializes on read, so whitespace, key order, and duplicate
keys are all lost.

It changes nothing semantically — every consumer parses rather than string-matches — but records on one
topic should look alike whichever code path produced them, so the dispatcher normalizes on the way out.

(`json`, without the `b`, *does* preserve the original text. `jsonb` is otherwise the right choice —
it indexes and queries — so normalizing on output is the better trade.)

---

## The scheduler

```java
@Scheduled(fixedDelayString = "${orderfulfillment.outbox.poll-interval-ms:50}")
void publishPending() {
    try {
        int published = dispatcher.publishPendingBatch();
        if (published > 0) {
            log.debug("Outbox published {} event(s)", published);
        }
    } catch (Exception ex) {
        // A scheduled method that throws is simply logged by Spring and retried next tick; this
        // catch exists only to keep the message specific (e.g. the database being unreachable,
        // which is not the dispatcher's own per-row failure path).
        log.warn("Outbox poll failed; retrying on the next tick", ex);
    }
}
```

A separate bean from the dispatcher, for the third appearance of the same trap:

> Separate from `OutboxPublisher` (which owns the `@Scheduled` tick) so that `@Transactional` actually
> applies — a self-invoked call would bypass Spring's proxy, the same reason `OrderPersistence` is
> split out of `OrderService`.

`@Transactional`, `@Async`, `@Scheduled` — all proxy-based, all silently inert on self-invocation. Once
you know the pattern, a one-method class calling into another bean stops looking like over-engineering.

**`fixedDelay`, not `fixedRate`**, and the Javadoc says why:

> ticks must not stack up behind a slow batch, since two dispatchers running at once would contend on
> the same rows for no gain.

`fixedDelay` waits N ms *after the previous run finishes*; `fixedRate` fires every N ms regardless, so
a slow batch would have the next tick starting while the previous still held the row lock — threads
piling up to immediately block on each other.

**The `catch` is deliberate and narrow.** A scheduled method that throws is logged by Spring and
retried next tick anyway; this exists only to make the message specific — a database that is
unreachable entirely, as opposed to the dispatcher's own per-row failure path.

**Polling, with no notify-on-commit hook**, and that is also a stated decision:

> ADR-006 offers that as an optional latency optimization, and at the default 50 ms interval the added
> publication latency is already inside the "tens of milliseconds" the ADR budgets for, which does not
> justify a second concurrent path into the dispatcher.

Declining an available optimization because the simpler thing already meets the budget — and naming
the cost of taking it (a second concurrent path into the dispatcher) rather than just calling it
unnecessary.

### The poll interval is the latency

```yaml
orderfulfillment:
  outbox:
    # Phase 6's transactional outbox (ADR-006). The poll interval is the publication latency this
    # pattern costs: ADR-006 budgets "tens of milliseconds if the publisher polls tightly", and 50ms
    # is inside that without a notify-on-commit hook.
    poll-interval-ms: 50
    batch-size: 100
    send-timeout-ms: 10000
    fail-after-ms: 300000
```

**Every event now waits up to 50ms before publication.** That is the pattern's other cost, stated
plainly and tuned deliberately — fast enough to be invisible in a demo timeline, slow enough not to
hammer the database with empty queries.

Each of the other three is also a named trade, and the comments say what each bounds:

- **`batch-size: 100`** — *"sends are sequential and acknowledged one at a time (ordering), so this
  bounds how long one transaction can hold its FOR UPDATE lock."*
- **`send-timeout-ms: 10000`** — how long to block on one acknowledgement before treating the send as
  failed.
- **`fail-after-ms: 300000`** — the age-based retry budget.

Four numbers, four stated reasons. The same discipline as
[Chapter 4](../04-reliability/README.md)'s derived bounds.

---

[← The dual-write problem](1-the-dual-write-problem.md) · [Next: The rollout →](3-the-rollout.md)


# 6.3 — The rollout

[← The implementation](2-the-implementation.md) · [Chapter 6 ↑](README.md)

Phase 6 shipped the outbox in one service. Sprint 2 shipped it in the other three. The gap between
those two events is the interesting part.

---

## What Phase 6 shipped, and why only one service

`implementation-phases.md`'s Phase 6 says *"at least the most important publisher, likely Order
Service"* — so the scoping was in the plan from Phase 0, and ADR-006 picked the same target:

> it is the only publisher whose lost event strands an order that a user has already been told was
> accepted.

That reasoning is about **blast radius**, and it is sound as far as it goes. A lost `OrderCreated`
strands an order the user was told was accepted. It is the most visible failure.

What made it *incomplete* was the second half of the argument — that the other three would self-heal
through redelivery. As [section 1](1-the-dual-write-problem.md) showed, they would not, because their
`processed_events` claim commits inside the same transaction and short-circuits the redelivery.

**Phase 6 shipped the right service first, for a partly wrong reason.** Which is a common and
recoverable situation, and the recovery is the thing worth studying.

---

## How the correction was recorded

ADR-006 does not have its scoping paragraph edited away. It keeps the original text and appends a
correction block:

> **Correction, Phase 6 (implementation).** This section originally continued: "The other publishers
> lose an event that a redelivery can regenerate…" That is **not true of this implementation**, and
> the mistake matters.

And a second one, later:

> **Correction, Sprint 2 goal 2.** The gap this ADR originally left open for Inventory, Payment and
> Fulfillment Service is closed.

Three things about this practice.

**The wrong reasoning is preserved.** Anyone who read the ADR before the correction, or who has the
old argument in their head, can find out specifically what was wrong with it. Deleting it would leave
them with a document that no longer matches their memory and no explanation.

**The correction says *why*, not just *what*.** *"Every event-driven publish site claims its
`processed_events` row inside the business transaction, so a redelivery is short-circuited by the
ledger."* That is the reusable insight — someone else's outbox scoping decision can be checked against
it.

**Both corrections are dated and attributed to a phase.** The ADR reads as a record of a decision *and
its subsequent history*, which is what an ADR is actually for.

This is one place where the guide's own convention differs from the repo's. This project's docs are
generally supposed to *"state current content only, no embedded revision history"* — and an ADR is
precisely the exception, because the decision's history **is** its content.

---

## What Sprint 2 actually did

Three services, each getting the same four things:

| | |
|---|---|
| `V4__outbox_events.sql` (or `V6` for Inventory) | Identical DDL, own schema |
| `OutboxRecorder` | `MANDATORY`, builds the envelope at transaction time |
| `OutboxDispatcher` | Ordered batch, `FOR UPDATE`, age-bounded retry |
| `OutboxPublisher` | `fixedDelay` scheduler |

And one edit each, at the publish site:

| Service | Transaction | Events now recorded |
|---|---|---|
| **Inventory** | `InventoryReservationExecutor.attemptReserve` / `release` | `InventoryReserved`, `InventoryReservationFailed`, `InventoryReleased` |
| **Payment** | `PaymentService.authorize` | `PaymentAuthorized`, `PaymentRejected` |
| **Fulfillment** | `FulfillmentService.createShipment` | `ShipmentCreated` |

In every case the publish moved **into an existing `REQUIRES_NEW` transaction that already held both
the business change and the `processed_events` claim.** So the transaction that was already the unit
of "this event has been handled" became the unit of "and its consequence will be announced."

The consumers got simpler:

> this consumer no longer publishes anything itself. `InventoryService` (by way of
> `InventoryReservationExecutor`) records the outbound event to `outbox_events` inside the same
> transaction as the reservation; `OutboxPublisher` sends it to Kafka afterward.

**Publishing moved out of the listener and into the domain transaction** — which is also where it
always belonged, since the decision to publish is a consequence of the business change rather than of
having consumed a record.

### One duplication worth noticing

Four services now have four near-identical copies of `OutboxRecorder`, `OutboxDispatcher`,
`OutboxPublisher`, `OutboxEventEntity`, `OutboxEventRepository`, and `OutboxStatus`. Thirty files
where six shared ones might do.

Compare `ProcessedEventLedger`, which is **one** class in `common` used by all four
([Chapter 4](../04-reliability/1-idempotent-consumers.md)) — and whose Javadoc argued explicitly
against per-service copies as *"four copies of the one thing Phase 4's fan-out is most likely to let
drift."*

The same argument applies here and was not made. Two things differ, which partly explains it:

- The ledger is **two SQL statements against `JdbcClient`** with no entity mapping. The outbox uses a
  JPA entity and a Spring Data repository, which are harder to share generically across schemas.
- The dispatcher hard-codes its **destination topic** — `KafkaTopics.ORDERS_EVENTS` in Order
  Service's copy — so a shared version would need that parameterized, the way
  `ConsumerErrorHandlerFactory` parameterizes its DLQ topic.

> **Open question.** Neither difference looks decisive, and the error-handler factory demonstrates the
> pattern for parameterizing exactly this kind of per-service value. This is the largest piece of
> duplication in the codebase and the repo does not record a decision about it — worth asking whether
> it was considered and rejected, or simply followed the shape of Order Service's existing code during
> the Sprint 2 rollout.

---

## Stale documentation this created

Rolling a pattern out to three more services touched a lot of prose, and not all of it was updated.
Two known instances, both found while writing this guide:

**`EventPublisher`'s Javadoc** still says:

> Phase 6 closed that gap in Order Service only [...] Inventory, Payment and Fulfillment Service still
> publish this way, deliberately and documented.

Not true since Sprint 2. See [Chapter 3](../03-kafka-and-services/1-events-on-the-wire.md).

**ADR-004's decision section** still says:

> `outbox_events` exists only in Order Service (ADR-006).

Not true either. ADR-006 carries both corrections; ADR-004 carries none.

Neither is dangerous — nothing depends on them — but together they make a point worth carrying into
[Chapter 10](../10-retrospective/README.md): **the code was updated consistently and the prose was
not.** A rollout that touches four services touches every document that described the previous state,
and those references live in Javadoc, ADRs, and design docs that no test exercises.

---

## Where the pattern now stands

All four services publish through an outbox. No business change in this system can commit without its
event also being committed.

What that does and does not buy, precisely:

**Buys:** no event is lost after its business change commits. The failure mode that stranded an order
silently, with no error anywhere, is gone.

**Costs:** up to 50ms of publication latency; serial publication per service; more duplicates, not
fewer.

**Does not buy:** exactly-once. Never has, never will —
*"at-least-once, never exactly-once (agent-guidance.md rule 18)."*

That last line appears in the dispatcher's own Javadoc, in the class most likely to be described as
providing reliable delivery. Exactly where it should be.

---

[← The implementation](2-the-implementation.md) · [Chapter 6 ↑](README.md) · [Chapter 7 — Containers and Kubernetes →](../07-containers-and-kubernetes/README.md)


<hr style="page-break-after: always;"/>

# Chapter 7 — Containers and Kubernetes

**Build history:** Phase 7 (`4f3b07c containerization`) and Phase 8 (`fb5caab kubernetes`).

Two phases with one theme: the system stops being something you run from an IDE and becomes something
you deploy. Nothing about the application changes — which is the point, and the return on a discipline
kept since [Chapter 2](../02-domain/1-project-skeleton.md) with nothing verifying it.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [Containers and Compose](1-containers-and-compose.md) | Multi-stage Dockerfiles, the monorepo build-context problem, why a browser SPA needs build-time configuration, the nginx SPA fallback, and Kafka's dual-listener arrangement |
| 2 | [Kubernetes manifests](2-kubernetes-manifests.md) | Plain YAML over Helm, one file per service, ConfigMaps vs. Secrets, `NodePort` and what it exposes, PostgreSQL without a StatefulSet, and what Phase 8 deferred |
| 3 | [Probes and resources](3-probes-and-resources.md) | Readiness vs. liveness, the finding that default readiness reflected nothing, requests vs. limits and the JVM, and running on kind |

---

## The exit criteria

**Phase 7:** *A fresh clone can be started using documented commands.*
**Phase 8:** *The full app runs in Kubernetes.*

Both are satisfied by `docker compose up --build` and a four-command kind sequence — and neither
required an application source change.

---

## Three ideas worth carrying out

**A discipline kept for six chapters gets paid back in one.** Every environment-varying value has been
`${VAR:local-default}` since [Chapter 2](../02-domain/1-project-skeleton.md), on ADR-007's warning that
otherwise *"Phase 7 turns into a refactor."* The bill turns out to be two environment variables per
service, and the same image runs under Compose and under Kubernetes with no knowledge of either.

**Where configuration is read decides how it is packaged.** A backend reads config at startup, so one
image serves every environment. A browser SPA has config compiled into its bundle, so the environment
is chosen at *build* time. That is not a tooling flaw — it follows from where the code runs — and it is
why the frontend Dockerfile takes `ARG`s and the backends do not.

**Check what your health check actually checks.** Spring Boot's default readiness group contains only
`readinessState` and reflects no dependency at all. A readiness probe against it passes for a pod that
cannot reach its database. Phase 8 found this by *listing the registered indicators live* rather than
assuming, and discovered along the way that Spring Kafka registers no broker indicator — so the
obvious fix would have silently included nothing.

---

## Build it yourself

**Containers** — [section 1](1-containers-and-compose.md)

1. A multi-stage Dockerfile per backend: `maven:3.9-eclipse-temurin-21` building with
   `-pl <common>,<service> -am package -DskipTests`, then `eclipse-temurin:21-jre-alpine` with just the
   jar. **Build context is the repo root** — Maven's reactor needs every listed module on disk. Delete
   `*.jar.original` before the `COPY` glob.
2. A `.dockerignore` covering `target/`, `node_modules/`, `.git/`.
3. A frontend Dockerfile: `node:22-alpine` building, `nginx:alpine` serving, with a `VITE_*_SERVICE_URL`
   **`ARG`** per backend defaulting to `http://localhost:808X`. Dependencies before source.
4. An `nginx.conf` with `try_files $uri $uri/ /index.html` — without it, deep links 404.
5. `docker-compose.yml`: PostgreSQL with a `pg_isready` health check; Kafka in KRaft with **two
   listeners** (`HOST://localhost:9092` for the host, `INTERNAL://kafka:29092` for containers); five
   backends with `SPRING_DATASOURCE_URL` and `KAFKA_BOOTSTRAP_SERVERS`, `depends_on … condition:
   service_healthy`, and a `wget --spider` health check against `/actuator/health`; the frontend on
   5173.

**Kubernetes** — [section 2](2-kubernetes-manifests.md)

6. Numbered manifests so `kubectl apply -f <dir>` orders them: namespace, secrets, Postgres, Kafka,
   five services, frontend.
7. **Do not commit a real password.** Create the Secret imperatively, or template it from the
   environment.
8. PostgreSQL: PVC + Deployment with `strategy: Recreate` and `subPath: pgdata`, plus a `pg_isready`
   exec readiness probe. Not a StatefulSet, and be able to say why.
9. Per service: a ConfigMap with the same two variables Compose uses, a Deployment with `envFrom` +
   `secretKeyRef`, and a `NodePort` Service on a fixed `3008X`.

**Probes and resources** — [section 3](3-probes-and-resources.md)

10. `management.endpoint.health.probes.enabled: true`, and probes targeting
    `/actuator/health/readiness` and `/actuator/health/liveness`.
11. **Liveness slower and later than readiness** — 45s/15s against 30s/5s.
12. **Check which health indicators exist**, live, before writing a readiness group. Then set
    `MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE: "readinessState,db"` in the ConfigMap — a
    manifest-level override, not a source change.
13. Requests and limits per service (150m/320Mi requested, 500m/640Mi capped) and sum the requests
    against your node's *allocatable* capacity.
14. `kind-config.yaml` mapping each NodePort to the **same host port Compose uses**, so the frontend
    image works unchanged.
15. `kind create cluster`, `docker compose build`, `kind load docker-image … ×6`, `kubectl apply -f`.

**Demonstrate it** — the part that makes probes more than configuration

16. Delete the Postgres pod. Watch the backends leave the Service endpoints and **not restart**. Bring
    it back; watch them return.
17. `kubectl scale deployment inventory-service --replicas=3` and watch the consumer group rebalance
    across three partitions.
18. Delete a consumer pod mid-workflow and watch redelivery plus the idempotency ledger suppressing the
    duplicate.

**Done when:** `docker compose up --build` starts everything from a fresh clone; the same images run on
kind and the browser cannot tell which; a Postgres outage fails readiness without triggering a single
restart; and every scenario from [Chapter 5](../05-scenarios-and-frontend/README.md) still passes
against the cluster.

---

## Next

[Section 1 — Containers and Compose](1-containers-and-compose.md).


# 7.1 — Containers and Compose

[← Chapter 7](README.md) · [Next: Kubernetes manifests →](2-kubernetes-manifests.md)

Phase 7's exit criterion is one sentence, and it is a good one:

> A fresh clone can be started using documented commands.

---

## Why now, and not earlier

[ADR-007](../01-design-contract/4-sequencing-and-deferrals.md) held containers back until the service
boundaries were stable — and warned about the cost of doing so:

> Nothing before Phase 8 proves the services are container-friendly. Configuration must come from
> environment variables and nothing may depend on local filesystem state, **or Phase 7 turns into a
> refactor**.

That is why [Chapter 2](../02-domain/1-project-skeleton.md) insisted on `${VAR:local-default}` from the
first `application.yml`. The bill comes due here, and it is small:

```yaml
environment:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/orderfulfillment
  KAFKA_BOOTSTRAP_SERVERS: kafka:29092
```

Two variables per service. Everything else already had a sensible default. **Containerization is a
packaging exercise rather than a rewrite**, exactly as ADR-007 predicted — because the discipline was
kept for six chapters with nothing verifying it.

> **Primer — [Docker: images, layers, and multi-stage builds](../technology/docker/images-and-layers.md)**
> Layer caching and instruction order, multi-stage builds, base-image choice, build context and
> `.dockerignore`, exec vs. shell entrypoint and signal handling, layered JARs, `ARG` vs `ENV`.

---

## The backend Dockerfile

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY services services
RUN mvn -q -pl services/common,services/order-service -am package -DskipTests
RUN rm -f services/order-service/target/*.jar.original

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/services/order-service/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Multi-stage: Maven and the JDK build; a JRE runs. The final image contains one jar and no build
tooling, no compiler, and no source.

### The build-context comment is the interesting part

```dockerfile
# Build context is the repo root (see docker-compose.yml: context: .) because Maven's reactor
# requires every module listed in the root pom.xml's <modules> to exist on disk even when -pl
# restricts which ones actually get built (verified empirically — `mvn -pl A,B -am` still fails
# fast with "Child module ... does not exist" if a sibling module directory is absent). So the
# build stage copies the whole services/ tree, then -pl/-am compiles only common + this service.
```

The obvious approach — context the service directory, copy only what that service needs — does not
work, and the comment says exactly why *and* that it was **verified empirically** rather than assumed.

`-pl services/common,services/order-service` restricts what is *built*; `-am` ("also make") builds
required dependencies. But the reactor still parses the root POM and insists every listed module
exists on disk.

**The cost, stated honestly:** copying the whole `services/` tree means a change in *any* service
invalidates the `COPY services services` layer in *every* service's image. Full rebuilds all round.
Acceptable at five services; the fix at fifty would be per-module POM copies before source, or a
purpose-built build image.

### `.jar.original`

```dockerfile
RUN rm -f services/order-service/target/*.jar.original
```

`spring-boot-maven-plugin` repackages the plain jar into an executable one and leaves the original
beside it as `*.jar.original`. The `COPY --from=build … target/*.jar` glob would match both, and
copying two files to one destination fails.

A small, real papercut of the kind that only shows up when you try.

---

## The frontend Dockerfile, and a genuine asymmetry

```dockerfile
FROM node:22-alpine AS build
ARG VITE_ORDER_SERVICE_URL=http://localhost:8081
ARG VITE_INVENTORY_SERVICE_URL=http://localhost:8082
# …
ENV VITE_ORDER_SERVICE_URL=$VITE_ORDER_SERVICE_URL
# …
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
```

Dependencies before source, so `npm ci` stays cached. Node builds; nginx serves. The runtime image
contains static files and a web server.

The header comment names the asymmetry that makes frontends different:

> The frontend is a browser-side SPA: `fetch()` calls made in the user's browser hit backend URLs
> **baked in at build time**.

A backend reads its configuration at startup, so one image serves every environment. **A browser-side
SPA has its configuration compiled into the bundle**, so the environment is chosen at build time —
hence `ARG` rather than `ENV`, and one image per environment.

The two builds:

> - **Local Compose / kind** (the default): the ARGs default to `http://localhost:808X`, which stays
>   correct because `docker-compose.yml` publishes every backend's port to the host, and
>   `kind-config.yaml` maps those same host ports for kind — either way the browser talks to the host.
> - **Production**: pass each ARG as a relative same-origin prefix, e.g.
>   `--build-arg VITE_ORDER_SERVICE_URL=/svc/order`. `apiFetch` and both `EventSource` URLs concatenate
>   `${baseUrl}${path}` unchanged either way, **so a relative prefix works with no other code change.**

That last clause is the payoff for a decision made in
[Chapter 2](../02-domain/6-the-first-frontend.md): base URLs as configuration, concatenated rather than
constructed. It costs nothing then and it is what makes
[Chapter 9](../09-production/README.md)'s single-hostname deployment a build argument rather than a
refactor.

### The nginx config exists for one line

> `nginx.conf` adds the SPA `try_files` fallback the stock `nginx:alpine` image doesn't have (a deep
> link or refresh on a client-side route 404s without it — see `App.tsx`'s `BrowserRouter`).

A client-side router owns paths the server knows nothing about. Request `/scenarios` directly — a deep
link, or a refresh — and nginx looks for a file called `scenarios`, finds none, and returns 404. The
fallback serves `index.html` for anything that is not a real file, and the router takes over.

The direct consequence of the deep-linkability requirement that motivated React Router in
[Chapter 5](../05-scenarios-and-frontend/5-the-console.md). Adopt a client-side router, own a server
rewrite rule.

---

## Compose

Ten services: PostgreSQL, Kafka, five backends, the frontend, Prometheus, and Grafana.

### The Kafka listener split

The longest comment in the file, and worth reading in full because it explains a failure that is
genuinely baffling the first time:

```yaml
KAFKA_LISTENERS: INTERNAL://0.0.0.0:29092,HOST://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:29092,HOST://localhost:9092
```

> **HOST** is what host-side tools (local `mvn spring-boot:run`, Testcontainers-based integration
> tests, `kafka-console-consumer` from your laptop) connect to via `localhost:9092`. **INTERNAL** is
> what containers on the compose network use via `kafka:29092` — a name that only resolves inside the
> compose network, which is the point: a container that connects on INTERNAL gets handed back the
> INTERNAL advertised address for subsequent produce/consume, instead of "localhost" resolving to
> itself. Without this split, a container-side client would connect fine on its first request (to the
> bootstrap address) and **then fail as soon as it tried to actually produce/consume**, because the
> broker's metadata response would tell it to reconnect to "localhost" — itself, not the broker.

The mechanism worth internalizing: **a Kafka client's bootstrap address is only used to fetch
metadata.** The broker then replies with its *advertised* addresses, and the client reconnects to
those for all real work.

So a single advertised address cannot serve two networks with different names for the same broker.
Connection succeeds, produce fails, and the error points at `localhost` — which, inside a container,
is the container itself.

This is the most common Kafka-in-Docker problem there is, and the answer is always two listeners.

### Health checks and ordering

```yaml
depends_on:
  postgres:
    condition: service_healthy
  kafka:
    condition: service_healthy
```

`depends_on` alone only orders *starts*. `condition: service_healthy` waits for the dependency's own
health check to pass — the difference between "Postgres has been started" and "Postgres will accept a
connection."

And the Kafka check is deliberately a real round trip:

> Broker API versions round-trip proves the broker is **actually accepting client connections** on the
> HOST listener, not just that the process is up (KRaft startup has a window where the process is
> running but not yet serving).

> **We got this wrong — later, and elsewhere.** This exact check, `kafka-broker-api-versions.sh`,
> **starts a JVM per invocation**. Harmless on a laptop every 5 seconds; on a 2-vCPU production box it
> was identified as the thing that flaps the broker under CPU contention, and
> [Chapter 9](../09-production/README.md) replaces it with a TCP socket check. A correct check whose
> *cost* was wrong for a different environment.

The backend health checks use `wget --spider` against `/actuator/health` — `wget` because
`nginx:alpine` and the Temurin images have it and not `curl`, and `--spider` because a HEAD-like
request is enough.

### One command

```bash
docker compose up --build
```

Ten containers, ordered by health, on one network, with the frontend on `localhost:5173` and each
backend on its own port. That is Phase 7's exit criterion satisfied — and it is what makes every later
chapter's "just run it" instruction honest.

---

[← Chapter 7](README.md) · [Next: Kubernetes manifests →](2-kubernetes-manifests.md)


# 7.2 — Kubernetes manifests

[← Containers and Compose](1-containers-and-compose.md) · [Next: Probes and resources →](3-probes-and-resources.md)

Twelve YAML files, numbered so `kubectl apply -f infrastructure/kubernetes/` applies them in order.
No Helm, no operators, no templating.

---

## Plain YAML, on purpose

ADR-007 rejected Helm for Phase 8:

> Templating would remove per-service duplication. Rejected for Phase 8 as premature: **plain YAML is
> what a reviewer can read without knowing Helm**, and five nearly identical Deployments are not yet a
> duplication problem worth a templating layer.

Worth defending, because "why no Helm?" is a certain question. Five Deployments differing in name,
image, and port are five files a reader can diff by eye. A chart is a second language between the
reader and the manifests, and the abstraction pays for itself somewhere north of "a handful of nearly
identical services."

[Chapter 9](../09-production/README.md) does eventually add a templating layer — **kustomize
overlays**, not Helm, and only when a genuine second environment appeared. That is the trigger to wait
for.

> **Primer — [Kubernetes: the object model](../technology/kubernetes/objects.md)**
> Reconciliation, Pods and Deployments, Services and their types, ConfigMaps and Secrets and what
> Secrets actually protect, namespaces, resource requests vs. limits, and reading cluster state.

---

## The file layout

```
00-namespace.yaml           orderfulfillment
01-secrets.yaml             postgres-credentials
02-postgres.yaml            PVC + Deployment + Service
03-kafka.yaml               Deployment + Service (KRaft)
04-order-service.yaml       ConfigMap + Deployment + Service
05-inventory-service.yaml   …the same shape ×4
09-frontend.yaml
10-inventory-service-hpa.yaml   (Sprint 2)
11-metrics-server.yaml          (Sprint 2)
```

Numeric prefixes because `kubectl apply -f <dir>` processes files in lexical order, and dependencies
run one way: namespace before anything in it, secrets before the pods that mount them.

**One file per service, holding all three objects** — ConfigMap, Deployment, Service — separated by
`---`. Everything about `order-service` is in `04-order-service.yaml`, which is the layout that reads
best when you are trying to understand one service rather than one object type.

---

## A service, end to end

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: order-service-config
  namespace: orderfulfillment
data:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/orderfulfillment
  KAFKA_BOOTSTRAP_SERVERS: kafka:29092
---
apiVersion: apps/v1
kind: Deployment
spec:
  replicas: 1
  template:
    spec:
      containers:
        - name: order-service
          image: order-service:local
          imagePullPolicy: IfNotPresent
          envFrom:
            - configMapRef:
                name: order-service-config
          env:
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: postgres-credentials
                  key: POSTGRES_PASSWORD
---
apiVersion: v1
kind: Service
spec:
  selector:
    app: order-service
  ports:
    - port: 8081
      targetPort: 8081
      nodePort: 30081
  type: NodePort
```

### The same environment variables as Compose

`SPRING_DATASOURCE_URL` and `KAFKA_BOOTSTRAP_SERVERS`, with `postgres` and `kafka` resolving through
cluster DNS instead of Compose DNS. **The application does not know which orchestrator it is running
under**, and never had to.

That is the whole return on the `${VAR:default}` discipline: two orchestrators, one image, zero
application changes.

### Configuration split by sensitivity

Non-secret values in a ConfigMap, injected wholesale with `envFrom`. Credentials in a Secret, injected
key by key with `secretKeyRef`.

The split is not about the injection mechanism — both end up as environment variables. It is about
**what can be read by whom**: Secrets can be restricted with RBAC and are excluded from the casual
`kubectl get -o yaml` habits that print ConfigMaps.

> **We got this wrong.** `01-secrets.yaml` originally contained a real, committed PostgreSQL password.
> A Secret manifest in git is a credential in git — base64 is an encoding, not encryption. Sprint 2's
> security pass caught it, and [Chapter 9](../09-production/README.md) replaces it with a
> `create-postgres-secret.sh` that generates the Secret imperatively, outside version control.
> [Chapter 10](../10-retrospective/README.md).

### `imagePullPolicy: IfNotPresent`

With no registry in Phase 8, images are built locally and loaded into the cluster with
`kind load docker-image`. The default policy for a tag other than `latest` would be `IfNotPresent`
anyway, but stating it prevents the cluster trying to pull `order-service:local` from Docker Hub and
failing with `ImagePullBackOff` — the single most common first-time kind error.

### `NodePort`, with fixed ports

```yaml
type: NodePort
ports:
  - port: 8081
    targetPort: 8081
    nodePort: 30081
```

`NodePort` rather than `ClusterIP` because the browser must reach these services from outside the
cluster and there is no ingress controller in Phase 8. Fixed node ports rather than
auto-assigned, so they can be mapped predictably — see [section 3](3-probes-and-resources.md).

> **This is exactly what has to change in production.** `NodePort` opens the port on **every node**.
> On an internet-facing box that means the `/demo/consumers` endpoints — the ones that pause consumers
> — are reachable by anyone who scans ports 30000–32767, regardless of any ingress rules.
> [Chapter 9](../09-production/README.md) closes the range at the firewall and routes everything
> through a single ingress instead.

---

## Stateful infrastructure

### PostgreSQL, and why not a StatefulSet

```yaml
# Single-instance Postgres in-cluster (plain Deployment + PVC, not a StatefulSet — this is a
# local demo cluster with one replica and no replication story, matching docker-compose.yml's
# postgres service; a StatefulSet would demonstrate nothing extra here).
```

The reflexive answer for a database is `StatefulSet`, and the comment declines it with a reason: what
a StatefulSet provides — stable network identity per replica, per-replica volumes, ordered rollout —
is only meaningful with **more than one replica**. With one instance and no replication, it is
ceremony.

Being able to say *what a StatefulSet would buy and why it does not apply here* is a better answer than
having used one.

Two details that do matter at one replica:

```yaml
strategy:
  type: Recreate # single PVC, ReadWriteOnce — never run two postgres pods at once
```

The default `RollingUpdate` starts the new pod before terminating the old one. With a
`ReadWriteOnce` volume the new pod cannot mount it, so the rollout hangs — and if it *could*, two
PostgreSQL processes on one data directory would corrupt it. `Recreate` takes the old pod down first,
accepting downtime as the correct trade.

```yaml
volumeMounts:
  - name: postgres-data
    mountPath: /var/lib/postgresql/data
    subPath: pgdata # avoid postgres complaining about lost+found in the mount root
```

A mounted volume's root often contains `lost+found`, and PostgreSQL refuses to initialize a data
directory that is not empty. `subPath` puts the data one level down. A one-line fix for an error
message that is otherwise thoroughly confusing.

### Kafka

Same shape — a single-node KRaft broker with the same dual-listener arrangement as Compose, with
`kafka:29092` for in-cluster clients.

Note what is **not** here: no replication, no multiple brokers, no rack awareness. Single-node with
`replication.factor=1`, matching
[Chapter 3](../03-kafka-and-services/1-events-on-the-wire.md)'s topic configuration. The project runs
Kubernetes to demonstrate **application** scaling and restart behavior, not to operate Kafka — and
`project-overview.md` rules "full production Kafka operations" out explicitly.

---

## What Phase 8 did *not* add

ADR-007's deferral list is worth reading as a statement of scope:

> **Deferred past Phase 8:** HorizontalPodAutoscaler, PodDisruptionBudget, NetworkPolicy, and service
> mesh — the last being an explicit non-goal.

- **HPA** — arrived in Sprint 2, after Phase 10 produced measurements to base it on.
  [Chapter 8](../08-observability-and-scaling/README.md).
- **PodDisruptionBudget** — meaningful with multiple replicas and voluntary disruptions. Neither
  applies to a one-replica local cluster.
- **NetworkPolicy** — would be the right answer to the `NodePort` exposure above. Rejected here and
  handled at the firewall in [Chapter 9](../09-production/README.md).
- **Service mesh** — an explicit non-goal in `project-overview.md`.

Each is deferred with a reason and a trigger, rather than listed as future work.

---

[← Containers and Compose](1-containers-and-compose.md) · [Next: Probes and resources →](3-probes-and-resources.md)


# 7.3 — Probes and resources

[← Kubernetes manifests](2-kubernetes-manifests.md) · [Chapter 7 ↑](README.md)

The part of Phase 8 that ADR-007 singled out as worth getting right, and the part most likely to come
up in conversation.

---

## Why ADR-007 flagged this specifically

> Readiness and liveness are treated as **genuinely different questions** when they are written [...]:
> a broker that is temporarily unreachable should fail readiness, not liveness, because restarting a
> healthy pod does not fix a dependency — **and being able to explain that distinction is part of what
> the project is for.**

An ADR naming a concept as something the project exists to demonstrate. The distinction is also the
one most commonly got wrong in real deployments, with a memorable failure mode.

> **Primer — [Kubernetes: health probes](../technology/kubernetes/probes.md)**
> The three probes and the question each answers, why a dependency check in liveness causes a restart
> storm, every timing field, probe types and their costs, startup probes, Spring Boot's health groups,
> and how probes amplify load-induced failure.

---

## The configuration

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8081
  initialDelaySeconds: 30
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 6

livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081
  initialDelaySeconds: 45
  periodSeconds: 15
  timeoutSeconds: 3
  failureThreshold: 6
```

**Liveness is slower and later in every dimension** — a 45-second initial delay against readiness's 30,
and a 15-second period against 5.

That asymmetry is deliberate and follows from the costs. Readiness failing is cheap: a pod briefly
leaves the load balancer, and comes back within seconds. Liveness failing is expensive: a restart, a
lost in-flight request, a cold JVM, and a fresh startup delay. **The expensive action should require
more evidence and more time.**

A liveness probe firing before the JVM has finished starting is the classic self-inflicted crash loop.
45 seconds plus 6 failures at 15-second periods means roughly two minutes before Kubernetes concludes
the process is unrecoverable.

---

## The finding: readiness that reflected nothing

This is the most instructive thing in Phase 8, and it came from checking rather than assuming.

```yaml
# Phase 8 finding: this app has no Kafka Actuator health indicator registered (verified live —
# /actuator/health's component list is only db/diskSpace/livenessState/ping/readinessState/ssl,
# no "kafka" entry), so Spring Boot's default readiness group (readinessState only) never
# reflects a broker outage. Wiring the one dependency indicator that IS registered (db) into the
# readiness group is what makes the readiness-vs-liveness distinction ADR-007 requires actually
# observable: a Postgres outage now fails readiness (pod pulled from Service endpoints) without
# touching liveness (no restart) — demonstrated live, see docs/agent-reports/phase-8-kubernetes.md.
# This is a K8s-manifest-level property override (Spring's relaxed env-var binding), not an
# application source change.
MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE: "readinessState,db"
```

Four things here, each worth separating.

**The default readiness group is nearly empty.** Spring Boot's `readiness` group contains only
`readinessState` — the application context's own lifecycle. It says "Spring finished starting." It
says **nothing about whether any dependency is reachable.**

A readiness probe against the default group therefore passes for a pod that cannot reach its database
or its broker. Traffic keeps arriving. The probe is decorative.

**The available indicators were checked, not assumed.** *"verified live — `/actuator/health`'s
component list is only `db`/`diskSpace`/`livenessState`/`ping`/`readinessState`/`ssl`, no `kafka`
entry."*

Spring Kafka registers **no broker health indicator by default**. Writing
`include: readinessState,db,kafka` would have looked correct and silently included nothing.

**The fix went in the manifest, not the source.** Spring's relaxed binding maps
`MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE` onto
`management.endpoint.health.group.readiness.include`, so a deployment-specific health policy stays in
the deployment. The application image is unchanged, and Compose is free to have a different policy.

**It was demonstrated, not asserted.** *"a Postgres outage now fails readiness (pod pulled from Service
endpoints) without touching liveness (no restart) — demonstrated live."* Delete the Postgres pod, watch
the backends leave the Service endpoints, watch them not restart, bring Postgres back, watch them
return.

That is the difference between having configured probes and having *demonstrated* the distinction —
which is what ADR-007 asked for.

> **Open question — the Kafka half is still unreflected.** Readiness now covers the database. A Kafka
> outage still fails nothing, because there is no indicator to include. A custom `HealthIndicator`
> calling `AdminClient.describeCluster()` would close it, and the repo does not record whether that
> was considered and rejected as scope or simply not reached. Worth knowing, since "what happens if
> Kafka goes down?" is a natural follow-up.

---

## Resources

```yaml
resources:
  requests:
    cpu: 150m
    memory: 320Mi
  limits:
    cpu: 500m
    memory: 640Mi
```

Per backend service: 0.15 cores and 320MiB reserved, capped at 0.5 cores and 640MiB.

**Requests are scheduling; limits are runtime**, and the two resources behave completely differently:

- **CPU is compressible.** Over the limit, the container is *throttled* — slower, not killed. A CPU
  limit set too low shows up as latency, which is much harder to attribute than a crash.
- **Memory is not.** Over the limit, the container is **OOM-killed** and restarted. No degraded mode.

For a JVM, that asymmetry is the whole story. Modern JVMs read the container memory limit and size the
heap from it — but heap is not the only thing a JVM allocates. Metaspace, thread stacks, code cache,
and direct byte buffers sit on top, and the JVM's default heap fraction leaves room for them only
approximately.

> **Not yet.** These manifests do **not** cap the JVM heap explicitly. On a laptop with headroom that
> is fine. On [Chapter 9](../09-production/README.md)'s 2-vCPU / 4GB production box it was blocking
> work item T2 — *"cap JVM heaps explicitly rather than relying on defaults"* — because five JVMs each
> sizing their own heap from their own limit is how you exhaust a small node.

**Requests × 8 pods is the real floor.** Five backends plus the frontend plus PostgreSQL plus Kafka is
what has to fit in a node's *allocatable* capacity — which is less than its total, because the kubelet
and system daemons reserve some. Sprint 2's sizing decision cites this project's own Phase 10
measurements: the 8-pod baseline stack runs inside 3.825GiB.

---

## Running it on kind

```yaml
# Maps container NodePorts to the same host ports Docker Compose already uses (8081-8085 for the
# 5 backend services, 5173 for the frontend), per docs/adr/ADR-007. Point of doing it this way:
# the frontend's Vite build already bakes in http://localhost:8081..8085 as its default backend
# URLs, so keeping those exact host ports means the existing frontend Docker image (built once by
# Phase 7, unmodified here) works against the kind cluster with zero rebuild and zero env changes —
# the browser can't tell it isn't talking to Compose.
extraPortMappings:
  - containerPort: 30081
    hostPort: 8081
```

**kind** runs a Kubernetes cluster inside Docker containers — a node is a container. `extraPortMappings`
publishes a container port to the host, which is how a NodePort becomes reachable from the browser.

The clever part is choosing to map `30081 → 8081`. The frontend image has `http://localhost:8081` baked
into its bundle ([section 1](1-containers-and-compose.md)), so preserving the host ports means **the
Phase 7 image works unmodified against Kubernetes.** The browser genuinely cannot tell which
orchestrator is behind it.

That is a small decision that removes a whole category of work — a second frontend build, a second set
of environment variables, and a second thing to keep in sync.

The workflow:

```bash
kind create cluster --config infrastructure/kind-config.yaml
docker compose build
kind load docker-image order-service:local --name orderfulfillment   # ×6
kubectl apply -f infrastructure/kubernetes/
```

`kind load` is the step people miss. There is no registry, so images must be **loaded into the node
container** explicitly. Skip it and you get `ImagePullBackOff` while the cluster tries Docker Hub.

ADR-007's reason for kind over Minikube: *"a `kind` cluster is free, starts in a minute, can be
recreated identically inside CI, and demonstrates the same Kubernetes objects."* The CI point is the
strongest — kind is designed to run inside a GitHub Actions runner.

---

## What running on Kubernetes actually demonstrates

ADR-007 was explicit that Kubernetes had to earn its place:

> Kubernetes gets introduced for reasons that can be defended in an interview — replicas, restart
> behavior, scaling — rather than as a checkbox.

Concretely, three things Compose cannot show:

**Multiple consumer replicas in one consumer group.** `kubectl scale deployment inventory-service
--replicas=3` and watch three pods share three partitions.
[Chapter 8](../08-observability-and-scaling/README.md).

**Pod restarts as a way to trigger consumer recovery.** Delete a pod and watch a rebalance, a
redelivery, and the idempotency ledger suppressing the duplicate — Scenario 5's mechanism, driven by
the platform instead of by a demo endpoint.

**Readiness gating during a rolling update.** New pods do not receive traffic until they are ready, so
a deploy does not drop requests. Which is also where a real problem surfaces:

> Deployment problems surface late, and some are only visible in a cluster: readiness gating during
> rolling updates, resource limits triggering OOM kills, and **SSE connections dropping when a pod is
> replaced**.

All three happened. Two of them are in [Chapter 9](../09-production/README.md).

---

[← Kubernetes manifests](2-kubernetes-manifests.md) · [Chapter 7 ↑](README.md)


<hr style="page-break-after: always;"/>

# Chapter 8 — Observability and scaling

**Build history:** Phase 9 (`a1cdcf2 observability`), Phase 10 (`55d55d7 scaling`), and Sprint 2's
HorizontalPodAutoscaler (`6212383`).

Two phases about being able to *see* the system, and one about making it bigger. They belong together
because you cannot responsibly do the second without the first — Phase 10's most valuable outputs are
measurements, and measurements need instrumentation.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [Structured logging](1-structured-logging.md) | ECS over an encoder library, the correlation ID finally becoming a field, and the audit that found four of five services logging nothing on a successful run |
| 2 | [Metrics](2-metrics.md) | Actuator and Micrometer, pull-based scraping, what you get for free, provisioned Grafana, and the Actuator CORS trap |
| 3 | [Scaling](3-scaling.md) | Partitions as the parallelism ceiling, Scenario 8, what Phase 10 could and could not measure, and what scaling does not fix |
| 4 | [The autoscaler](4-the-autoscaler.md) | The HPA, why `maxReplicas: 3` is architecture rather than budget, utilization as a fraction of *request*, and the incident where the autoscaler caused a second outage |

---

## The exit criteria

**Phase 9:** *A scenario can be followed across services without guessing what happened.*
**Phase 10:** *The project can demonstrate **why** Kafka consumer groups and Kubernetes scaling are
useful.*

Phase 10's phrasing is the interesting one. Not "the project scales" — demonstrate *why the mechanism
is useful*, which requires showing where it helps **and where it stops helping**. That is why the
partition ceiling matters more here than any throughput number.

---

## Four ideas worth carrying out

**A perfect mechanism attached to nothing is worth nothing.** Correlation-ID propagation was correct
from Chapter 3. Structured logging rendered it correctly in Phase 9. And a live scenario run still
produced *zero* log output in four of five services, because the only lines the consumers had were on
branches a successful run never takes. The audit caught it; the configuration change alone would not
have.

**`curl` is not a browser.** An endpoint can work perfectly under `curl` and be completely unusable
from a page, because `curl` sends no `Origin` header and ignores CORS entirely. Verify browser-facing
behavior in a browser.

**The partition count is the parallelism ceiling.** A Kafka consumer group can never usefully run more
consumers than partitions. "Just add pods" is not an answer — and raising the partition count changes
which partition existing keys hash to, so it is not a free fix either.

**Startup CPU is not load.** An autoscaler with no scale-up stabilization read five JVMs cold-starting
after a deploy as sustained demand, added replicas at the moment the box had least memory, and caused a
second outage on top of the first. Every decision it made was correct given its inputs.

---

## Build it yourself

**Logging** — [section 1](1-structured-logging.md)

1. `logging.structured.format.console: ecs` in every service. No dependency, no `logback-spring.xml`.
2. Confirm `spring.application.name` is set — it becomes `service.name`.
3. **Audit your log call sites.** Count them, run a real end-to-end workflow, and check what actually
   came out per service. If a service produced nothing, its only lines are on branches the happy path
   never takes.
4. Add one `INFO` per consumer happy path, **inside** the correlation scope, right after the event is
   confirmed relevant. Plus the first HTTP hop and wherever a workflow's correlation ID is minted.
5. Ensure the catch-all exception handler logs its exception: `log.error(…, ex)`.
6. Verify: `docker compose logs | grep <correlation-id>` returns the whole workflow across all five
   services.

**Metrics** — [section 2](2-metrics.md)

7. `spring-boot-starter-actuator` + `micrometer-registry-prometheus` per service.
8. `management.endpoints.web.exposure.include: health,metrics,prometheus` — nothing more.
9. `prometheus.yml` scraping every service's `/actuator/prometheus`.
10. Grafana **provisioned from files** — datasource, dashboard provider, and the dashboard JSON, all in
    version control.
11. `management.endpoints.web.cors.allowed-origin-patterns` — Actuator has its **own** CORS block and
    does not use `WebConfig`'s. Point both at one property.

**Scaling** — [section 3](3-scaling.md)

12. A high-volume scenario reading **real broker-side lag** via `AdminClient`, not a self-reported
    counter.
13. `kubectl scale deployment/inventory-service --replicas=2`, run the scenario, and record throughput,
    latency, and lag at 1 and 2 replicas. Watch the rebalance.
14. Try 3, and **record what happens** — including if your hardware runs out first. That number is
    worth having.

**Autoscaler** — [section 4](4-the-autoscaler.md)

15. `metrics-server` (kind only — k3s bundles one).
16. An `autoscaling/v2` HPA on the consumer that actually saturates, targeting the resource that
    actually saturates. `maxReplicas` = **the partition count**, and say so in a comment.
17. `averageUtilization` below 100 and relative to the **request** — know which number you are a
    percentage of.
18. `scaleDown.stabilizationWindowSeconds: 120` against flapping, and
    `scaleUp.stabilizationWindowSeconds: 60` so a cold-start CPU spike is not read as demand.
19. Verify **both directions** and keep the `kubectl describe hpa` events as evidence.

**Done when:** one grep returns a whole workflow across five services; Grafana comes up configured from
a fresh clone; the health page works *in a browser*; a scenario run reports real consumer lag; scaling
Inventory Service to 2 measurably drains the backlog faster; and the HPA scales up under load and back
down afterwards, with the controller's own events as proof.

---

## Next

[Section 1 — Structured logging](1-structured-logging.md).


# 8.1 — Structured logging

[← Chapter 8](README.md) · [Next: Metrics →](2-metrics.md)

Phase 9's gate:

> A scenario can be followed across services without guessing what happened.

Which turned out to require two things, only one of which was the logging configuration.

---

## What already existed, and what was missing

The [correlation-ID plumbing](../patterns/correlation-id-propagation.md) was built in
[Chapter 3](../03-kafka-and-services/3-correlation-ids.md). `CorrelationIdFilter` put the ID into
SLF4J's MDC for every HTTP request; `CorrelationIdHolder.runInScope` did the same for every Kafka
listener thread.

And it rendered nowhere:

> no service's logging configuration actually rendered the MDC value anywhere, and every
> `application.yml` had a bare `logging.level.com.orderfulfillment: INFO` with no pattern or
> structured-format setting at all.

**The MDC is a place to put values, not a mechanism for emitting them.** The plumbing was correct and
completely invisible — every line was written without the one field that made it traceable.

---

## Structured logging, and why it is not just formatting

A conventional log line is a sentence:

```
2026-08-19 14:03:22.118  INFO 1 --- [ntainer#0-0-C-1] c.o.i.InventoryOrderEventsConsumer : Processing OrderCreated 0c7c3acd for order order-21873
```

Readable, and opaque to anything that is not a human with a regular expression. Which service wrote
it? Parse the thread name, or infer it from the logger. Which correlation ID? It is not there at all.

A structured line is a record:

```json
{"@timestamp":"2026-08-19T14:03:22.118Z","log.level":"INFO","service.name":"inventory-service",
 "correlationId":"d89512f7-b544-4170-b66b-2e93f475ea8f",
 "message":"Processing OrderCreated 0c7c3acd for order order-21873"}
```

Same information, plus fields. **`grep` becomes a query.** Filter by `correlationId` and get one
workflow. Filter by `service.name` and `log.level` and get one service's errors. No pattern to
maintain, no ambiguity when a message happens to contain something that looks like an ID.

## The decision: native, not a library

```yaml
logging:
  structured:
    format:
      console: ecs
```

Four lines of configuration. No dependency, no `logback-spring.xml`.

Spring Boot 4.1 ships structured logging natively with `ecs`, `logstash`, and `gelf` built in. The
conventional choice — `logstash-logback-encoder` — was rejected on a rule rather than a preference:

> it is an entire extra dependency and a Logback XML file for a capability Spring Boot 4.1 now ships
> natively — `agent-guidance.md` rule 11 asks for extra infrastructure to be justified, and **"we're
> used to it from other projects" isn't a justification** when the built-in option covers the same
> ground.

### ECS over logstash, decided by testing both

Both put MDC entries in the output. The difference was found by trying them:

> only `ecs` maps `spring.application.name` to a top-level `service.name` field. A quick local test
> confirmed `logstash`'s output has **no service-identifying field at all** — every service's lines
> would look identical except for their content, defeating half the "service name" requirement.

**ECS** is Elastic Common Schema — a published field-naming convention (`@timestamp`, `log.level`,
`service.name`, `error.type`) that anything in the Elastic ecosystem understands without mapping.

Two things to take from this. **The decision was made by running both and looking at the output**,
not by reading documentation. And `spring.application.name` — set in
[Chapter 2](../02-domain/1-project-skeleton.md) as an apparently cosmetic line — is what makes the
whole thing work.

---

## The gap the configuration did not close

This is the part worth studying, because it is where a phase nearly declared victory on a
configuration change that satisfied nothing.

> Wiring the pattern alone was not sufficient to satisfy the actual gate. Auditing every `log.*` call
> in the codebase before this phase found only **32 call sites total**, and on the happy path of the
> domain Kafka consumers the only `INFO` log line in each was the **duplicate-delivery skip branch** —
> an edge case, never hit on a normal run. A live `standard-order` scenario run before this fix
> produced **zero log output identifying the workflow in 4 of the 5 services.**

Read that again. Correlation-ID propagation: working. Structured logging: configured. Every field
correct.

**And a normal scenario run produced no log lines at all in four of five services** — because the only
lines the consumers had were on branches a successful run never takes. A perfect tracing mechanism
attached to nothing.

The fix was not clever: one `INFO` line per consumer's happy path, *"right after the event is confirmed
relevant, before/around the side effect,"* plus `OrderController.createOrder` (the workflow's first
hop) and `ScenarioRunExecutor` (where a scenario's correlation ID is minted).

Which is why those two lines are positioned the way [Chapter 5](../05-scenarios-and-frontend/README.md)
noted — *inside* the correlation scope, as the first statement, so they are the first line of the
trace rather than an untagged line just outside it.

**The lesson generalizes past logging.** A mechanism can be perfectly correct and completely useless
because nothing invokes it. The audit — count the call sites, run the real workflow, check what
actually came out — is what caught it, and *"a live standard-order scenario run before this fix
produced zero log output in 4 of the 5 services"* is the kind of measurement worth taking before
declaring a gate met.

---

## The second gap: a 500 that logged nothing

> `GlobalExceptionHandler.handleUnexpected` caught every uncaught exception and returned a 500
> `ApiError` — carrying the correlation id in the response body — but **never logged the exception
> anywhere.** A real 500 during verification left zero trace in any service's log, in direct
> contradiction of this phase's whole purpose.

The worst possible failure mode: the client is told something broke, and the server has no record of
what.

And note how it was found — *"it is what surfaced the actual bug hit during verification (a wrong URL
in a manual test), and without the fix that bug would have been undiagnosable from logs alone."* The
gap was discovered by hitting it.

The fix is one line, and it is why [Chapter 2](../02-domain/3-the-http-layer.md) builds the handler
with it from the start:

```java
log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
```

MDC's `correlationId` attaches automatically through the ECS path, so the log line and the error
response the user is holding carry the same identifier.

> **We got this wrong.** Both gaps — consumers with no happy-path logging, and a catch-all that
> discarded its exception — existed from Phase 1 until Phase 9.
> [Chapter 10](../10-retrospective/README.md).

---

## What it looks like in use

```bash
docker compose logs | grep d89512f7-b544-4170-b66b-2e93f475ea8f
```

Every line, from all five services, for one workflow, in order. The `application.yml` comment states
the goal precisely:

> `docker compose logs <service> | grep <correlation-id>` finds a workflow's hops across all 5 services
> **without decoding a custom text pattern**, and every line is self-describing which service emitted
> it.

Where to get the correlation ID from, in practice:

- The `X-Correlation-Id` **response header** on any request you made.
- The `correlationId` field in an **`ApiError` body**, if something failed.
- The **scenario run**, which mints one per run and shows it in the UI.
- Any **event envelope** in the Event Explorer.

The same value in all four places, because it is the same value everywhere.

> **Where this pays off.** ECS output is JSON on stdout, which is exactly what a log aggregator wants.
> This project does not ship one — no Elasticsearch, no Loki, no cloud logging — and that is a scope
> decision, not an oversight. The format means adding one later is a collector configuration rather
> than an application change.

---

[← Chapter 8](README.md) · [Next: Metrics →](2-metrics.md)


# 8.2 — Metrics

[← Structured logging](1-structured-logging.md) · [Next: Scaling →](3-scaling.md)

Logs answer "what happened to *this* order." Metrics answer "how is the system doing." Different
questions, different tools, and conflating them is how you end up counting log lines.

---

## The three pillars, and which two are here

Conventionally: **logs** (discrete events), **metrics** (aggregated numbers over time), **traces**
(one request's path with timings).

This project has the first two. Tracing is deliberately absent — the
[correlation-ID pattern](../patterns/correlation-id-propagation.md) gives *correlation* without spans
or timings, and [Chapter 3](../03-kafka-and-services/3-correlation-ids.md) covers what that does and
does not buy.

**Logs do not aggregate.** "What is the p99 latency of `POST /api/orders`?" is not a log question —
answering it from logs means parsing every line and computing percentiles, which is a data pipeline.
A metrics system computes it as it goes, at a fixed cost per measurement rather than per event.

**Metrics do not particularize.** "Why did order-21873 fail?" is not a metrics question. A counter that
went up tells you nothing about which one.

---

## The stack

```xml
<dependency>...spring-boot-starter-actuator</dependency>
<dependency>...micrometer-registry-prometheus</dependency>
```

Two dependencies in each of the five services.

**Actuator** exposes operational endpoints — health, metrics, info, environment.

**Micrometer** is a metrics *facade*, the SLF4J of metrics: instrument once against its API, choose the
backend by adding a registry dependency. `micrometer-registry-prometheus` is that choice here, and it
adds `/actuator/prometheus`.

**Prometheus** is a pull-based time-series database. It **scrapes** each service on an interval rather
than receiving pushes.

**Grafana** queries Prometheus and draws it.

### Exposure is opt-in

```yaml
management:
  endpoints:
    web:
      exposure:
        # Phase 9 Observability: metrics added to the previously health-only exposure
        include: health,metrics,prometheus
```

Actuator exposes only `health` over HTTP by default, and everything else must be named. That default is
correct — `/actuator/env` prints your entire configuration, `/actuator/heapdump` returns a heap dump —
and this project adds exactly three.

Worth noticing that Phase 8 needed only `health`, and Phase 9 added the other two when there was
something to read them.

### Pull, not push

```yaml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: order-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["order-service:8081"]
```

Prometheus pulling has consequences worth understanding, because they are the opposite of most
monitoring systems:

- **A service that stops responding is visibly down.** The scrape fails; that failure is itself a
  signal. A push-based system cannot distinguish "nothing to report" from "gone."
- **The application does not know about Prometheus.** It exposes an endpoint. Nothing is configured
  with a collector address, and nothing breaks when the collector is down.
- **Scrape interval is the resolution.** 5 seconds here — fine-grained for a demo where a scenario
  lasts seconds. Production deployments typically use 15–60s to bound storage.
- **Targets must be discoverable.** `static_configs` works because Compose gives every service a DNS
  name. In Kubernetes you would use service discovery instead.

Note the coupling: `static_configs` naming Compose service names means **this Prometheus config works
under Compose and not under Kubernetes.** A real deployment needs `kubernetes_sd_configs` or the
Prometheus Operator. The observability stack here is a local development tool, and
[Chapter 9](../09-production/README.md) does not deploy it.

---

## What you get for free

The instrumentation that arrives without writing any:

| Metric | Answers |
|---|---|
| `http_server_requests_seconds` | Request rate, latency percentiles, error rate — by URI, method, and status |
| `jvm_memory_used_bytes` | Heap and non-heap by pool |
| `jvm_gc_pause_seconds` | GC frequency and duration |
| `jvm_threads_live_threads` | Thread count |
| `hikaricp_connections_*` | Connection pool usage, pending threads, timeouts |
| `kafka_consumer_*` | Consumer throughput, fetch latency, and **records-lag** |
| `process_cpu_usage`, `system_cpu_usage` | CPU |

Two are worth singling out.

**`hikaricp_connections_pending`** — threads waiting for a database connection. Non-zero means the pool
is the bottleneck, which is a common and easily-misattributed cause of latency. It usually looks like
"the database is slow" when the database is idle.

**`kafka_consumer_records_lag_max`** — the consumer's own view of lag, per partition, exported by the
Kafka client. Distinct from the broker-side lag `ConsumerLagService` reads
([Chapter 5](../05-scenarios-and-frontend/4-observing-the-system.md)): this one is a *metric* for
graphing over time, that one is a *point query* for a scenario to report. Same underlying idea, two
different consumers of it.

**No custom metrics.** No `Counter` for orders created, no `Timer` around reservation. Defensible for a
demo — the built-in instrumentation covers the operational questions, and business counters would be
the first thing to add for a real system. Worth naming as an absence rather than leaving unmentioned.

---

## Grafana

`infrastructure/observability/grafana/` holds a provisioned datasource, a dashboard provider, and one
dashboard — `order-fulfillment-overview.json`.

**Provisioned, not clicked.** Grafana reads YAML at startup and configures itself, so
`docker compose up` produces a working dashboard with no setup. The alternative — a Grafana whose
configuration lives only in its own database — is a dashboard that exists on one machine and nowhere
in version control.

The dashboard is stretch-goal scope in the phase plan (*"Optional: Prometheus, Grafana"*), and treating
it as configuration-as-code is what makes it worth having at all.

---

## The CORS trap

The System Health page from [Chapter 5](../05-scenarios-and-frontend/4-observing-the-system.md) polls
`/actuator/health` from the browser. It did not work, and the reason is the kind of thing that costs an
afternoon:

```yaml
      # Actuator endpoints are served via a separate WebMvcEndpointHandlerMapping that does NOT go
      # through WebConfig's WebMvcConfigurer#addCorsMappings (that only covers regular
      # @RestController endpoints) — so the frontend's browser-side calls to /actuator/health were
      # silently blocked by CORS until this was added, even though curl (which doesn't enforce CORS)
      # showed the endpoint working fine. Found via live browser verification, not curl.
      cors:
        allowed-origin-patterns: "${app.cors.allowed-origin-patterns}"
        allowed-methods: GET
```

**Actuator endpoints do not go through your CORS configuration.** They are served by a separate handler
mapping, and `WebMvcConfigurer#addCorsMappings` covers only `@RestController` endpoints. Actuator has
its own `management.endpoints.web.cors.*` block.

The detail that makes it expensive: *"even though `curl` (which doesn't enforce CORS) showed the
endpoint working fine."* `curl` is not a browser. It sends no `Origin` header and ignores response
headers about who may read the response. **An endpoint can be perfectly functional under `curl` and
completely unusable from a page** — and every instinct says to verify with `curl` first.

*"Found via live browser verification, not curl"* is the practice worth adopting: verify
browser-facing behavior in a browser.

Note also that both blocks reference one property:

```yaml
app:
  cors:
    # Single source of truth for allowed browser origins, consumed by both WebConfig and the
    # actuator CORS block below.
    allowed-origin-patterns: ${APP_CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:*}
```

Two mechanisms, one configured value — so a deployment cannot fix one and forget the other.

> **Primer — [CORS](../technology/http/cors.md)**
> What the same-origin policy protects, preflight requests, why `curl` proves nothing, and when a
> reverse proxy removes the problem entirely.

---

## What Phase 9 delivered against its gate

> A scenario can be followed across services without guessing what happened.

- **One correlation ID** through every HTTP request, event envelope, and log line.
- **Structured JSON** with `service.name` and `correlationId` as queryable fields.
- **Happy-path logging** at every hop — the gap that made the rest of it useless until it was audited.
- **Every 500 logged** with its exception attached.
- **Metrics** for request rate, latency, JVM, connection pool, and consumer lag.
- **A provisioned dashboard**, in version control.

What is absent, and worth being able to say: **no distributed tracing** (correlation without spans or
timings), **no log aggregation** (JSON on stdout, no collector), and **no custom business metrics**.
Each is a scope decision with an obvious next step, and the ECS format and Micrometer facade are
specifically what make those next steps configuration changes rather than rewrites.

---

[← Structured logging](1-structured-logging.md) · [Next: Scaling →](3-scaling.md)


# 8.3 — Scaling

[← Metrics](2-metrics.md) · [Next: The autoscaler →](4-the-autoscaler.md)

Phase 10's exit criterion is unusual, and it is the right one:

> The project can demonstrate **why** Kafka consumer groups and Kubernetes scaling are useful.

Not "the project scales." Demonstrate *why the mechanism is useful* — which requires showing where it
helps, and equally where it stops helping.

---

## What scaling means for a consumer

Adding replicas of an HTTP service is uncomplicated: a load balancer spreads requests, and more
instances means more concurrent requests. There is no natural ceiling short of a downstream
bottleneck.

A Kafka consumer is different, and the difference is the whole lesson.

Within a consumer group, **each partition is assigned to exactly one consumer.** That is the mechanism
that gives per-partition ordering — two consumers reading one partition could process its records
concurrently, and ordering would be gone.

So:

| Replicas | Partitions each | Effect |
|---|---|---|
| 1 | 3 | Baseline |
| 2 | 2 + 1 | Roughly 2× parallelism |
| 3 | 1 + 1 + 1 | Maximum useful parallelism |
| 4 | 1 + 1 + 1 + **0** | **The fourth does nothing** |

> a Kafka consumer group can never usefully have more running consumers than partitions — a 4th
> replica would sit idle with no partition assigned.

**The partition count is the parallelism ceiling**, and it was fixed at 3 back in
[Chapter 3](../03-kafka-and-services/1-events-on-the-wire.md).

That is the most useful thing in this chapter. "Just add more pods" is not an answer for a Kafka
consumer; you would have to increase the partition count first — and doing so **changes which
partition existing keys hash to**, breaking per-key ordering across the change.

Which is why the number is worth choosing carefully at design time and is awkward to change later.

---

## Scenario 8

Create many orders quickly and measure what happens. The success condition is a **measurement**, not a
state — the only scenario in the set where that is true.

**Inventory Service is the target**, and the reasoning is specific:

> it's the consumer Scenario 8 is specifically written to stress — it consumes every `OrderCreated` off
> `orders.events` and does a reservation write per order — and it's the exact service Phase 10 already
> scaled by hand and measured (1 vs 2 replicas: consumer lag and throughput both moved). This HPA
> targets **CPU** because that's what visibly saturates first under that scenario's write load, not
> I/O wait or memory.

Three separate observations behind one choice: which service saturates, that scaling it measurably
helps, and *which resource* saturates first. None of them is guessable — all three came from running
it.

The scenario reads **real broker-side lag** through `ConsumerLagService`
([Chapter 5](../05-scenarios-and-frontend/4-observing-the-system.md)), not a self-reported counter. It
is the same number `kafka-consumer-groups.sh --describe` prints.

**Lag is the metric that matters**, because it is the only one that answers *"is this keeping up?"*
Throughput tells you how fast something is going, not whether that is fast enough. Lag climbing means
arrival exceeds processing; lag flat means they match; lag draining means you are catching up.

---

## What Phase 10 actually measured

This is where the honest part of the story is.

> the local `kind` Docker Desktop VM's ~3.8GB ceiling meant **3 replicas of Inventory Service alongside
> the rest of the 8-pod stack pushed the node into CPU/memory contention and Kafka readiness-probe
> flapping before any scenario load was even applied.**

So Phase 10 could demonstrate 1 → 2 replicas and could not reach 3 on the development laptop. Not
because 3 is wrong — it is exactly right, matching the partition count — but because **the hardware ran
out before the architecture did.**

That is recorded rather than hidden, and it is a better outcome than a clean graph would have been.
Two things came out of it that a successful run would not have produced:

**A concrete resource budget.** The 8-pod baseline stack fits inside 3.825GiB. That number is what
Sprint 2's deployment sizing decision cites when choosing a production box — a measurement, not an
estimate.

**The probe finding.** *"Kafka readiness-probe flapping"* under CPU contention identified the
`kafka-broker-api-versions.sh` health check — which starts a JVM per invocation
([Chapter 7](../07-containers-and-kubernetes/1-containers-and-compose.md)) — as a real cost on a
constrained node. That became blocking work item T1 in
[Chapter 9](../09-production/README.md), before it could take down the public demo.

**A capacity limit found in testing is a capacity limit not found in production.** Phase 10's inability
to reach 3 replicas is the reason Chapter 9's production box was sized and tuned correctly on the first
attempt.

---

## Demonstrating it by hand

```bash
kubectl scale deployment/inventory-service --replicas=2
kubectl get pods -n orderfulfillment -w
```

Then run Scenario 8 and watch:

- **`kubectl get pods`** — a new pod, then a **rebalance** as the group reassigns partitions.
- **Consumer lag** — climbing during the burst, draining faster with two consumers than one.
- **The Grafana dashboard** — CPU across both replicas, and per-replica consumer lag.
- **Order completion** — the same total work finishing sooner.

The rebalance is worth watching specifically. Adding a consumer to a group triggers a partition
reassignment, and **processing pauses** while it happens. At small scale that is a blip; at large scale
it is why people care about cooperative rebalancing protocols. It is also one of the ordinary causes of
duplicate delivery ([Chapter 4](../04-reliability/README.md)) — a partition's uncommitted records get
reprocessed by their new owner, and the idempotency ledger absorbs it.

So a scale-up exercises the reliability machinery as a side effect. Watching lag drain while
`processed_events` quietly suppresses redeliveries is two chapters demonstrating themselves at once.

---

## What scaling does not fix

Worth being able to say, because it is the follow-up question.

**More replicas than partitions does nothing.** The ceiling is 3.

**Order Service does not scale the same way.** Its status writes take a per-order pessimistic row lock
([Chapter 4](../04-reliability/4-out-of-order-transitions.md)). That serializes *per order*, so
different orders never contend and replicas still help — but it is a different profile from Inventory's
CPU-bound write loop, and it would need its own measurement rather than an assumption.

**PostgreSQL does not scale here at all.** One instance, one PVC,
`strategy: Recreate` ([Chapter 7](../07-containers-and-kubernetes/2-kubernetes-manifests.md)). Every
service's replicas share it. Past a certain load the database is the bottleneck and no amount of pod
scaling helps — which is the usual shape of real systems and worth naming rather than implying that
horizontal scaling is unbounded.

**Kafka does not scale here either.** One broker, replication factor 1. Deliberate scope
(`project-overview.md` rules out "full production Kafka operations").

---

[← Metrics](2-metrics.md) · [Next: The autoscaler →](4-the-autoscaler.md)


# 8.4 — The autoscaler

[← Scaling](3-scaling.md) · [Chapter 8 ↑](README.md)

Sprint 2 goal 6. Turning Phase 10's manual `kubectl scale` into a `HorizontalPodAutoscaler` — and then
learning something from it the hard way.

---

## Why it waited

ADR-007 deferred the HPA past Phase 8, and the sequencing is the point:

1. **Phase 8** — Deployments and Services. No autoscaling.
2. **Phase 10** — scale by hand, measure what happens, find the ceiling.
3. **Sprint 2** — encode the measured behavior as an autoscaler.

An HPA written in Phase 8 would have needed a target CPU percentage, a replica ceiling, and
stabilization windows — every one of them guessed. By Sprint 2, each was **derived from something
observed**.

---

## The manifest

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef:
    kind: Deployment
    name: inventory-service
  minReplicas: 1
  maxReplicas: 3
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 65
```

An HPA is a controller: read a metric, compare against a target, adjust the replica count, repeat.

### `maxReplicas: 3` is not a budget

> `orders.events` has a fixed 3-partition count, and a Kafka consumer group can never usefully have
> more running consumers than partitions — a 4th replica would sit idle with no partition assigned.
> **3 is the actual ceiling of "more replicas helps," not an arbitrary cap.**

This is the single best detail in the manifest. Most `maxReplicas` values are cost limits or guesses.
This one is a **property of the system** — beyond 3, the autoscaler would add pods that consume
memory, take a scheduling slot, and process nothing.

An HPA that could scale past the partition count would actively make things worse under load, which is
the opposite of what an autoscaler is for.

### `averageUtilization: 65` is relative to the request

```yaml
# Against the 150m CPU request in 05-inventory-service.yaml: scale up once average usage across
# replicas passes ~97m. Deliberately below 100% so the HPA reacts before the container is fully
# CPU-starved, not after.
```

**CPU utilization in an HPA is a percentage of the *request*, not of the node or the limit.** 65% of a
150m request is ~97m — and since the *limit* is 500m, a pod at "65% utilization" is using about a fifth
of what it is allowed. Reading this as "65% of capacity" is a common and consequential misreading.

Targeting below 100% is deliberate: an autoscaler that waits for saturation is scaling up *after* the
damage. Pods take time to schedule, pull, and start — a JVM especially — so the trigger has to lead the
problem.

### metrics-server

> Requires metrics-server or an equivalent `metrics.k8s.io` provider — **the HPA controller has nothing
> to read CPU from otherwise.**

Not installed by default in kind (hence `11-metrics-server.yaml`), and bundled with k3s — so the
production overlay omits it. An HPA with no metrics source reports `<unknown>/65%` and does nothing,
silently.

---

## The scale-down window

```yaml
scaleDown:
  # Default HPA behavior scales down almost immediately once utilization drops, which reads as
  # flappy in a demo (add a pod, drop it 30s later, add it back). A 2-minute stabilization window
  # means the scale-down decision looks at the max recommendation over the last 2 minutes, so a
  # brief dip mid-burst doesn't undo a scale-up that's still needed.
  stabilizationWindowSeconds: 120
```

A **stabilization window** makes the controller consider the *maximum* recommendation over the window
rather than the instantaneous one. A momentary dip cannot undo a scale-up that is still needed.

Asymmetric on purpose: scaling down too eagerly costs you the pod you are about to need again, plus
another cold start.

---

## The scale-up window, and the incident behind it

This is the best operational story in the project.

```yaml
scaleUp:
  # Originally 0s (react instantly) so a burst's scale-up is visible while it's still draining. A
  # same-night incident showed the cost of that: right after a deploy restarts all five backend
  # services (see ADR-011), five JVMs cold-starting at once produce a CPU spike from class loading
  # and Spring context init, not real request load — and at 0s stabilization the HPA read that
  # spike as sustained demand and added two more inventory-service replicas during the exact window
  # the box had the least spare memory, causing a second outage on top of the first.
  stabilizationWindowSeconds: 60
```

Follow the chain:

1. A deploy restarts all five backend services at once.
2. Five JVMs cold-start simultaneously. Class loading and Spring context initialization are
   **CPU-intensive** — for tens of seconds, and for reasons having nothing to do with load.
3. The HPA, with **zero** scale-up stabilization, reads that spike as sustained demand.
4. It adds two more Inventory Service replicas — **during the exact window the box had least spare
   memory**, because five JVMs were already starting.
5. Two more JVMs start. The node runs out. **A second outage, on top of the first.**

The autoscaler, working exactly as configured, converted a rough deploy into an outage. Every
individual decision was correct given its inputs; the inputs were misleading.

**Startup CPU is not load.** An autoscaler that cannot tell them apart will amplify every restart into
a scale-up, at precisely the moment the system has least headroom.

The fix, and why 60 seconds specifically:

> 60s filters that out: cold-start CPU settles within tens of seconds once the JVM finishes
> initializing, while a genuine Scenario 8 burst (which drains over 12–22s but keeps inventory-service
> busy for longer than that as orders queue) stays elevated well past 60s and still triggers a real
> scale-up — just not an instantaneous one.

**A threshold chosen to sit between two measured durations.** Cold-start CPU settles in tens of
seconds; genuine burst load stays elevated past 60. The window separates them. Both numbers came from
observation.

And the discipline in what was *not* changed:

> The CPU threshold itself (65%) is unchanged: it's already validated against real load and isn't what
> caused this.

**Change the thing that caused the problem, not everything nearby.** 65% was validated; the incident
was about *duration*, not *level*. Adjusting both would have invalidated the one number that was known
good.

This also connects to [Chapter 9](../09-production/README.md): the deploy that restarted all five
services at once is ADR-011, and the fix there — sequential, wait-for-health rollouts — addresses the
same incident from the other end. **Two fixes to one incident, at two layers**, neither sufficient
alone.

---

## Verified, not asserted

> Verified for real on the Hetzner dev box running Scenario 8 against a live `kind` cluster — real
> `kubectl get hpa` / `kubectl describe hpa` output, not a hypothetical: CPU utilization crossed the
> 65% target after the burst's submitted orders started draining, the HPA rescaled Inventory Service
> from 1 to 2 replicas (`SuccessfulRescale ... New size: 2; reason: cpu resource utilization
> (percentage of request) above target`), and once the backlog drained and utilization stayed low past
> the stabilization window it scaled back down to 1 (`SuccessfulRescale ... New size: 1; reason: All
> metrics below target`).

Both directions, with the controller's own event messages quoted as evidence.

Note *where*: the Hetzner dev box, because the laptop could not do it. Phase 10 hit the ~3.8GB Docker
Desktop ceiling; Sprint 2 provisioned a machine with headroom specifically so the demonstration could
be completed. **The infrastructure workstream existed because a measurement was blocked** — which is a
better reason to provision a box than "it would be convenient."

---

## What an HPA is and is not

**Is:** a controller that keeps a metric near a target by adjusting replica count, within bounds you
set.

**Is not:**

- **Instant.** Scheduling, image pull, JVM start, readiness — tens of seconds before a new pod helps.
  It cannot absorb a spike shorter than that.
- **Aware of your architecture.** It does not know about partition counts. `maxReplicas: 3` is
  knowledge *you* supplied.
- **A fix for a saturated dependency.** More consumers against one PostgreSQL instance move the
  bottleneck rather than removing it.
- **Free of feedback loops.** The incident above is exactly one: the autoscaler's action made the
  condition it was reacting to worse.

The right framing: an HPA handles **sustained, gradual** changes in demand. It is the wrong tool for
spikes shorter than a pod start, and a dangerous one if it can misread a non-load signal as load.

---

## Chapter 8 in one paragraph

Correlation IDs that had been propagating correctly since Chapter 3 finally became visible, once
structured logging rendered them *and* an audit found that four of five services logged nothing at all
on a successful run. Metrics arrived through Actuator and Micrometer with request latency, JVM state,
connection-pool pressure and consumer lag for free, and a CORS trap that made health checks work under
`curl` and fail in a browser. Scaling demonstrated the property that actually matters — a Kafka
consumer group cannot usefully exceed its partition count — and found the laptop's ceiling before the
architecture's. And the autoscaler that encoded all of it turned a rough deploy into an outage by
mistaking JVM cold-start CPU for demand, which is now sixty seconds of stabilization and a very good
story.

---

[← Scaling](3-scaling.md) · [Chapter 8 ↑](README.md) · [Chapter 9 — Production →](../09-production/README.md)


<hr style="page-break-after: always;"/>

# Chapter 9 — Production

**Build history:** Sprint 2 goal 4 — ADR-010 (platform), ADR-011 (`0c5ad13 fix production redeploys
taking the demo box down`), plus the security pass and the GHCR image workflow.

Everything until now ran on a laptop. This chapter puts it on the public internet, on a €6/month box
with 2 vCPUs and no swap — and the constraints of that box drive every decision in it.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [The platform decision](1-the-platform-decision.md) | Why the deployed thing must be the same system, sizing from Phase 10's own measurements, k3s over managed Kubernetes, always-on billing reality, and manual deploys with automated builds |
| 2 | [The production overlay](2-the-production-overlay.md) | Kustomize over a base that never changes, NodePort → ClusterIP as a security requirement, the Ingress allowlist, same-origin routing that removes CORS entirely, and why images need a registry |
| 3 | [Tuning for a small box](3-tuning-for-a-small-box.md) | The four blocking changes — TCP broker probe, explicit heap caps, a startup probe, widened scenario timeouts — each with its stated cost |
| 4 | [The outage](4-the-outage.md) | One routine command, a control plane that starved with its workload, a fix that caused a second outage, and three changes at three layers |

---

## The requirement that shaped everything

> The deployed thing must be the *same* system, not a reduced one. The Deployments, the probes, the
> resource requests and limits, a rolling update, a consumer group rebalancing after a pod is killed —
> **those are the demonstration.** A platform that cannot run them is not cheaper, it is a different
> product.

That single sentence eliminates every cheap PaaS option, and it is the right answer to "why not just
deploy it somewhere simple?"

---

## Four ideas worth carrying out

**Measurements you already have beat estimates.** Sizing did not need a guess: Phase 10 had run this
exact 8-pod stack inside 3.825 GiB — *less memory than the box being considered.* It also showed the
real risk was CPU rather than memory, and cold start rather than steady state.

**A security boundary is only a boundary if there is no way around it.** An Ingress allowlist with live
NodePorts beside it is decoration, which is why turning six Services into `ClusterIP` is *"a patch and
not a suggestion."*

**"Not deployed" beats "authenticated."** The endpoints that can wedge the demo are simply not routed.
Scenario Service reaches them over cluster-internal DNS. No credentials, no auth code, no sessions —
and this works because [ADR-002](../01-design-contract/3-state-and-api-contracts.md) split `/api` from
`/demo` in Phase 0 for entirely different reasons.

**On a single-node cluster, the control plane is a workload.** Starve the node and Kubernetes cannot
observe that it needs to recover — which is how a memory spike became an outage that required a reboot
rather than one that self-resolved.

---

## Build it yourself

This chapter is the least reproducible — it needs a VPS, a domain, and a few euros a month. The
repo-side work is all doable without one.

**Repo side** — [sections 2](2-the-production-overlay.md) and [3](3-tuning-for-a-small-box.md)

1. Remove any committed Secret. Write a `create-postgres-secret.sh` that generates it with
   `kubectl create secret`, outside version control.
2. `production/common/kustomization.yaml`: enumerate the base resources, **omit the secret**, and patch
   every Service from `NodePort` to `ClusterIP` with **JSON 6902** so `nodePort` is explicitly removed.
3. `production/common/ingress.yaml`: a Traefik `Middleware` doing StripPrefix, and an `Ingress` that
   **enumerates every routed path** — backend `/svc/{service}/…` prefixes and frontend routes.
   Deliberately omit `/demo/consumers/*/pause`, `/demo/payment-behavior`, `/actuator/metrics`, and
   `/actuator/prometheus`. Set no `host:`.
4. `production/common/patch-tuning.yaml`: `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=60`, a
   `startupProbe` allowing ~5 minutes, relaxed liveness/readiness timings, and
   `strategy.rollingUpdate.maxSurge: 0` on all five backends.
5. Replace Kafka's `exec` readiness probe with a `tcpSocket` check — **in the base manifests**, since it
   is a strict improvement everywhere.
6. Widen scenario timeouts in `application-production.yml`, and enable idle auto-reset there.
7. `production/ghcr/` and `production/local-verify/`, both over `../common`, differing only in the
   `images` transformer.
8. A `workflow_dispatch`-only GitHub Actions workflow building all six images for `linux/amd64` and
   pushing to GHCR with both `latest` and an immutable commit-SHA tag. Build the frontend with the
   `/svc/{service}` build args.
9. `redeploy.sh` — `set -euo pipefail`, restarting the five backends **one at a time**, waiting for
   `kubectl rollout status` before continuing.
10. Verify the whole overlay locally: `kubectl kustomize production/common` to inspect, then
    `kubectl apply -k production/local-verify` against kind.

**Box side** — [section 1](1-the-platform-decision.md)

11. A small always-on VPS. Install k3s (which brings Traefik and metrics-server).
12. **Firewall the NodePort range 30000–32767**, so nothing can bypass the Ingress even by accident.
13. DNS and TLS at your provider; a `host:` and `tls:` block on the Ingress once the name exists.
14. `create-postgres-secret.sh`, then `kubectl apply -k production/ghcr`.
15. Confirm Traefik's CRD API group before applying — v3 uses `traefik.io`, v2 `traefik.containo.us`.

**Done when:** a stranger with the URL can run all eight scenarios; every unlisted path 404s at the
edge; no NodePort is reachable; a full five-service redeploy via `redeploy.sh` completes without the
box exceeding its steady-state memory; and an abandoned session resets itself within fifteen minutes.

---

## Next

[Section 1 — The platform decision](1-the-platform-decision.md).


# 9.1 — The platform decision

[← Chapter 9](README.md) · [Next: The production overlay →](2-the-production-overlay.md)

ADR-010. The project needs a URL a stranger can click, and this is how that decision was made.

---

## What changed

[ADR-007](../01-design-contract/4-sequencing-and-deferrals.md) put Kubernetes in the project and kept
it deliberately optional — local `kind`, plain YAML, never a prerequisite. **That decision is
unchanged.** What changed is that the project now needs

> a **URL a stranger can click** — a recruiter opening a link, running a failure scenario, and
> watching real events cross five services — which local `kind` cannot provide.

## The requirements, and the one that constrains everything

> - The deployed thing must be the *same* system, not a reduced one. The Deployments, the readiness
>   and liveness probes, the resource requests and limits, a rolling update, a consumer group
>   rebalancing after a pod is killed — **those are the demonstration.** A platform that cannot run
>   them is not cheaper, it is a different product.
> - It must be up when someone clicks. Not a cold start, not a reclaimed instance.
> - Roughly €10/month, all in, for something that will sit idle between job searches.
> - It must not be the same machine as Sprint 2's dev VPS. That box exists to be crashed — chaos
>   testing, crash loops, multi-replica load tests. **The demo box exists to be boring.**

The first requirement eliminates the whole category of cheap options. A PaaS running five containers
with no Kubernetes objects, no probes, no rolling update, and no rebalance would host the
*application* and not the *demonstration* — and the demonstration is the product.

*"Not cheaper, a different product"* is the sentence to remember. Cost comparisons between platforms
are only meaningful when both deliver the thing you need.

The last requirement is a nice piece of operational judgment: **two boxes with opposite jobs.** One is
for breaking things; one must never break. Sharing them would mean every load test risks the public
demo.

---

## Sizing, from the project's own measurements

This is the best part of the ADR, because the answer already existed.

> The instinct was a 4 vCPU / 8 GB box. Only the 2 vCPU / 4 GB CX23 was orderable — Hetzner's
> cost-optimized lines were capacity-constrained, and the lines that stayed in stock had been
> re-priced 2.2–2.7× in June 2026, putting them 3–8× over budget.

A constraint arrived from outside. The response was not to guess whether a smaller box would work:

> This did not need to be estimated, because the **Phase 10 scaling demo had already measured this
> exact stack under a harder cap than a CX23 imposes**:
>
> - The development machine's Docker Desktop VM is limited to **3.825 GiB — less memory than a CX23
>   has** — and inside that limit the full 8-pod stack at 1 replica each stood up and ran. That is
>   precisely the configuration the public demo runs.
> - Instability appeared only at 9 pods, when Inventory Service was scaled to 3 replicas. **The demo
>   box does not do that; the dev box does.**

[Chapter 8](../08-observability-and-scaling/3-scaling.md) noted that Phase 10's most valuable output
was a measurement rather than a graph. This is where it pays: the sizing question is answered with
*"we have already run this exact stack under a tighter memory cap"* rather than an estimate.

### And the risk is not where the instinct says

> So memory is not the risk. **CPU is.** The same report recorded CPU spiking to 270–406% under
> contention on a machine with 8 vCPUs available. A CX23 has 2. Five JVMs and a Kafka broker starting
> simultaneously will want more cores than exist, which makes **cold start — not steady state — the
> pressure point.**

Two reframings, both correct and both non-obvious:

**CPU, not memory.** The instinct for "is this box big enough" is memory, and the measurements say
otherwise.

**Cold start, not steady state.** Five JVMs at rest fit comfortably. Five JVMs *starting at once* want
far more CPU than exist. The dangerous moment is a deploy or a reboot — which is exactly the moment
that later produced [section 4](4-the-outage.md)'s outage.

### The probe that causes what it detects

> Phase 10 also recorded the specific failure that pressure produces: Kafka's readiness probe, an
> `exec` of `kafka-broker-api-versions.sh` that **starts a second JVM inside the broker container on
> every single check**, began timing out under CPU contention and flapped the broker Ready/NotReady,
> taking the Kafka Service's endpoints to zero. **A probe whose own cost causes the failure it is
> meant to detect is not a probe.**

The check from [Chapter 7](../07-containers-and-kubernetes/1-containers-and-compose.md) —
deliberately chosen because it proves the broker actually answers, rather than merely that the process
is up. Correct, and its *cost* is a JVM start every five seconds.

Under CPU contention it times out, marks the broker not-ready, empties the Service endpoints, and
every client loses the broker — because the health check could not get a CPU slice.

**A health check is a load.** On a machine with headroom that is invisible; on a constrained one, the
observer changes the outcome.

---

## The decision, in five parts

> **One Hetzner CX23 (2 vCPU / 4 GB / 40 GB, ~€5.99/month) running k3s, always on, reached through a
> subdomain with DNS and TLS on Cloudflare. The existing manifests apply to it, through a production
> overlay. Deploys are manual `kubectl apply`.**

**1. k3s, not managed Kubernetes and not a PaaS.**

> Everything a reader can observe about a Kubernetes deployment is byte-identical on k3s; the part a
> managed control plane buys — someone else running etcd and the API server — is **the one part nobody
> can see.**

k3s is a certified Kubernetes distribution in a single binary. The same objects, the same API, the
same `kubectl`. It also ships **Traefik**, which the routing in
[section 2](2-the-production-overlay.md) depends on.

**2. A dedicated box, separate from the dev VPS.** The dev box is a CPX32 created per session and
deleted after — *"Hetzner bills hourly up to a monthly cap, so a box that does not exist costs
nothing."* The demo box is always on.

**3. Always on rather than spin-up-on-demand**, for a reason that inverts the intuition:

> Hetzner bills a server until it is *deleted*, not until it is powered off, so "on demand" would buy
> a multi-minute cold boot for a visitor and **save nothing**.

The cost lever that actually works is snapshot-and-delete between job searches (~€0.30–1.15/month) —
*"a deliberate act, not a request-time behavior."*

**4. Manual `kubectl apply`.** No deployment pipeline. Images *are* built in GitHub Actions and
published to GHCR, and the ADR is careful that this is not a contradiction:

> the demo box is x86_64 and the development laptop is arm64, so cross-building is required either
> way, and a native x86 runner beats QEMU emulation by a wide margin. **That is image building, not
> deployment automation**, and it does not reopen the deferred CI/CD decision.

The workflow is `workflow_dispatch` only, and the reasoning holds the line:

> a build that fires on every push would produce images nobody asked for and would make the mutable
> `latest` tag move under a box that is meant to be **boringly stable**. Publishing is an explicit
> act here, matching how the deploy itself works.

Every run also pushes an immutable commit-SHA tag, *"which is what you want to pin once the first
deploy is done."* Mutable `latest` for convenience, immutable SHA for reproducibility.

**5. A production overlay rather than edited base manifests** — [section 2](2-the-production-overlay.md).

---

## The rejected options

Worth having ready, because "why not just use a PaaS?" is the obvious question.

**Managed Kubernetes (EKS/GKE).** Rejected on cost, and on the observation above: the part it buys is
the part nobody can see.

**A PaaS** (Render, Fly, Railway). Cheaper and simpler, and it cannot run the demonstration — no probe
semantics to show, no rolling update to watch, no rebalance when a pod dies.

**Serverless / scale-to-zero.** Fails the "up when someone clicks" requirement, and a Kafka consumer
that scales to zero is not a Kafka consumer.

**Docker Compose on a VPS.** Would run the application on this exact box. Rejected because Kubernetes
is an explicit portfolio goal and half the demonstration is Kubernetes behavior — the same reasoning
[ADR-007](../01-design-contract/4-sequencing-and-deferrals.md) used to reject Compose-only in the first
place.

---

[← Chapter 9](README.md) · [Next: The production overlay →](2-the-production-overlay.md)


# 9.2 — The production overlay

[← The platform decision](1-the-platform-decision.md) · [Next: Tuning for a small box →](3-tuning-for-a-small-box.md)

`infrastructure/kubernetes/production/` — what changes between a local `kind` cluster and a box on the
public internet, expressed as patches rather than a second copy.

---

## Why an overlay

The requirement is unusually strict:

> `kubectl apply -f infrastructure/kubernetes/` against local `kind` behaves **exactly as it did
> before this ADR**.

The base manifests do not change. Everything production needs arrives as an overlay, so the local
development flow of [Chapter 7](../07-containers-and-kubernetes/README.md) is untouched — no new
required arguments, no environment variable that has to be set, no way to accidentally apply
production settings locally.

> **Primer — [Kustomize](../technology/kubernetes/kustomize.md)**
> Bases and overlays, strategic-merge vs. JSON 6902 patches and when each is right, built-in
> transformers, composing overlays, `kubectl kustomize` before `kubectl apply -k`, and Kustomize vs.
> Helm.

Note this is where [Chapter 7](../07-containers-and-kubernetes/2-kubernetes-manifests.md)'s rejection
of Helm gets revisited — and the answer is still not Helm. A genuine second environment appeared, and
the response was **patches over plain YAML**, keeping the base manifests readable and applyable on
their own.

## The structure

```
production/
├── common/
│   ├── kustomization.yaml     what to include, what to exclude, what to patch
│   ├── ingress.yaml           production-only: Traefik Ingress + StripPrefix
│   └── patch-tuning.yaml      CX23 tuning (section 3)
├── ghcr/                      common + real GHCR image references
├── local-verify/              common + local image tags
├── create-postgres-secret.sh
├── redeploy.sh                (section 4)
└── README.md
```

Two leaf overlays over one `common`, and the reason for the split is a good distinction:

> the registry path is **the one piece of the production configuration that is an environment fact
> rather than a design decision.**

`common` holds design decisions and stays renderable with no registry — so `kubectl kustomize
production/common` can be inspected and reviewed by anyone. `ghcr/` adds the environment fact.
`local-verify/` exists so the whole production configuration can be validated on a laptop before it
reaches the box.

---

## Five changes

The `kustomization.yaml` header enumerates them, which is itself worth copying — an overlay that lists
what it does and why is far easier to audit than one you have to diff.

### 1. The committed Secret is omitted

```yaml
resources:
  - ../../00-namespace.yaml
  # ../../01-secrets.yaml is deliberately absent — see note 1 above.
```

> The committed Postgres password is fine for a throwaway kind cluster and not for a public box;
> production generates that Secret **imperatively** first.

Exclusion by omission, with a comment marking it as deliberate — so a future reader does not "fix" the
missing file.

`create-postgres-secret.sh` creates it with `kubectl create secret` outside version control.

> **We got this wrong.** `01-secrets.yaml` contained a real committed password for most of the
> project's life, caught by Sprint 2's security pass. Base64 is an encoding, not encryption.
> [Chapter 10](../10-retrospective/README.md).

### 2. NodePort becomes ClusterIP

```yaml
- target:
    version: v1
    kind: Service
    name: order-service
  patch: |-
    - op: replace
      path: /spec/type
      value: ClusterIP
    - op: remove
      path: /spec/ports/0/nodePort
```

Six times, once per Service. And the reason it is a patch rather than a suggestion:

> A reachable NodePort would **bypass that allowlist completely**, which is why this is a patch and
> not a suggestion.

[Chapter 7](../07-containers-and-kubernetes/2-kubernetes-manifests.md) flagged this: `NodePort` opens
a port on **every node**. On an internet-facing box, anyone scanning 30000–32767 reaches the service
directly, and every routing rule you wrote is irrelevant.

**A security boundary is only a boundary if there is no way around it.** An ingress allowlist with live
NodePorts beside it is decoration.

Note the JSON 6902 style, and why:

> JSON 6902 rather than a strategic merge: **removing the `nodePort` field is an explicit operation
> here**, instead of relying on how a null merges into a keyed list.

Removing a field is exactly where strategic merge gets subtle. When the correctness of a security
boundary depends on a field being gone, use the patch format that says "remove."

### 3. The Ingress, and the allowlist

One hostname, everything behind it, `/svc/{service}/...` prefixes stripped before the request reaches
a pod:

> a StripPrefix middleware removes `/svc/{service}` before the request reaches the pod, so **every
> service keeps serving the exact paths it serves locally** and the Deployments' probe paths are
> untouched. The frontend production build is built with those same prefixes as its base URLs, which
> also makes every browser request **same-origin — so CORS is never consulted in production at all.**

Two payoffs collected at once. The services are unmodified — a path prefix is an edge concern.
And CORS, which cost real debugging in
[Chapter 8](../08-observability-and-scaling/2-metrics.md), simply **does not apply**, because
everything is same-origin. The
[CORS primer](../technology/http/cors.md)'s closing note — that a reverse proxy is often the better
answer — turns out to be this project's production answer.

The frontend side is the `ARG` mechanism from
[Chapter 7](../07-containers-and-kubernetes/1-containers-and-compose.md):
`--build-arg VITE_ORDER_SERVICE_URL=/svc/order`. A relative prefix works because `apiFetch` and both
`EventSource` URLs concatenate `${baseUrl}${path}` — a decision from
[Chapter 2](../02-domain/6-the-first-frontend.md) with no code change since.

#### The allowlist is the security model

> **THIS IS AN ALLOWLIST, AND THAT IS THE POINT.** Only the paths listed below are routed; anything
> else has no matching router and Traefik answers 404. The endpoints that can wedge the demo
> indefinitely with no auto-recovery —
>
> ```
> POST /demo/consumers/{name}/pause   (inventory-service, fulfillment-service)
> PUT  /demo/payment-behavior         (payment-service)
> ```
>
> — are **never called by the browser.** Scenario Service invokes them server-side over
> cluster-internal DNS, which keeps working because that traffic never touches this Ingress. So they
> are simply not routed, and the wedge risk is removed **structurally rather than by
> authentication.**

This is where [ADR-002](../01-design-contract/3-state-and-api-contracts.md) pays off in a way nobody
planned.

The `/api`–`/demo` split was made in Phase 0 for *cleanliness* — to keep the business API honest. Five
chapters later it turns out to be the **security boundary** that makes a public demo possible: a
visitor can run every scenario, and cannot pause a consumer or arm a payment rejection, because
Scenario Service calls those over cluster-internal DNS and the Ingress simply does not route them.

**"Not deployed" beats "authenticated."** No credentials to manage, no auth code to get wrong, no
session handling — the endpoint is unreachable from outside the cluster, full stop.

`/actuator/metrics` and `/actuator/prometheus` are excluded on the same reasoning: nothing in the
browser needs them.

#### The cost of no catch-all

> Adding a frontend route means adding it to the frontend Ingress below. That is the cost of not
> having a catch-all: a `/` prefix rule would match everything, including
> `/svc/inventory/demo/consumers/...`, and would answer it with the SPA's `index.html` instead of a
> 404. **Nothing would leak** — that request would still never reach a backend — but **"not listed
> means 404" is a far easier property to check than "not listed means you get some HTML"**, so the
> frontend is enumerated too.

A stricter invariant chosen because it is *checkable*, not because the looser one is unsafe. Verifying
"every unlisted path 404s" is a one-line test; verifying "every unlisted path returns HTML that does
nothing dangerous" requires reasoning about the SPA.

Consequences, stated: the SPA's own catch-all route is unreachable for unrouted paths — they 404 at
the edge — and every new frontend route needs an Ingress entry.

And one more deliberate choice:

> No `host:` is set, so these rules apply to every hostname pointed at the box. That is deliberate —
> it means **the allowlist cannot be sidestepped by hitting the node's IP directly.**

Host-based rules would leave a hole: request the raw IP, match no rule, and fall through to whatever
Traefik's default is. Applying to every hostname closes it.

Even the version check is written down rather than assumed:

> k3s ships Traefik, and Traefik v3 serves these CRDs under `traefik.io`. **Confirm on the box before
> applying**, since a v2 install would need `traefik.containo.us`.

### 4. The CX23 tuning

[Section 3](3-tuning-for-a-small-box.md).

### 5. The production Spring profile

`SPRING_PROFILES_ACTIVE=production` on Scenario Service turns on the **idle auto-reset** (15 minutes)
and widens scenario timeouts for slower hardware.

Idle reset is what makes an unattended public demo work: a visitor who pauses a consumer and closes
the tab leaves the system wedged, and fifteen minutes later it resets itself. The
[demo-state-is-real-state](../05-scenarios-and-frontend/3-the-eight-scenarios.md) cost from ADR-002,
paid automatically.

---

## Images, and the arm64 problem

```yaml
images:
  - name: order-service
    newName: ghcr.io/noelwschneider/kafka-portfolio-project/order-service
    newTag: latest
```

The base manifests keep `<service>:local`, so `common` renders with no registry. `ghcr/` maps them.

The reason a registry exists at all is hardware:

> the demo box is x86_64 and the development laptop is arm64, so **images built locally will not run
> there.** GitHub's hosted runners are native x86, free for public repositories, and build without
> QEMU emulation.

An Apple Silicon laptop cannot build a runnable x86 image without emulation, and emulating a
five-module Maven build is *"the difference between minutes and a large multiple of that."*

Once images are in a registry, the deploy gets simpler than local development:

> k3s pulls these itself via containerd — **no `docker` on the box, no `kind load`**, no
> `k3s ctr images import`. Public GHCR packages need no `imagePullSecret`.

No `kind load` step ([Chapter 7](../07-containers-and-kubernetes/3-probes-and-resources.md)) because
there is a real registry.

And a documented manual fallback with the exact `docker buildx --platform linux/amd64 --push`
commands, including the frontend's five build args — *"if this workflow is unavailable or not yet
trusted."* Writing down the manual path for an automated step is what keeps the automation from
becoming the only way anyone knows how to do it.

---

[← The platform decision](1-the-platform-decision.md) · [Next: Tuning for a small box →](3-tuning-for-a-small-box.md)


# 9.3 — Tuning for a small box

[← The production overlay](2-the-production-overlay.md) · [Next: The outage →](4-the-outage.md)

Four changes, all blocking rather than optional, all traceable to the same measurement: **on 2 vCPUs,
cold start is the pressure point.**

---

## Why these were blocking

Sprint 2's plan is unusually firm about it:

> All four are **blocking, not optional** — undiagnosed, they'd reproduce the same probe-flapping
> problem Phase 10 found, this time on the public demo.

Phase 10 already produced the failure these prevent, on a laptop, where it cost an afternoon. The
tuning exists so it does not happen again in front of a visitor.

Note also the discipline about *where* each change lives — the base manifests, or the overlay:

> Everything here is specific to the 2-vCPU / 4 GB demo box and deliberately does **not** go into the
> base manifests: on an 8-core development machine these numbers would only make failures **slower to
> surface.**

Which is the right test for an environment-specific setting. A generous timeout on a fast machine does
not help; it delays the moment you learn something is wrong.

---

## T1 — the Kafka probe

The one change that went into the **base** manifests:

> The one tuning change that IS a strict improvement everywhere — Kafka's readiness probe no longer
> spawning a JVM per check — lives in `../03-kafka.yaml` instead.

From [section 1](1-the-platform-decision.md): `kafka-broker-api-versions.sh` starts a JVM inside the
broker container **on every check**. Under CPU contention it times out, flaps the broker
Ready/NotReady, and empties the Service endpoints — *"a probe whose own cost causes the failure it is
meant to detect is not a probe."*

The replacement is a `tcpSocket` check, and the ADR is honest about what that gives up:

> The honest cost is that a TCP accept proves the listener is **bound**, not that the broker will
> answer a metadata request — a weaker signal, bought by removing the probe's own CPU cost.

**A weaker signal that is always available beats a stronger signal that fails under the exact
conditions you need it.** Not a free win, and the right trade — which is why it belongs in the base
manifests rather than the overlay.

> **Primer — [Kubernetes: health probes](../technology/kubernetes/probes.md)**
> Probe types and their costs, why an `exec` probe is the expensive one, and how probes amplify
> load-induced failure.

---

## T2 — explicit heap caps

```
JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=60"
```

> Left to itself the JVM takes **25% of the container limit** (160 MiB of the 640 MiB limit here) and
> Kafka takes a **flat 1 GB regardless of its limit.** Neither number is chosen with any knowledge of
> this workload. `MaxRAMPercentage=60` gives each service ~384 MiB of heap and leaves ~256 MiB for
> metaspace, code cache, thread stacks and direct buffers — **which is the part that gets a container
> OOMKilled when people set the heap to the limit.**

Three separate facts, each worth knowing independently.

**The JVM's container-aware default is 25%**, which on a 640 MiB limit is 160 MiB of heap and 480 MiB
unused. Not dangerous — wasteful, on a box where every megabyte is allocated.

**Kafka's start script ignores the container limit entirely** and asks for a flat 1 GB. On a 4 GB box
with five other JVMs, that is a quarter of the machine claimed by a default.

**Heap is not the whole JVM.** Metaspace, code cache, thread stacks, and direct byte buffers sit
*outside* it. Setting the heap to the container limit guarantees an OOM kill — and the kill is
delivered by the kernel to the container, so it looks like a crash rather than a memory error.
[Chapter 7](../07-containers-and-kubernetes/3-probes-and-resources.md) flagged this as "not yet"; here
it is, at 60%.

`MaxRAMPercentage` rather than a fixed `-Xmx` is the right form: it stays correct if the limit
changes, and one value works for every service.

---

## T3 — a startup probe

```yaml
startupProbe:
  # …allows up to 5 minutes
```

> **Startup is the pressure point, not steady state.** Five JVMs and a broker starting at once on 2
> vCPUs is exactly the contention Phase 10 measured at 270-406% CPU on an 8-core machine. A
> startupProbe is the right tool: while it is running, the liveness and readiness probes are **not
> evaluated at all**, so a slow cold start cannot be read as a failure and cannot restart a pod that
> is merely waiting for a CPU slice. It allows up to 5 minutes to come up, then hands over to the
> normal probes — which keep their base semantics but with more headroom.

The failure this prevents is the crash loop from
[Chapter 7](../07-containers-and-kubernetes/3-probes-and-resources.md), with a twist that makes it
worse: five pods starting together contend for CPU, all start slowly, all miss their liveness probes,
all get restarted — and the restarts start five more JVMs, deepening the contention. **A death
spiral triggered by the health checks.**

The startup probe breaks it by suspending the other two entirely until the application is up.

The alternative — a very long `initialDelaySeconds` on liveness — would work for startup and leave
liveness permanently slow to detect a genuinely wedged process. The startup probe decouples the two:
**a generous boot budget *and* a tight liveness check afterwards**, instead of trading one for the
other.

Note the phrasing: *"which keep their base semantics but with more headroom."* The readiness group
still includes `db`; the liveness check is still the same endpoint. Only the timings are relaxed.

---

## T4 — scenario timeouts

Scenario timeouts are Spring properties rather than probe settings, so they travel via
`SPRING_PROFILES_ACTIVE=production` and live in Scenario Service's `application-production.yml`.

The problem is real: scenario runners wait for observed outcomes
([Chapter 5](../05-scenarios-and-frontend/1-the-scenario-service.md)) with bounded polls. Those bounds
were tuned on 8-core hardware, and a scenario that times out on a slower box reports a **failure** for
work that was merely slow.

**Timeouts calibrated on fast hardware are a correctness problem on slow hardware.** Not a performance
problem — the scenario reports the wrong answer.

The same profile also enables the idle auto-reset from
[section 2](2-the-production-overlay.md). One environment variable, two production behaviors, both
about the box being unattended.

---

## T5 — `maxSurge: 0`

This one is [section 4](4-the-outage.md)'s subject, and it arrived *after* an outage rather than
before. It lives in the same file for a stated reason:

> This is a **Deployment strategy, not a resource number**, so it is unconditionally correct for the
> demo box regardless of future capacity changes — it does not belong in the same conditional as the
> heap caps and probe timing above, but lives alongside them here per this directory's existing
> convention of one file for CX23-only Deployment patches.

Noting that a change does not really belong with its neighbours, and putting it there anyway for
consistency, with the reasoning recorded. That is the honest way to make a filing decision you are not
fully happy with.

---

## The shape of all of this

Every one of T1–T4 follows the same pattern:

1. **A measurement from Phase 10** — CPU at 270–406%, probe flapping, the 3.825 GiB stack.
2. **A specific mechanism** it predicts will fail on 2 vCPUs.
3. **A change with a stated cost**, not a free win.

| | Prevents | Costs |
|---|---|---|
| T1 TCP probe | Probe-induced broker flapping | A weaker readiness signal |
| T2 heap caps | OOM kills; wasted memory | A number to revisit if limits change |
| T3 startup probe | Cold-start crash spiral | Up to 5 minutes before liveness applies |
| T4 timeouts | False scenario failures | Slower failure detection in scenarios |

**Naming what each change costs** is what separates tuning from cargo-culting. A change with no stated
cost is usually one whose cost has not been found yet.

---

[← The production overlay](2-the-production-overlay.md) · [Next: The outage →](4-the-outage.md)


# 9.4 — The outage

[← Tuning for a small box](3-tuning-for-a-small-box.md) · [Chapter 9 ↑](README.md)

ADR-011. One routine command took the public demo down, the fix took it down again a different way,
and the whole thing is the most instructive incident in the project.

---

## The command

```bash
kubectl rollout restart deployment -n orderfulfillment
```

Restart everything in the namespace. Utterly routine. It **took the public demo box fully down**, and
recovery needed two reboots and manual intervention.

---

## Why it did that

> Kubernetes' default rolling-update strategy for a Deployment is `maxSurge: 25%, maxUnavailable: 25%`,
> which **for a single-replica Deployment means: start the new pod, and keep the old one running until
> the new one is Ready.**

That default is excellent — zero-downtime updates. And at one replica it means **two pods**, briefly.

> Restarting several single-replica Deployments in the same command starts all of their new pods while
> all of their old pods are still up, so **for a window the box has to hold double the fleet in
> memory.**

Four services × double = eight JVMs on a 4 GB box with no swap.

And Sprint 2 had just made each one heavier:

> Sprint 2 added a real per-service memory cost on top of that window — an **outbox-dispatcher thread
> and a retention-scheduler thread per service** — and the box had no headroom for it.

[Chapter 6](../06-outbox/README.md)'s rollout and
[Chapter 4](../04-reliability/README.md)'s retention schedulers each added a thread per service. Small
individually. Multiplied by four services and then by two during a rollout, not small.

## Why it did not recover

This is the part that turns a spike into an outage:

> Memory hit 100% with no swap to absorb the spike, the box began thrashing, and **the k3s API server
> itself became unresponsive.** That last part is what turned a resource spike into an outage that did
> not self-resolve: **the API server was too overloaded to observe that the new pods had failed
> readiness, so it never scaled the old ReplicaSets back down either.** Recovery took two box reboots
> and manually scaling the stale ReplicaSets to 0.

**Kubernetes' self-healing runs on the control plane, and on a single-node cluster the control plane
is on the same machine as the workload.** Starve the node and you starve the thing whose job is to
notice.

The system that would have rolled back could not observe that a rollback was needed, so both
ReplicaSets stayed up, which kept memory at 100%, which kept the API server unresponsive. A stable
failure state that required an outside force — a reboot — to break.

**Self-healing is not self-healing when the healer shares the resource.** That is the transferable
insight, and it applies to any single-node cluster, control-plane-on-workload-node setup, or monitoring
agent running inside the thing it monitors.

---

## The fix, and the second outage

`maxSurge: 0`, verified live, held the fleet to its steady-state footprint through a full five-service
redeploy.

> But **the very next redeploy against the fixed process took the box down again**, by a different
> route, needing one more reboot to clear: the inventory-service HPA reacted to the CPU spike that five
> JVMs cold-starting at once naturally produce — class loading and Spring context init, not real
> request load — and **added two more inventory-service replicas during the exact window the box had
> the least spare memory.**

[Chapter 8](../08-observability-and-scaling/4-the-autoscaler.md) tells this from the autoscaler's side.
Here is the sentence that matters:

> `maxSurge: 0` prevents a service from ever running two versions of itself; **it does nothing to stop
> the HPA from independently deciding the moment right after a deploy is when inventory-service needs
> *more* replicas.**

Two independent controllers, each correct, with no shared understanding of what was happening. The
Deployment controller carefully avoided doubling memory. The HPA then added replicas anyway — because
CPU was genuinely high, and it had no way to know why.

**Fixing one contributor to a multi-factor failure leaves you exposed to the others**, and the fix can
even *expose* them: a working deploy is exactly what let the HPA see the cold-start spike it would
previously have been drowned out by.

---

## Three changes, at three layers

> Two changes, both scoped to the production overlay only — local `kind` development has no memory
> constraint remotely like this and should not pay for it with slower or interrupted restarts.

**1. `maxSurge: 0, maxUnavailable: 1` on all five backend Deployments.**

> This tears the old pod down before the new one starts, so a single service's rolling update **never
> needs more than that service's own steady-state memory** — it cannot recreate the double-fleet
> condition on its own.

Scope decisions worth noting. **Scenario Service is included** though it was not in the incident:
*"it has the same per-service memory profile and the same risk on any future deploy."* Fix the class,
not the instance.

**Frontend, Kafka and Postgres are excluded**, each for a stated reason: the frontend is lightweight
stateless nginx, and the two stateful services are single-replica with `ReadWriteOnce` volumes where
*"a surge pod likely could not even schedule (only one pod can hold an RWO volume at a time); changing
a stateful service's rollout strategy is out of scope here."*

The cost, stated: *"a few seconds of per-service unavailability while the new pod starts, which is
acceptable for this demo."* Zero-downtime deploys traded away deliberately, because on this box the
surge is what causes downtime.

**2. `redeploy.sh` — one service at a time.**

> `maxSurge: 0` alone stops any *one* Deployment from surging, but does nothing to stop five separate
> `rollout restart` commands, issued together, from tearing down and starting up five services at once
> — still a real memory and CPU spike, just a smaller one than before. Doing it one Deployment at a
> time, and confirming health before continuing, keeps the peak footprint to **"steady state plus one
> service restarting"** and turns a stuck rollout into an **immediate, loud failure** instead of one
> that compounds by starting the next restart on top of it.

Two properties, and the second is the more valuable.

**Bounded peak** — steady state plus one service.

**Failing loudly and early.** The script waits for `kubectl rollout status` before continuing, so a
service that does not come up **stops the deploy**. In the incident, each failure made the next one
more likely; sequencing turns a compounding cascade into a single clean stop.

`set -euo pipefail` at the top of the script is the same instinct in bash: fail on error, fail on
undefined variable, fail on a broken pipe.

**3. A 60s scale-up stabilization window on the HPA.**

Covered in [Chapter 8](../08-observability-and-scaling/4-the-autoscaler.md). Note the restraint:

> The HPA's CPU threshold (65% of the 150m request) is unchanged — **it's already validated against
> real Scenario 8 load and wasn't the problem.** The problem was reacting to a CPU reading *instantly*.

---

## What this incident teaches

**Defaults are tuned for the common case, and a single-replica Deployment is not it.**
`maxSurge: 25%` assumes replicas to spare. At one replica it means 100% surge, which is the opposite of
what the percentage suggests.

**Memory spikes on a swapless box are cliffs, not slopes.** No swap means no degraded mode: fine, fine,
fine, thrashing.

**On a single-node cluster, the control plane is a workload.** Starve the node and you lose the
mechanism that would have recovered it.

**Independent controllers do not coordinate.** The Deployment controller and the HPA both did their
jobs. Nothing was looking at the whole picture — and nothing will, unless you are.

**Fix the class, not the instance.** Scenario Service was not involved and was included anyway.

**Don't change what was already validated.** The 65% threshold survived because it was not the problem,
and changing it would have invalidated the one number known to be right.

---

## What the demo box actually is now

- **One Hetzner CX23**, 2 vCPU / 4 GB, always on, ~€6/month.
- **k3s**, with the base manifests plus a production overlay.
- **One hostname**, TLS at Cloudflare, everything behind a Traefik Ingress **allowlist**.
- **No NodePorts.** The consumer-pause and payment-override endpoints are unroutable from outside.
- **Heap caps, a startup probe, a TCP broker probe**, and widened scenario timeouts.
- **Sequential deploys** via `redeploy.sh`, `maxSurge: 0`, and an HPA that waits 60 seconds before
  believing a CPU spike.
- **Idle auto-reset** after 15 minutes, so an abandoned session cleans itself up.

A recruiter can open a link, run all eight scenarios, watch real events cross five services, and cannot
wedge it — and if they somehow do, it fixes itself in a quarter of an hour.

---

[← Tuning for a small box](3-tuning-for-a-small-box.md) · [Chapter 9 ↑](README.md) · [Chapter 10 — Retrospective →](../10-retrospective/README.md)


<hr style="page-break-after: always;"/>

# Chapter 10 — Retrospective: what we got wrong

**Build history:** everything after the fact. ADR-009 (post-Phase 10), Sprint 2's security pass, its
correctness cleanup, its bug hunt, ADR-011, and four documentation-drift findings made while writing
this guide.

Every other chapter builds the **corrected** version of this system. This one is where the mistakes
actually live.

That separation is deliberate: a build-along that teaches you to write a bug on purpose so it can be
fixed three chapters later wastes your time. But the mistakes are the most valuable thing in the
project, so they get their own chapter — organized by **how each one was found**, because the detector
turns out to predict the category.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [Found by load](1-found-by-load.md) | The cross-topic status race, a retry budget chosen by feel, interleaved SSE writes, cleanup that failed an unrelated request, and a duplicate-SKU oversell |
| 2 | [Found by looking](2-found-by-looking.md) | Consumers that logged nothing on the happy path, a 500 that logged nothing at all, 404s reported as 500s, a committed password, an unimplemented transition, an ADR that corrected its own reasoning, and four stale doc claims |
| 3 | [Found in production](3-found-in-production.md) | A rollout that took the box down, a fix that took it down again, a health check that caused what it detected, and a reset that silently failed |
| 4 | [What this adds up to](4-what-this-adds-up-to.md) | Six patterns across all of it, what worked, and five things to do differently |

---

## Every entry, in one table

| # | What | Where it's built correctly | Found by |
|---|---|---|---|
| 1 | Order status could move backwards and out of terminal states (ADR-009) | [4.4](../04-reliability/4-out-of-order-transitions.md) | Load, Phase 10 |
| 2 | Optimistic-lock retry budget of 3, no backoff — stranded orders | [4.3](../04-reliability/3-inventory-contention.md) | Load, Phase 3 |
| 3 | `SseEmitter#send` unsynchronized across four writer threads | [5.2](../05-scenarios-and-frontend/2-server-sent-events.md) | Load, Sprint 2 |
| 4 | Cleanup throwing a second time, failing an unrelated `POST` | [5.2](../05-scenarios-and-frontend/2-server-sent-events.md) | Load, Sprint 2 |
| 5 | Duplicate SKUs on one order oversold and leaked stock | [2.4](../02-domain/4-the-four-domains.md) | Review during build |
| 6 | Consumers logged nothing on the happy path | [8.1](../08-observability-and-scaling/1-structured-logging.md) | Audit, Phase 9 |
| 7 | The catch-all handler discarded its exception | [2.3](../02-domain/3-the-http-layer.md) | Audit, Phase 9 |
| 8 | Unmapped routes and wrong methods returned 500 | [2.3](../02-domain/3-the-http-layer.md) | Bug hunt, Sprint 2 |
| 9 | A real PostgreSQL password committed in a Secret manifest | [9.2](../09-production/2-the-production-overlay.md) | Security pass, Sprint 2 |
| 10 | `FAILED` transition defined in Phase 0, unimplemented until Sprint 2 | [4.2](../04-reliability/2-retry-and-dlq.md) | Known and deferred |
| 11 | `processed_events` grew without bound | [4.1](../04-reliability/1-idempotent-consumers.md) | Known and deferred |
| 12 | ADR-006 scoped the outbox on reasoning ADR-005 invalidated | [6.1](../06-outbox/1-the-dual-write-problem.md) | Implementation, Phase 6 |
| 13 | `rollout restart` doubled the fleet and starved the control plane | [9.4](../09-production/4-the-outage.md) | Production |
| 14 | The HPA read cold-start CPU as demand, causing a second outage | [8.4](../08-observability-and-scaling/4-the-autoscaler.md) | Production |
| 15 | Kafka's readiness probe started a JVM per check | [9.3](../09-production/3-tuning-for-a-small-box.md) | Load, Phase 10 (near-miss) |
| 16 | `POST /demo/reset` silently 409'd once reservations accumulated | [5.3](../05-scenarios-and-frontend/3-the-eight-scenarios.md) | Production |
| 17 | Four documentation claims no longer true | [10.2](2-found-by-looking.md) | Writing this guide |

---

## The shortest version

**Load finds concurrency. Audits find absences. Production finds resource limits.** Each detector is
blind to the others' categories, and this project ran all three.

**Check-then-act appears three times** — the ledger, the reservation, the status transition — with
three different correct answers, chosen by access pattern.

**The worst bugs live between two correct decisions**, and no document describes an interaction.

**Every bound that holds was derived from something measured.** The one chosen by feel is the one that
failed.

---

## Next

[Section 1 — Found by load](1-found-by-load.md).


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


# 10.2 — Found by looking

[← Found by load](1-found-by-load.md) · [Next: Found in production →](3-found-in-production.md)

Bugs that were sitting in plain sight and were only found when somebody deliberately went looking.
These are the cheapest to fix and the most embarrassing to have shipped.

---

## The logging that logged nothing

**Where:** all five services. **Found:** Phase 9, by auditing call sites. **Severity:** the
observability gate was unmeetable.

The correlation-ID plumbing was built in Phase 2 and worked perfectly. Structured logging was
configured in Phase 9 and rendered the field correctly. Everything was right.

> Auditing every `log.*` call in the codebase before this phase found only **32 call sites total**, and
> on the happy path of the domain Kafka consumers the only `INFO` log line in each was the
> **duplicate-delivery skip branch** — an edge case, never hit on a normal run. A live `standard-order`
> scenario run before this fix produced **zero log output identifying the workflow in 4 of the 5
> services.**

A tracing mechanism attached to nothing. The consumers logged only on a branch a successful run never
takes.

**The lesson:** a mechanism can be perfectly correct and completely useless because nothing invokes it.
The audit — count the call sites, run the real workflow, look at what came out — is what caught it, and
no amount of reviewing the logging *configuration* would have.

## The 500 that logged nothing

Same phase, worse:

> `GlobalExceptionHandler.handleUnexpected` caught every uncaught exception and returned a 500
> `ApiError` — **carrying the correlation id in the response body** — but never logged the exception
> anywhere. A real 500 during verification left **zero trace in any service's log**, in direct
> contradiction of this phase's whole purpose.

The client is handed a correlation ID to report, and searching for it finds nothing, because the one
line that would have carried it was never written.

Found by hitting it: *"it is what surfaced the actual bug hit during verification (a wrong URL in a
manual test), and without the fix that bug would have been undiagnosable from logs alone."*

**The lesson:** the catch-all handler is the one that matters most and gets reviewed least. It runs
exactly when you have no other information.

---

## 404s reported as 500s

**Where:** `common`. **Found:** Sprint 2 bug hunt. **Severity:** noise, and misleading status codes.

Two exceptions had no handler and fell through to the catch-all:

- `NoResourceFoundException` — no handler matches the path. Reported as **500** instead of 404.
- `HttpRequestMethodNotSupportedException` — wrong HTTP method. Reported as **500** instead of 405.

Both were **logged at `ERROR`** with a stack trace. So every scan, every typo, every stale link
produced a server-error log line — filling the logs you would use to find real failures with client
mistakes.

Found *"during deployment verification"* and *"by making the requests, not by reading the code."*

**The lesson:** exercise your error paths. A framework's default for an unhandled exception type is a
500, and the set of exception types a web framework can throw is larger than the set you thought about.

---

## The committed password

**Where:** `infrastructure/kubernetes/01-secrets.yaml`. **Found:** Sprint 2 security pass.
**Severity:** a credential in git history.

A real PostgreSQL password, base64-encoded in a Kubernetes `Secret` manifest, committed to a public
repository.

Base64 is an encoding. `echo <value> | base64 -d` is the entire attack.

The specific trap: a Kubernetes `Secret` **looks** like a secure object. It has its own kind, its own
RBAC, and its values are not printed in plain text. None of that applies to a YAML file in git — where
it is simply a credential with an extra step.

Fixed by omitting the file from the production overlay entirely and generating the Secret
imperatively with `create-postgres-secret.sh`.
[Chapter 9](../09-production/2-the-production-overlay.md).

**The lesson:** the fix is structural, not procedural. "Remember not to commit secrets" fails
eventually; a repository with no file that *could* contain one does not.

Worth noting the value of a scheduled pass. This was not found by someone noticing — it was found
because Sprint 2 allocated time to *look for exactly this class of thing*.

---

## The unimplemented transition

**Where:** Order Service. **Found:** it was never lost — Phase 0 wrote it down. **Severity:** orders
stuck in a lie.

`FAILED` was in the frozen state machine from Phase 0, with transition 9 defined as *"any non-terminal
→ FAILED"*. Nothing implemented it until Sprint 2.

So an order whose event was dead-lettered stayed at whatever status it last reached — `PAYMENT_PENDING`,
say — **displaying as in-progress forever** with nothing left to progress it.

The gap was *recorded*: ADR-009's accepted costs named it. It was documented and deferred, and then
deferred again.

Fixed by `OrderDeadLetterConsumer` listening on Order Service's own `orders.dlq` and calling
`markFailed`. [Chapter 4](../04-reliability/2-retry-and-dlq.md).

**The lesson:** a documented gap is better than an undocumented one and still a gap. Writing it down
buys you the ability to find it later; it does not fix it. Worth distinguishing "we know and accept
this" from "we know and intend to fix this," because the second decays into the first.

Retention for `processed_events` is the same story, milder: ADR-005 flagged unbounded growth as an
accepted cost in Phase 4, and Sprint 2 closed it.
[Chapter 4](../04-reliability/1-idempotent-consumers.md).

---

## The ADR that corrected itself

**Where:** ADR-006. **Found:** during Phase 6 implementation. **Severity:** three services carried a
dual-write window nobody thought they had.

Not a code bug — a **reasoning** bug, and the most interesting entry in this chapter.

ADR-006 scoped the outbox to Order Service, arguing the other three would self-heal:

> The other publishers lose an event that a redelivery can regenerate, because their publishes are
> themselves reactions to consumed events.

Plausible, and wrong — because ADR-005 requires the `processed_events` claim to commit **inside** the
business transaction. So a redelivery is short-circuited by the ledger before it can republish
anything: *"the event is not regenerated, it is silently skipped."*

**Two individually correct designs interacting to produce a failure neither has alone.** ADR-005 is
right. ADR-006's scoping was right about which service to do first and wrong about why the others could
wait.

It was caught during implementation, and the ADR **kept the wrong reasoning and appended a correction
block** rather than editing it away — twice, once at Phase 6 and once at Sprint 2.
[Chapter 6](../06-outbox/1-the-dual-write-problem.md).

**The lesson:** the dangerous bugs live in the *interaction* between two correct decisions, and no
document describes an interaction. You have to hold both mechanisms in your head and ask what happens
in the gap.

---

## Documentation drift, found while writing this guide

Four claims in the repo that no longer match the code. None is dangerous; together they make a point.

| Where | Claim | Reality |
|---|---|---|
| `docs/events/event-catalog.md` §2 | A `demo.events` topic published by Scenario Service | **No such topic exists.** Scenario Service writes timeline rows to its own table and pushes SSE; its `KafkaTemplate` only publishes duplicate/poison records onto existing domain topics |
| `ADR-004` decision section | *"`outbox_events` exists only in Order Service"* | Sprint 2 added it to all four. ADR-006 carries a correction; ADR-004 does not |
| `EventPublisher` Javadoc | *"Inventory, Payment and Fulfillment Service still publish this way"* | They do not, since Sprint 2 |
| `IdGenerator` Javadoc | *"see `docs/CHANGELOG-contracts.md` for why that mattered"* | That file has seven entries, **none** about the ID generator |

Three of the four are the **same rollout**: Sprint 2 moving the outbox into three more services updated
the code consistently and left the prose describing the previous state in three places.

**The lesson:** a change that touches four services touches every document that described the old
behavior — and those references live in Javadoc, ADRs, and frozen contracts that no test exercises.
Code drift fails a build; documentation drift fails a reader, silently, later.

The repo has the right instinct here — a coordination protocol requiring contract changes to be
proposed in the doc first, with a `CHANGELOG-contracts.md` note. It caught the `db-ownership.md` change
in that rollout. It did not catch three prose references in other files, because the protocol covers
the frozen contracts and Javadoc is not one of them.

---

[← Found by load](1-found-by-load.md) · [Next: Found in production →](3-found-in-production.md)


# 10.3 — Found in production

[← Found by looking](2-found-by-looking.md) · [Next: What this adds up to →](4-what-this-adds-up-to.md)

Three failures that only existed on a 2-vCPU box with no swap, and one that only existed because the
demo ran unattended. All of them are about **resource constraints being a different category of
problem** — the code was correct.

---

## The rollout that took the box down

**Where:** the demo box. **Found:** by doing it. **Severity:** total outage requiring two reboots.

```bash
kubectl rollout restart deployment -n orderfulfillment
```

The chain, from [Chapter 9](../09-production/4-the-outage.md):

1. Kubernetes' default `maxSurge: 25%` — which for a **single-replica** Deployment means start the new
   pod and keep the old one until it is Ready.
2. Four services restarted at once = **eight JVMs** on a 4 GB box with no swap.
3. Sprint 2 had just added an outbox-dispatcher thread and a retention-scheduler thread per service, so
   each was heavier than when the box was sized.
4. Memory hit 100%. No swap. The box thrashed.
5. **The k3s API server became unresponsive** — and on a single-node cluster the control plane is on
   the same machine as the workload.
6. So Kubernetes could not observe that the new pods had failed readiness, and never scaled the old
   ReplicaSets back down. A stable failure state.

Recovery: two `hcloud server reboot`s and manually scaling stale ReplicaSets to zero.

**The lesson, and it is the biggest one in this chapter:** *self-healing is not self-healing when the
healer shares the resource.* Every recovery mechanism Kubernetes has runs on the control plane. Starve
the node and you lose the thing whose job is to notice.

Also: **defaults are tuned for the common case, and a single-replica Deployment is not it.**
`maxSurge: 25%` sounds conservative and means 100% surge at one replica.

## The fix that caused a second outage

`maxSurge: 0` worked — verified live through a full five-service redeploy.

> But **the very next redeploy against the fixed process took the box down again**, by a different
> route [...] the inventory-service HPA reacted to the CPU spike that five JVMs cold-starting at once
> naturally produce — class loading and Spring context init, not real request load — and added two more
> inventory-service replicas **during the exact window the box had the least spare memory.**

The autoscaler had a **zero-second** scale-up stabilization window, chosen so a burst's scale-up would
be visible while it was still draining. Perfectly sensible for demonstrating Scenario 8. Catastrophic
during a deploy.

> `maxSurge: 0` prevents a service from ever running two versions of itself; **it does nothing to stop
> the HPA from independently deciding the moment right after a deploy is when inventory-service needs
> *more* replicas.**

**Two lessons.** *Startup CPU is not load* — a cold JVM looks identical to a busy one for the first
several seconds, and only a stabilization window separates them. And *fixing one contributor to a
multi-factor failure leaves the others* — worse, a working deploy is exactly what let the HPA see a
spike it would previously have been drowned out by.

Fixed with a 60-second window, chosen to sit **between two measured durations**: cold-start CPU settles
in tens of seconds, genuine burst load stays elevated well past 60.
[Chapter 8](../08-observability-and-scaling/4-the-autoscaler.md).

And the restraint worth copying: the 65% CPU threshold was **not** changed, because *"it's already
validated against real Scenario 8 load and wasn't the problem."*

---

## The probe that caused what it detected

**Where:** Kafka, everywhere. **Found:** Phase 10, on a laptop. **Severity:** total loss of the broker,
under load.

Compose and the base manifests used `kafka-broker-api-versions.sh` as Kafka's readiness check —
deliberately chosen because it proves the broker actually *answers*, not merely that the process is up.

It **starts a JVM inside the broker container on every check.**

> began timing out under CPU contention and flapped the broker Ready/NotReady, **taking the Kafka
> Service's endpoints to zero.** [...] A probe whose own cost causes the failure it is meant to detect
> is not a probe.

Under contention, the probe cannot get a CPU slice, times out, marks the broker not-ready, and every
client loses the broker — because the health check was too expensive to run.

Replaced with a `tcpSocket` check, with the cost stated: *"a TCP accept proves the listener is bound,
not that the broker will answer a metadata request — a weaker signal, bought by removing the probe's
own CPU cost."* [Chapter 9](../09-production/3-tuning-for-a-small-box.md).

**The lesson:** **a health check is a load.** On a machine with headroom that is invisible. On a
constrained one the observer changes the outcome — and health checks run forever, on every pod, at a
fixed interval, whether or not anything is wrong.

This one is worth flagging as a near-miss rather than an incident: Phase 10 found it on a laptop, and
Sprint 2 fixed it as **blocking work before the deploy**, so it never took down the public demo. That
is the system working.

---

## The reset that silently failed

**Where:** Inventory Service. **Found:** on the live demo. **Severity:** the demo wedged, permanently.

`POST /demo/reset` used the business `PUT /api/inventory/{sku}` to restore seed quantities. That
endpoint correctly rejects an update where `availableQuantity < reservedQuantity`, as an oversold
state.

And:

> reservations are only released on the payment-failure compensation path (**never on successful
> fulfillment**), so `reservedQuantity` accumulates without bound over a long-running demo and will
> routinely exceed any seed value.

So after enough successful orders, reset returned **409** and did nothing. The demo could not be reset,
and `freeQuantity()` was permanently below the seed. Commit `1a81745` — *"fix inventory reset not
clearing reservations, wedging the live demo."*

Two correct behaviors composing into a broken one. The business rule is right. Reservations
accumulating on the success path is a modelling decision, also defensible — a fulfilled reservation is
history, not free stock.

Fixed with `restoreForDemo`, a demo-only operation that zeroes both fields together, deliberately
bypassing the business guard. [Chapter 5](../05-scenarios-and-frontend/3-the-eight-scenarios.md).

**The lesson:** *a reset path is a feature and needs its own semantics.* Reusing the business endpoint
looked like reuse and was actually a category error — reset is not an update. And this is the
`/api`–`/demo` split earning its keep on a case Phase 0 never anticipated: rather than weakening the
business rule, the demo got an operation with its own rules, quarantined.

Also: **long-running state accumulates.** Everything worked in a test that created a few orders. The
failure needs *enough* orders, which only an unattended public demo produces.

---

## What these have in common

**The code was correct in every case.** Not one of these is a logic error. A default suited to a
different topology, an autoscaler reading a true metric, a health check that was too honest, and two
correct rules composing badly.

**All four are about resources or time.** Memory during a rollout, CPU during cold start, CPU during a
probe, and state accumulating over hours. None of them is expressible as a unit test.

**Three involve one mechanism interfering with another.** The Deployment controller versus the HPA. The
probe versus the thing it probes. The reset versus the business rule. Each component behaving correctly
in isolation.

**Environment is a first-class variable.** Everything here worked on an 8-core laptop. The 2-vCPU box
is not a smaller version of the same environment — it is a different one, where costs that round to
zero become the dominant term.

---

[← Found by looking](2-found-by-looking.md) · [Next: What this adds up to →](4-what-this-adds-up-to.md)


# 10.4 — What this adds up to

[← Found in production](3-found-in-production.md) · [Chapter 10 ↑](README.md)

Fourteen mistakes across three categories. The patterns across them are worth more than any individual
fix.

---

## Six patterns

### 1. Check-then-act is the recurring hazard, and it wears three disguises

The idempotency ledger (two threads both see "not processed"). The inventory reservation (two orders
both see enough stock). The order status (two topics both decide from a stale status).

Same shape every time: **read state → decide → write**, with an interleaving in between. And three
different correct answers, chosen by access pattern rather than preference:

| Where | Fix | Why that one |
|---|---|---|
| Ledger | `INSERT … ON CONFLICT DO NOTHING` | The write *is* the check; the database serializes it for free |
| Reservation | Optimistic `@Version` + bounded retry | Conflicts are rare; uncontended paths pay nothing |
| Order status | Pessimistic `SELECT … FOR UPDATE` | Conflicts are *expected*, and the operation is too expensive to redo |

Recognizing the shape is most of the skill. Choosing between the three is the rest.

### 2. A contract nothing enforces is a description of intent

Phase 0 produced an exhaustive transition table with consistency checks proving every state reachable
and every event accounted for. It was correct, and **no code read it** until after Phase 10 — so orders
silently went backwards.

The same pattern, milder, in the `FAILED` transition: defined in Phase 0, unimplemented until Sprint 2,
so dead-lettered orders displayed as in-progress forever.

**If a rule matters, something must check it**, as close to the write as possible. A document is where
the rule lives; it is not what makes the rule true.

### 3. The dangerous bugs live between two correct decisions

ADR-005 requires the idempotency claim inside the business transaction. Correct. ADR-006 scoped the
outbox to one service on the reasoning that the others would self-heal via redelivery. Plausible.
Together: the ledger short-circuits the redelivery, and the "self-healing" never happens.

The Deployment controller avoids doubling memory. The HPA adds replicas on high CPU. Both correct.
Together: an outage.

The business inventory rule rejects an oversold state. Reservations accumulate on the success path.
Both defensible. Together: a reset that silently fails.

**No document describes an interaction.** Each ADR is a complete account of its own decision. The
failure lives in the gap, and finding it requires holding two mechanisms in your head at once and
asking what happens between them.

### 4. Every number should be derived, and the one that wasn't is the one that broke

The bounds in this project that hold:

- **3 Kafka retries** — because retrying blocks a partition, and a bigger budget turns one poison
  record into a partition outage.
- **25 CAS attempts** — because that exceeds partitions × listener concurrency × instances.
- **10 drain passes** — because the longest legal transition chain is 6.
- **7 days of retention** — because that is Kafka's own topic retention.
- **`maxReplicas: 3`** — because `orders.events` has 3 partitions and a fourth consumer would idle.
- **60s HPA stabilization** — because cold-start CPU settles in tens of seconds and burst load stays
  elevated past 60.

And the one chosen by feel: **3 CAS attempts, no backoff.** It stranded orders under real load.

If you cannot say why a number is what it is, it is a guess — and the guesses fail first.

### 5. Environment is a variable, and constraints are a different category of problem

Every production failure in [section 3](3-found-in-production.md) involved code that was correct. A
2-vCPU box is not a smaller version of an 8-core laptop; it is a different environment where costs that
round to zero become dominant.

The health check that starts a JVM is free on a laptop and fatal under contention. The rolling-update
default that guarantees zero downtime causes total downtime with no memory headroom. The autoscaler
that reacts instantly is right for a demo and wrong after a deploy.

The corollary is that **Phase 10's most valuable output was a measurement, not a graph.** Failing to
reach 3 replicas on the laptop produced the 3.825 GiB number that sized the production box correctly on
the first attempt, and identified the probe cost before it could take the demo down.

### 6. How a bug is found predicts what kind of bug it is

| Detector | Finds |
|---|---|
| **Load** | Concurrency. Check-then-act, interleaved writes, cross-topic ordering. Nothing else finds these |
| **A deliberate audit** | Absences. Missing log lines, missing handlers, missing implementations, committed secrets |
| **Production** | Resource and time. Memory ceilings, CPU contention, state accumulating over hours |

Each detector is blind to the others' categories. Reviewing code will never find a race; load testing
will never notice that a committed file contains a password; neither will find that a health check is
too expensive on a machine you do not own yet.

**Running all three is the practice.** The project did — Phase 10's scaling work, Sprint 2's security
pass and bug hunt, and an actual deployment — and each found a different class of thing.

---

## What worked

Worth naming, because a retrospective that only lists failures is misleading.

**Writing down accepted costs.** ADR-005 flagged unbounded ledger growth. ADR-009 flagged the
unimplemented `FAILED` transition. The event catalog flagged the dual-write window. Every one was
closed later *because it was written down*. A documented gap decays slowly; an undocumented one is
found by an outage.

**Correcting ADRs in place, additively.** ADR-006 kept its wrong reasoning and appended two correction
blocks. Anyone who read the original can find out exactly what was wrong with it, and the *why* of the
correction is the reusable part.

**Keeping the discipline nothing verified.** Six chapters of `${VAR:local-default}` with nothing
enforcing it, and containerization turned out to be two environment variables per service.

**Refusing to display what could not be observed.** The Event Explorer shows publication and not
consumption, because consumption happens inside another service's transaction. A fabricated
"consumed at, 43ms, 0 retries" would look better and be false.

**Verifying in the real medium.** The Actuator CORS trap was found *"via live browser verification, not
curl."* The 404-as-500 was found by making the requests. The HPA was verified with real `kubectl
describe hpa` events, in both directions.

---

## If you built this again

Five things to do differently, in order of value:

**1. Make the state machine executable in Phase 0.** The transition table was written, checked for
consistency, and not consulted by any code for most of the project's life. Transcribing it into a
`VALID_PREDECESSORS` map is an hour's work and would have prevented the worst bug in the project.

**2. Write the concurrency tests with the load, not after.** Every check-then-act bug was found by
concurrency and could have been found by a test that *proves it raced* — the conflict counter, applied
from the start.

**3. Audit log call sites when you build the mechanism, not three phases later.** "Run the real workflow
and look at what came out" would have caught the empty consumers immediately.

**4. Treat the deployment environment as a design input from Phase 0.** Not by deploying early —
ADR-007 is right that Kubernetes should wait — but by knowing the target's shape. Heap caps, probe
costs, and rollout strategy are all decidable once you know it is 2 vCPUs and no swap.

**5. Extend the contract-change protocol to code comments.** The coordination protocol correctly caught
the `db-ownership.md` change during the outbox rollout. It missed three Javadoc comments describing the
old behavior, because Javadoc is not a frozen contract. A grep for the changed thing's name across the
whole repo would have.

---

## The guide ends here

Ten chapters, twenty-two technology primers, five pattern pages, and a glossary — from a `docs/`
directory with no code in it to a system on the public internet that a stranger can break in eight
specific ways and cannot break in any other.

The thing worth taking from it is not the outbox pattern or the idempotency ledger, both of which are
in any distributed-systems book. It is the habit visible in every ADR and half the code comments in
this repository: **decide, write down why, name what it costs, and correct it in the open when it turns
out to be wrong.**

That is what the fourteen entries in this chapter have in common. Every one of them was findable
afterwards, because someone had written down what they thought was true.

---

[← Found in production](3-found-in-production.md) · [Chapter 10 ↑](README.md) · [Back to the index](../README.md)


<hr style="page-break-after: always;"/>

# Pattern — DTO / entity separation

**Where it's introduced:** [Chapter 2, section 3](../02-domain/3-the-http-layer.md).
**Where it recurs:** every controller in all five services.

---

## The rule

> Never expose JPA entities directly from controllers; keep DTOs separate from persistence entities.

It is one of this project's twenty hard agent rules and it holds without exception across the
codebase.

---

## The problem

A JPA entity is a description of a database row. A response body is a description of what a client is
entitled to see. Those are two different things that happen to overlap at the moment you write them,
and the overlap is what makes the shortcut tempting: `return orderRepository.findById(id)` works, is
one line, and produces plausible JSON.

Five things go wrong afterwards.

**Your schema becomes your public API.** Rename a column and you have broken every client. Every
future migration is now an API-versioning problem, which is precisely the coupling
[Chapter 1](../01-design-contract/1-boundaries-and-ownership.md) spent a whole ADR avoiding
*between* services — and it is no more acceptable between your database and your consumers.

**Everything is exposed, including what shouldn't be.** Serialization is opt-out. A column added for
internal bookkeeping — a version counter, an internal note, a flag — appears in the response the
moment it is added to the entity, and nobody reviewing the migration is thinking about the API.

**Lazy loading meets serialization.** With `open-in-view: false` (which is the correct setting), a
lazily-loaded association touched during JSON writing throws. With it on, the serializer silently
issues queries. Neither is a good outcome, and both are invisible until they happen.

**The request side is worse.** Binding a request body straight onto an entity means the client
chooses which fields to set. Mass-assignment vulnerabilities are exactly this shape: a field the
client should never control — `status`, `totalAmount`, an ownership reference — set from JSON because
nobody enumerated what was writable.

**Validation ends up in the wrong place.** Input constraints ("customerId is 1–64 characters") are
about *requests*, not about *rows*. Putting them on the entity means they also apply to internal
writes that should not be subject to them.

## The decision

Two families of types, in different packages, converted explicitly in the service layer.

- **Entities** (`com.orderfulfillment.<domain>`) — mutable classes, JPA-annotated, package-visible
  intent, never serialized to a client.
- **DTOs** (`com.orderfulfillment.<domain>.dto`) — immutable Java records, Bean Validation
  annotations on the inbound ones, no JPA anywhere.

Java records make the DTO side nearly free:

```java
public record OrderAccepted(String id, String status, Instant createdAt) { }

public record CreateOrderRequest(
        @NotNull @Size(min = 1, max = 64) String customerId,
        @NotEmpty @Size(min = 1, max = 20) @Valid List<CreateOrderItem> items
) { }
```

Conversion is a hand-written mapping in the service layer, not a reflective mapper library:

```java
List<OrderItemDto> items = orderItemRepository.findByOrderId(orderId).stream()
        .map(i -> new OrderItemDto(i.getSku(), i.getQuantity(), i.getUnitPrice()))
        .toList();
return new OrderDetail(order.getId(), order.getCustomerId(), order.getStatus().name(),
        order.getTotalAmount(), order.getCreatedAt(), order.getUpdatedAt(), items, history);
```

Verbose on purpose. Every field that reaches a client is named at least once by a human, which is the
property the whole pattern exists to buy. An automatic mapper would restore the coupling by making
new entity fields flow outward by default.

## Details worth copying

**`status` is a `String` in the DTO, an `OrderStatus` enum in the entity.** The conversion is
`order.getStatus().name()`. This keeps Jackson's enum handling out of the contract and means the
response shape is decided by the OpenAPI spec rather than by an enum's serialization defaults.

**Inbound and outbound DTOs are separate types.** `CreateOrderRequest` is not a stripped-down
`OrderDetail`. What a client may send and what it may receive are different questions, and one type
answering both drifts toward being neither.

**Different DTOs for different responses.** `POST /api/orders` returns `OrderAccepted` — three
fields. `GET /api/orders/{id}` returns `OrderDetail` — eight, including items and full status
history. `GET /api/orders` returns `OrderSummary` inside an `OrderPage`. A list endpoint that returned
full detail per row would fetch history for every order on the page.

**No entity type appears in any controller signature.** The mechanical way to check the pattern holds:
grep the controllers for `Entity` and expect nothing.

## The cost

Real, and worth stating plainly: more types, and a mapping to update whenever a field should become
visible. That is the trade — a small recurring cost, in exchange for the schema and the API being
able to change independently. For a system whose whole thesis is that boundaries are worth paying
for, it is a consistent one.

## Where else this appears

Every service. `InventoryItemDto` and `UpdateInventoryRequest` in Inventory Service,
`PaymentAttemptDto` and `PaymentBehaviorDto` in Payment Service, `ShipmentDto` in Fulfillment
Service, and nine DTOs in Scenario Service.

The event payloads in `common/events/` are the same idea applied to a different boundary: the wire
contract for Kafka is its own set of records, separate from any entity, for the same reasons.
See [Chapter 3](../03-kafka-and-services/README.md).


# Pattern — Correlation ID propagation

**Where it's introduced:** [Chapter 3, section 3](../03-kafka-and-services/3-correlation-ids.md).
**Where it recurs:** every publish site, every `@KafkaListener`, every log line, every error response.

---

## The problem

A single order touches five services, four Kafka topics, five database schemas, and produces a few
dozen log lines. When something goes wrong, the question is always the same: **what happened to this
one order?**

Without a shared identifier, answering it means correlating by timestamp across five log streams and
hoping nothing else happened in the same millisecond. Under any concurrency at all, that does not
work.

The fix is conceptually trivial — put one identifier on everything belonging to one logical operation.
The difficulty is entirely in **propagation**: the identifier has to survive an HTTP boundary, a Kafka
hop, a thread change, and a service boundary, without every method signature growing a parameter.

## The decision

One `correlationId` (a UUID), generated once by whoever starts a workflow, carried through every hop.

**Three transports, one value:**

| Where | How it travels |
|---|---|
| HTTP request → service | `X-Correlation-Id` header (generated if absent) |
| Service → Kafka | `correlationId` field in the [event envelope](../01-design-contract/2-the-event-contract.md) |
| Kafka → service → next event | Read off the consumed envelope, copied onto everything published in reaction |

**Two scopes inside a process:**

| Where | Mechanism |
|---|---|
| Application code (`EventPublisher`, error responses) | A `ThreadLocal` in `CorrelationIdHolder` |
| Log lines | SLF4J's **MDC** (Mapped Diagnostic Context), a logging-framework `ThreadLocal` that the structured-logging encoder writes into every line automatically |

Both are set together, always, and cleared together, always.

## The implementation

### Entry point 1 — HTTP

```java
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        String incoming = request.getHeader(HEADER);
        UUID correlationId;
        try {
            correlationId = incoming != null ? UUID.fromString(incoming) : UUID.randomUUID();
        } catch (IllegalArgumentException ex) {
            correlationId = UUID.randomUUID();
        }
        CorrelationIdHolder.set(correlationId);
        MDC.put(MDC_KEY, correlationId.toString());
        response.setHeader(HEADER, correlationId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationIdHolder.clear();
            MDC.remove(MDC_KEY);
        }
    }
}
```

Four things worth copying exactly:

- **Accept an incoming header, generate one otherwise.** A caller that already has a correlation ID
  keeps it, so the trace spans the caller too.
- **A malformed header is replaced, not rejected.** A caller sending garbage should not get a 400 for
  a diagnostic header — but it must not poison the trace either.
- **Echo it back in the response.** The client now knows the ID for the request it just made, which is
  what makes a bug report actionable.
- **Clear in a `finally`.** Non-negotiable — see below.

### Entry point 2 — a Kafka listener

An HTTP filter cannot help here: a consumer thread has no request. The listener sets the scope itself,
from the envelope it just read:

```java
@KafkaListener(id = "...", topics = KafkaTopics.ORDERS_EVENTS, groupId = "inventory-service")
public void onMessage(String message) {
    EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
    CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
}
```

```java
public static void runInScope(UUID correlationId, Runnable action) {
    set(correlationId);
    MDC.put(CorrelationIdFilter.MDC_KEY, correlationId.toString());
    try {
        action.run();
    } finally {
        clear();
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }
}
```

**Every `@KafkaListener` wraps its work in `runInScope`.** That single line is what carries the trace
across the Kafka hop and into everything the handler does.

### Reading it, rather than passing it

`EventPublisher` takes no correlation-ID parameter:

```java
public EventEnvelope<Object> buildEnvelope(String eventType, String aggregateId, UUID eventId, Object payload) {
    UUID correlationId = CorrelationIdHolder.get();
    if (correlationId == null) {
        throw new IllegalStateException("No correlationId in scope while publishing " + eventType
                + " — every publish site must run within an HTTP request or a @KafkaListener that set one");
    }
    return new EventEnvelope<>(eventId, eventType, EventTypes.CURRENT_VERSION,
            Instant.now(), correlationId, aggregateId, payload);
}
```

This is the trade the pattern makes. Threading the ID explicitly would mean adding a parameter to
every method between the entry point and the publish site — which is where most attempts at this
quietly die. Reading it from ambient scope keeps signatures clean, at the cost of an invisible
dependency on somebody having set it.

**The `IllegalStateException` is what makes that trade safe.** A publish site running outside any
scope fails loudly and immediately, naming the rule it broke, instead of writing an event with a null
correlation ID that nobody notices until a trace comes up empty three weeks later. An implicit
dependency with a loud failure is very different from an implicit dependency with a silent one.

## The failure modes

**Forgetting to clear.** `ThreadLocal`s on a pooled thread outlive the work that set them. The next
request or record handled by that thread inherits a stale ID, and the trace silently merges two
unrelated operations. Always clear in a `finally`; never in the happy path only.

**Losing it across an async boundary.** A `ThreadLocal` does not follow work handed to an executor,
a `CompletableFuture`, or a `@Async` method. It must be captured and re-established explicitly —
which is exactly what `runInScope` does, and what any thread hand-off has to do too.

**Setting the holder but not the MDC** (or the reverse). They are two independent `ThreadLocal`s.
Setting one gives you an ID in your code with nothing in the logs, or logs with an ID that
`EventPublisher` cannot see. Set and clear both, in one place — which is why `runInScope` exists
rather than callers doing it by hand.

## What it buys

`docker compose logs | grep <correlation-id>` returns every log line, from every service, belonging to
one order — in order.

The same value also lands in:

- **the `ApiError` response body**, so a user reporting a failure hands you the exact search term;
- **every event envelope**, so the Event Explorer can group a workflow's events;
- **structured log fields**, where the ECS encoder puts MDC entries under `labels.*` automatically —
  see [Chapter 8](../08-observability-and-scaling/README.md).

## Relationship to distributed tracing

This is a hand-rolled subset of what OpenTelemetry, Zipkin, or Micrometer Tracing provide — those add
spans, parent/child relationships, timing, and sampling, and propagate through W3C `traceparent`
headers automatically.

Building it by hand here is a deliberate scope decision: one field on an envelope and two
`ThreadLocal`s, versus a collector, a backend, and an agent. The honest framing is that this gives you
**correlation** but not **tracing** — you can find every line for one operation, but not a timing
waterfall showing where the time went.


# Pattern — The idempotent consumer

**Where it's introduced:** [Chapter 4, section 1](../04-reliability/1-idempotent-consumers.md).
**Where it recurs:** every `@KafkaListener` in all four business services.

---

## The problem

Kafka delivers **at least once**. A consumer will see the same record twice, and this is not an edge
case — it is the ordinary consequence of ordinary events:

- A consumer processes a record, writes to its database, and crashes before committing its offset. On
  restart it reads the same record again.
- A consumer group rebalances mid-batch (a deployment, a scale-up, a missed heartbeat) and a
  partition's uncommitted records are redelivered to their new owner.
- A producer's send times out, the producer retries, and both records land.

Kafka cannot fix this for you. The commit that would have to be atomic is *your database write plus
Kafka's offset commit*, and those are two different systems.

The consequences are not symmetrical. A duplicated read is harmless. A duplicated **side effect** is
a second reservation against the same stock, a second charge, a second shipment. In this project the
worst case is inventory *release*: applying it twice hands the same units back to stock twice,
inventing inventory out of nothing.

So the requirement is: **processing a record twice must have the same effect as processing it once.**

## Three ways to get there

**Make the operation naturally idempotent.** `SET status = 'PAID'` is idempotent; `balance = balance -
10` is not. Where you can express the work as an assignment or an upsert keyed by something stable,
you need nothing else. This covers less than you would hope.

**Use a natural business key with a uniqueness constraint.** "One shipment per order" enforced by
`UNIQUE (order_id)` means a second attempt fails at the database. Excellent as a backstop, but the
failure arrives as an exception you must then classify, and it only works where such a key exists.

**Keep a ledger of what you have processed.** Record `(eventId, consumer)` as you apply the change,
and skip anything already recorded. General, explicit, and works regardless of what the operation
does.

This project uses the third as the primary mechanism, with the second as defence in depth.

---

## The design

### The table

```sql
CREATE TABLE processed_events (
    event_id      uuid NOT NULL,
    consumer_name text NOT NULL,
    processed_at  timestamptz NOT NULL,
    PRIMARY KEY (event_id, consumer_name)
);
```

**Per service, never shared.** Four schemas, four identical tables. The reason is correctness rather
than taste: the ledger row must commit **in the same local transaction** as the business change it
guards, and a transaction cannot span two services' schemas. It also avoids putting four services'
consumers into one write hotspot and coupling four migration histories.

**The key is composite** — `(eventId, consumerName)`, not `eventId` alone. One event is legitimately
processed once *by each* of several consumers: Order Service and Fulfillment Service both consume
`PaymentAuthorized` for different reasons. Keying on `eventId` alone would let whichever arrived first
suppress the other.

**`consumerName` must be stable across restarts.** Conventionally `"<service>.<listener>"` —
`"inventory.order-created"`, `"order.payment-events"`. Never a hostname, a partition number, a
generated client ID, or anything else that varies between deployments:

> a redelivery after a restart would fail to match the ledger row it is supposed to match.

**One name per listener method, not per event type.** A listener handling both `InventoryReserved`
and `InventoryReservationFailed` uses one `consumerName` for both — the composite key already
disambiguates by `eventId`.

### The insert is the authority, not the read

This is the part that is easy to get subtly wrong.

```java
public boolean isProcessed(ProcessedEventKey key) { /* SELECT count(*) … */ }

@Transactional(propagation = Propagation.MANDATORY)
public boolean recordProcessed(ProcessedEventKey key) {
    int inserted = jdbcClient.sql(
            "INSERT INTO " + tableName + " (event_id, consumer_name, processed_at) "
          + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
        .param(key.eventId()).param(key.consumerName()).param(now).update();
    return inserted != 0;
}
```

A naive implementation reads "have I seen this?" and then applies the change if not. **That is
check-then-act, and it is not atomic.** Two listener threads — and a service runs several per topic —
can both read *absent* and both apply.

So the read is demoted to a cheap early-out, and the decision is made by an
`INSERT … ON CONFLICT DO NOTHING` **inside the business transaction**:

- Insert affected one row → you claimed it → apply the change.
- Insert affected zero rows → someone else already claimed it → skip.

A concurrent duplicate blocks on the uncommitted row and then sees zero rows affected — exactly the
answer it should get. The database's own concurrency control does the work, which is the general shape
of every correct solution to check-then-act.

Note the asymmetry in what the two answers are worth: `isProcessed` returning **true** is final
(rows are never purged while the event could still be redelivered), while **false** only means "not
yet, as of now."

### The claim must be inside the business transaction

```java
@Transactional(propagation = Propagation.MANDATORY)
public boolean recordProcessed(ProcessedEventKey key) { … }
```

`MANDATORY` means: join the caller's transaction, and **throw if there isn't one**. It is mechanical
enforcement of the rule the whole pattern rests on. Calling this outside a transaction would allow
two failure modes:

- The ledger row commits, the business change does not → the event is **silently lost**, because
  the redelivery will be skipped as a duplicate.
- The business change commits, the ledger row does not → the redelivery **applies it twice**.

`MANDATORY` turns both into a loud failure at the call site instead of a production mystery.

### The claim must be the first statement, at the right level

It belongs in the method that *owns the business transaction* — not in the listener above it, and not
in a retry loop wrapping it:

> Claiming the event one level up would leave the row in an outer transaction that commits separately
> from the reservation it is supposed to be atomic with.

And it should be that method's first statement, so a duplicate short-circuits before any work runs.

This has a pleasing consequence where a retry loop is involved: an attempt that rolls back rolls back
its ledger row too, so a reservation that takes seven optimistic-lock attempts still leaves **exactly
one** ledger row — written by the attempt that actually committed.

---

## The shape in a listener

```java
public void onMessage(String message) {
    EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
    CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
}

private void handle(EventEnvelope<JsonNode> envelope) {
    // 1. Filter FIRST — before the ledger is touched
    if (!EventTypes.ORDER_CREATED.equals(envelope.eventType())) {
        return;
    }
    ProcessedEventKey eventKey = new ProcessedEventKey(envelope.eventId(), CONSUMER_NAME);

    // 2. Cheap early-out
    if (processedEventLedger.isProcessed(eventKey)) {
        log.info("Skipping duplicate delivery of {} for {}", envelope.eventId(), envelope.aggregateId());
        return;
    }

    // 3. Delegate — the real claim happens inside the domain's own transaction
    inventoryService.reserve(orderId, lines, eventKey);
}
```

**Filtering before touching the ledger** matters more than it looks:

> A skipped record has no side effect to deduplicate, and recording it would fill the ledger with rows
> for events this service never acts on.

## Retention

The ledger grows monotonically. A row is only ever needed to answer "was this already processed?" for
as long as Kafka could still redeliver the event — so **purging rows older than the topic's retention
is safe**, by the same reasoning that makes the ledger work at all.

The project's default window is 7 days, matching Kafka's own default `log.retention.hours=168`, with
a daily scheduled `DELETE … WHERE processed_at < ?`. Deliberately unsophisticated: housekeeping, not
a latency-sensitive path.

## What this does *not* buy you

Worth being precise, because it is a common overclaim:

- **It is not exactly-once.** The record is still delivered more than once; the *side effect* happens
  once. Same observable outcome, completely different mechanism.
- **It does not protect against a genuinely new event that duplicates work.** Two distinct
  `OrderCreated` events for the same order have different `eventId`s and both get processed. The
  business-key constraint is what catches that.
- **It does not help across services.** Each service deduplicates independently, which is exactly
  what the composite key exists to allow.


# Pattern — The transactional outbox

**Where it's introduced:** [Chapter 6](../06-outbox/README.md).
**Where it recurs:** all four business services, identically.

---

## The problem: the dual write

Every publisher in an event-driven system does two writes that must either both happen or neither:

1. change its own database,
2. publish the event that tells everyone else about it.

Two different systems. No shared transaction. So there is a window between them, and a crash in that
window leaves the two out of step.

**Publish after commit** — the obvious ordering — loses events:

```
BEGIN; INSERT order; COMMIT;    ← durable
                                 ← crash here
kafka.send(OrderCreated);        ← never happens
```

The order exists. It is visible over the API. **Nothing will ever process it**, and nothing retries,
because from the database's point of view the work succeeded.

**Publish before commit** trades that for something worse — a *phantom* event describing a state
change that never persisted, which downstream services act on. A lost event leaves one aggregate
stuck; a phantom event corrupts other services' state.

Neither ordering works, because the problem is not the ordering. It is that two systems cannot commit
together.

## Why the textbook answers do not apply

**Two-phase commit / XA.** The classic answer. Kafka does not support it. And where XA is available it
is operationally painful — a blocking coordinator, in-doubt transactions after a coordinator failure.

**Kafka transactions.** Atomic *within Kafka*. They cannot enrol a PostgreSQL commit, which is the
entire difficulty.

**Change Data Capture** (Debezium tailing the write-ahead log). Genuinely strong — no application
publisher and no dual write at all, because the log *is* the commit. The costs are operational (Kafka
Connect plus a connector to run) and structural: CDC events are shaped by your table structure, so
producing a designed event envelope needs a transformation step. **The right answer for a real system
with many publishers**, and worth naming as such.

**Polling business tables** — find rows whose event was never published. Needs a per-aggregate notion
of "already published," which means either a column per aggregate type or an inference from state.
Fragile and does not generalize.

## The pattern

Make the event durable **in the same transaction** as the business change, by writing it to a table in
the same database. Publish from that table afterwards.

```
BEGIN;
  INSERT INTO orders …;
  INSERT INTO outbox_events (payload) VALUES (<full envelope>);
COMMIT;                          ← both, or neither

-- later, a separate poller:
SELECT … FROM outbox_events WHERE status = 'PENDING' ORDER BY id FOR UPDATE;
kafka.send(…);
UPDATE outbox_events SET status = 'PUBLISHED';
```

The dual write does not disappear — it **moves**, from between two systems to between one system and
itself. And that second gap has a completely different failure mode: a crash between the send and the
`PUBLISHED` mark resends the row on the next tick.

> **A lost-event problem becomes a duplicate-event problem** — and duplicates are the one thing
> idempotent consumers already handle.

That sentence is the whole pattern. It does not achieve exactly-once; it converts an unhandleable
failure into one you have already solved.

## The table

```sql
CREATE TABLE outbox_events (
    id           bigserial PRIMARY KEY,
    aggregate_id text NOT NULL,
    event_type   text NOT NULL,
    payload      jsonb NOT NULL,     -- the complete envelope
    created_at   timestamptz NOT NULL,
    published_at timestamptz NULL,
    status       text NOT NULL       -- PENDING | PUBLISHED | FAILED
);
```

**One per service, in that service's own schema.** Same reason as the idempotency ledger: the insert
must commit with the business change, which requires it to be in the same database.

**`id` is a monotonic sequence**, and ordering by it is what preserves the order transactions
committed in.

**`payload` holds the complete envelope**, not just the domain data — see below.

## Five details that matter

### Build the envelope at business-transaction time

Not later, in the poller. The event ID, timestamp, and correlation ID must describe **the moment the
change actually happened**, and must be identical however many times the row is resent.

A poller that stamped `occurredAt` at send time would report infrastructure delays as business times,
and a resend would produce a *different* event ID — defeating the consumer-side deduplication the
whole design depends on.

It also means the ID is known to the caller before the send, which matters when a payload references
its own event ID.

### Enforce the transaction

```java
@Transactional(propagation = Propagation.MANDATORY)
UUID record(String eventType, String aggregateId, Object payload) { … }
```

`MANDATORY` throws if there is no surrounding transaction. An outbox insert in a transaction of its
own **reintroduces exactly the dual-write window the pattern exists to close** — and would do so
silently. Fail at the call site instead.

### Send in order, one at a time, blocking on each acknowledgement

Per-partition ordering is only worth anything if the publisher preserves the order transactions
committed in. That means strictly oldest-first, one send at a time, waiting for each broker
acknowledgement before the next.

It also means **a send failure must stop the batch** rather than skip ahead — publishing later rows
first would reorder the topic.

This is the pattern's real cost: publication is serial per service.

### Bound the retries, and have a terminal state

A row that can never be published must not block the queue forever. Bound it — by retry count, or by
age if the schema has no counter — and move the row to a terminal `FAILED` state, logged loudly,
skipped so everything behind it proceeds.

Never delete or rewrite the payload. A `FAILED` row is the complete record of an event that should
have been sent, and it is the only evidence a human has.

### The poll interval *is* the added latency

Every event now waits up to one tick before publication. That is the pattern's other cost, it is
directly tunable, and the floor is set by how tightly you are willing to poll. (A notify-on-commit
hook can eliminate it; a poll interval is the simpler default.)

## What it does and does not give you

**Does:** no event is ever lost after its business change commits. The two are one commit.

**Does not:** exactly-once delivery. A duplicate is *more* likely now, not less, because a crash
between send and mark resends the row. That is the deliberate trade — and it is only safe if consumers
are already idempotent.

**Does not:** ordering across services, or delivery within a bounded time. It guarantees an event will
eventually be published, in the order its service committed it.


<hr style="page-break-after: always;"/>

# Technology primers

General technology explanation, independent of this project. The chapters stay lean and link here at
the point each concept first matters.

Each page assumes genuine unfamiliarity rather than jargon-recognition, and each is readable on its
own — you can arrive from a chapter, read one page, and go back.

A concept earns a page here when it needs more depth than a chapter's flow can carry, or when two or
more chapters need it. Project-*specific* implementation patterns live in
[`../patterns/`](../patterns/) instead; the dividing line is whether you would explain it the same way
on a different project.

---

## Java & build

| Page | Covers |
|---|---|
| [Maven multi-module builds](maven/multi-module-builds.md) | Aggregator POMs, `<modules>` vs `dependencyManagement`, BOM imports, `relativePath`, packaging application vs. library modules, what belongs in a shared module |

## Spring

| Page | Covers |
|---|---|
| [Dependency injection and stereotypes](spring/dependency-injection.md) | Constructor vs. field injection, `@Service`/`@Repository`/`@Component`, `@Bean` methods, singleton scope and thread safety, common startup failures |
| [Auto-configuration and component scanning](spring/auto-configuration.md) | Starters, conditional configuration, the `--debug` condition report, property precedence and relaxed binding, `@ConfigurationProperties`, profiles, the shared-module package trap |
| [Spring MVC controllers and exception handling](spring/web-mvc.md) | The annotation set, path-matching precedence, status codes, `@RestControllerAdvice`, the four exceptions most projects mishandle, `void` handlers, Jackson behavior |
| [Bean Validation](spring/bean-validation.md) | The constraint vocabulary, `@Valid` recursion, boxed vs. primitive types, bounding untrusted input, validating outside controllers |
| [Spring Data repositories](spring/data-repositories.md) | Derived query grammar, `@Query`, pagination and the hidden count query, `@Lock`, the `@Transactional` self-invocation trap, when to drop to plain SQL |

## Persistence

| Page | Covers |
|---|---|
| [JPA and Hibernate](jpa/hibernate-basics.md) | Entities, the persistence context, dirty checking, lazy loading, the `@Enumerated` ORDINAL footgun, N+1, detached entities, `ddl-auto` |
| [Flyway and schema migrations](flyway/migrations.md) | File naming, checksums and immutability, forward-only, versioned vs. repeatable, multiple independent histories |
| [PostgreSQL column types](postgres/column-types.md) | `numeric` vs. float for money, `timestamptz` vs. `timestamp`, `CHECK`/`UNIQUE` vs. application validation, foreign keys across boundaries, nullability as information |

## Messaging

| Page | Covers |
|---|---|
| [Messaging: queues vs. logs](kafka/log-vs-queue.md) | Synchronous vs. asynchronous, what a queue is good at, what a log buys, honest guidance on choosing |
| [Kafka: topics, partitions, keys, and offsets](kafka/topics-partitions-keys.md) | The record model, partitions as ordering *and* parallelism, keys and hashing, offsets and commits, consumer groups, rebalancing, replication, `acks`, KRaft |
| [Spring for Apache Kafka](kafka/spring-kafka.md) | What the starter auto-configures, serializer choices, `auto-offset-reset`, producing and offset commits, `@KafkaListener` and the listener container, concurrency, testing |

## Containers & orchestration

| Page | Covers |
|---|---|
| [Docker: images, layers, and multi-stage builds](docker/images-and-layers.md) | Layer caching and instruction order, multi-stage builds, base-image choice, build context and `.dockerignore`, exec vs. shell entrypoint, layered JARs, `ARG` vs `ENV` |
| [Kubernetes: the object model](kubernetes/objects.md) | Reconciliation, Pods and Deployments, Services and their types, ConfigMaps and Secrets, namespaces, requests vs. limits, reading cluster state |
| [Kubernetes: health probes](kubernetes/probes.md) | The three probes, why a dependency check in liveness causes restart storms, timing fields, probe types and costs, startup probes, Spring Boot health groups |

## Frontend

| Page | Covers |
|---|---|
| [React: components, state, and hooks](react/components-and-hooks.md) | The rendering model, `useState` and immutable updates, props and lifting state up, list keys, `useEffect` and cleanup, rules of hooks, custom hooks |
| [TanStack Query](react/tanstack-query.md) | Server state vs. client state, query keys and the cache, mutations and `invalidateQueries`, `staleTime` vs `gcTime`, polling vs. pushing, error handling |

## HTTP & the web

| Page | Covers |
|---|---|
| [OpenAPI](http/openapi.md) | Generated-from-code vs. written-first, what a schema cannot say, `$ref`/`operationId`/`servers`, how a spec drifts |
| [Server-Sent Events](http/server-sent-events.md) | The wire format, `EventSource` and automatic reconnection, SSE vs. WebSockets, the HTTP/1.1 connection limit, keep-alives, `SseEmitter` mechanics and its traps, deployment considerations |
| [CORS](http/cors.md) | The same-origin policy, preflight, why `curl` proves nothing, the Actuator handler-mapping trap, when a proxy is the better answer |

## Concepts

| Page | Covers |
|---|---|
| [Finite state machines](concepts/state-machines.md) | The formalism, why an explicit transition set matters, encoding the table so code consults it, consistency checks, rejecting vs. deferring |


# Finite state machines

*Referenced from [Chapter 1.3 — The state and API contracts](../../01-design-contract/3-state-and-api-contracts.md).*

---

## The formalism

Small and old. Four parts:

1. A finite set of **states**.
2. A designated **initial** state.
3. A set of **terminal** states — states with no exit.
4. A set of legal **transitions**: `(from, to)` pairs, each with a named cause.

The power is entirely in what it **forbids**. If the transition set is exhaustive, any `(from, to)`
not in it is invalid *by definition* — and "invalid" becomes something code can detect rather than
something a reviewer might notice.

## Why bother, for something as simple as an order status

Without an explicit transition set, "what states can this be in, and how did it get here" has no
answer except reading every writer. Any writer can put the entity into any state. Three specific
failures follow:

**Vocabulary drift.** Two documents name the same state differently, two components implement both, a
test asserts one and the UI displays the other. Nobody notices until something fails for a reason
unrelated to what actually broke.

**Silent invalid states.** An entity reaches a combination nobody intended, and there is no place in
the code where that could have been caught.

**Undoing terminal outcomes.** The one that hurts most in an asynchronous system: a late message
overwrites a finished state. Without a rule that nothing leaves a terminal state, a delayed event can
move a completed order back to in-progress — and it is not even wrong locally, because the code that
wrote it had no way to know.

## Making it real

The table is worthless if nothing consults it. A transition table that exists only as prose and
documentation comments is a description of what the code *ought* to do, and the gap between that and
what it does is invisible until something breaks.

The minimum useful form is a map from target state to the states it may be entered from:

```java
VALID_PREDECESSORS.put(PAYMENT_PENDING, Set.of(INVENTORY_RESERVED));
VALID_PREDECESSORS.put(PAID,            Set.of(PAYMENT_PENDING));
VALID_PREDECESSORS.put(FULFILLED,       Set.of(FULFILLMENT_PENDING));
```

Then every write consults it, and a transition whose current state is not in the target's set is never
durably applied.

Terminality is worth putting on the enum itself, so it travels with the value:

```java
private static final Set<OrderStatus> TERMINAL =
        Set.of(REJECTED_OUT_OF_STOCK, PAYMENT_FAILED, FULFILLED, FAILED);

public boolean isTerminal() { return TERMINAL.contains(this); }
```

## Two checks worth doing on the table itself

Mechanical, quick, and they catch real errors:

**Every state is reachable.** Map each state to the transition that produces it. This catches the
classic case of an enum value nothing can actually get into.

**Every cause is accounted for.** Map each event (or command, or input) to its transition. Causes with
*no* state effect should be listed explicitly, with the reason — so "missing from the table" cannot be
mistaken for an oversight.

## Distinguishing kinds of transition

Two categories are worth marking separately, because they answer different questions for a reader:

- **Externally caused** — something arrived and we reacted. There is an event, a request, a message.
- **Internal** — the owner moved the entity itself, with no inbound cause.

Marking the internal ones saves the next reader from hunting for an event that does not exist. In an
event-driven system it is also the honest way to record a transition whose "cause" is an *outbound*
message rather than an inbound one.

## Rejecting vs. deferring

When an invalid transition arrives, there are two defensible responses and they are not
interchangeable:

- **Reject it.** The transition is wrong and always will be. Drop it, log it, move on.
- **Defer it.** The transition is *premature* — legal, but its predecessor has not happened yet.
  Store it and apply it once the predecessor does.

Distinguishing the two requires knowing whether the arriving transition is *earlier* or *later* than
the current state along the expected path, which is more than the predecessor table alone can tell
you. A monotonic ordering over the happy path is the usual addition.

In a system with a single writer this distinction rarely comes up. In one where several independent
sources write the same state — several message consumers, say, with no ordering guarantee between them
— it is essential, and it is the subject of [Chapter 4](../../04-reliability/README.md).


# Docker: images, layers, and multi-stage builds

*Referenced from [Chapter 7.1 — Containers and Compose](../../07-containers-and-kubernetes/1-containers-and-compose.md).*

---

## Images are stacks of layers

Each instruction in a Dockerfile that changes the filesystem (`COPY`, `RUN`, `ADD`) produces a
**layer** — a diff against the layer below. An image is those layers stacked; a container is the image
plus a thin writable layer on top.

Two consequences drive almost every Dockerfile decision:

**Layers are cached, keyed by the instruction and its inputs.** If nothing has changed, the layer is
reused. If something has, that layer **and every layer after it** are rebuilt.

**Layers are immutable.** Deleting a file in a later layer does not shrink the image — the file is
still in the earlier layer, just masked. A secret `COPY`d in and `RUN rm`'d out later is still in the
image, and still extractable.

## Ordering for cache hits

Put what changes rarely before what changes often.

```dockerfile
COPY package.json package-lock.json ./
RUN npm ci            # ← cached unless dependencies changed
COPY . .
RUN npm run build     # ← reruns on any source change
```

Copying the whole tree first would invalidate the dependency install on every source edit, turning a
five-second rebuild into a two-minute one. The same shape works for Maven (`pom.xml` first), Go
(`go.mod`), Python (`requirements.txt`), and Rust (`Cargo.toml`).

## Multi-stage builds

The tools that build your application are not the tools that run it. A JDK plus Maven plus a local
repository is several hundred megabytes; a JRE plus one jar is a fraction of that.

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY services services
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Only the final stage becomes the image. Everything in the build stage — the compiler, the package
manager, the source, the dependency cache — is discarded.

Three benefits, and the third is the one people forget:

- **Size.** Typically an order of magnitude.
- **Speed.** Less to push and pull, on every deploy.
- **Attack surface.** No compiler, no package manager, no build tooling, and **no source code** in the
  running image. Nothing an attacker can use to build something new in place.

## Choosing a base image

| Base | Size | Notes |
|---|---|---|
| `eclipse-temurin:21` (Debian) | ~450MB | Full toolchain available; easiest to debug |
| `eclipse-temurin:21-jre` | ~270MB | Runtime only |
| `eclipse-temurin:21-jre-alpine` | ~180MB | musl libc, not glibc — occasionally matters for native libraries |
| `gcr.io/distroless/java21` | ~190MB | No shell at all: nothing to `exec` into, and nothing an attacker can either |

**Alpine's musl libc** is the one real gotcha. Most JVM workloads are fine; anything with native
dependencies (some image or crypto libraries) may not be. Test before assuming.

**Distroless** is the strongest option and the most annoying to debug — no shell means no
`kubectl exec` into a running pod. Fine for a service you never poke at; painful for one you do.

## Build context

```
docker build -f services/order-service/Dockerfile .
```

The final `.` is the **build context** — the directory sent to the Docker daemon before the build
starts. Everything in it is transferred, even files no instruction ever copies.

A `.dockerignore` is therefore not optional on a real repository: without it, `node_modules`, `target`,
`.git`, and every build artifact are packed up and shipped for every build.

Context also constrains what you *can* copy: `COPY` cannot reach outside it. For a monorepo where one
service's build needs a sibling module, that usually means the context is the repository root and the
Dockerfile lives in the service directory.

## Entrypoint and signals

```dockerfile
ENTRYPOINT ["java", "-jar", "app.jar"]      # exec form — correct
ENTRYPOINT java -jar app.jar                # shell form — avoid
```

The **exec form** (a JSON array) runs the process directly as PID 1, so it receives `SIGTERM` when the
container is stopped and can shut down gracefully.

The **shell form** wraps the command in `/bin/sh -c`, so the shell is PID 1 and typically does not
forward signals. The result is that every stop waits for the full grace period and then kills the
process — no graceful shutdown, no connection draining, and a puzzling ten-second delay on every
deploy.

## Layered JARs, for Spring Boot specifically

A Spring Boot fat jar is one file, so any code change invalidates the whole ~50MB layer even though
the dependencies inside it did not move.

Spring Boot's layertools splits it:

```dockerfile
FROM eclipse-temurin:21-jre-alpine AS extract
COPY target/app.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
COPY --from=extract dependencies/ ./
COPY --from=extract spring-boot-loader/ ./
COPY --from=extract snapshot-dependencies/ ./
COPY --from=extract application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

Dependencies become their own layer, cached across builds; only the small application layer changes.
Worth it when you push images often, and unnecessary when you do not.

## Build-time vs runtime configuration

```dockerfile
ARG VITE_API_URL=http://localhost:8081     # build time only
ENV SPRING_PROFILES_ACTIVE=production      # available at runtime
```

`ARG` exists only during the build. `ENV` persists into the running container and can be overridden at
`docker run`.

The distinction matters most for **frontends**. A browser-side SPA has its configuration compiled into
the bundle, so it must be an `ARG` — and that means one image per environment. A server-side
application reads its configuration at startup, so it can be an `ENV` and one image serves everywhere.

That asymmetry is not a flaw in the tooling; it is a consequence of where the code runs. The usual way
around it for an SPA is to serve configuration from an endpoint the bundle fetches at startup, at the
cost of an extra round trip before the app can render.

**Never bake a secret in with either.** `ARG` values are visible in the image history; `ENV` values are
visible to anyone who can inspect the image.


# Flyway and schema migrations

*Referenced from [Chapter 2.2 — Persistence](../../02-domain/2-persistence.md).*

---

## The problem

A database schema changes over time, and it must change **the same way** on every laptop, in CI, in
Docker Compose, and in production — each starting from whatever version it currently happens to be
at.

That rules out two things people try first:

- **Applying SQL by hand.** Works until there are two environments.
- **Letting the ORM generate the schema** (`ddl-auto: update`). It adds but never removes or alters,
  so it drifts silently, and it cannot express a backfill, a data migration, or a constraint added
  only after cleanup.

## How Flyway works

Numbered SQL scripts, applied in order, with a record of what has been applied.

```
src/main/resources/db/migration/
├── V1__orders.sql
├── V2__processed_events.sql
├── V3__order_id_sequence.sql
└── V4__outbox_events.sql
```

The naming is mechanical: `V` + version + `__` (two underscores) + description + `.sql`.

On startup Flyway reads its `flyway_schema_history` table, compares it against the directory, and
applies whatever is missing, in version order, each in a transaction. That table records the version,
description, the user who ran it, when, how long it took, and a **checksum** of the file.

## The two rules

### 1. An applied migration is immutable

Flyway checksums every file. Edit one that has already been applied anywhere and startup fails with a
checksum mismatch — deliberately, because environments that already ran the old version would never
pick up the change and would silently diverge.

Fix a mistake with a **new** migration. `V5__fix_the_thing_V4_got_wrong.sql` is not embarrassing; it
is how this is supposed to work, and the history is genuinely useful later.

(There is a `repair` command for the case where a migration failed and left no trace. Reach for it
knowing exactly why you need it.)

### 2. Forward-only, in practice

Flyway's free edition has no `down` scripts. Rolling back means writing a new migration that undoes
the change. This is less limiting than it sounds — down-migrations are notoriously undertested, and
in production the realistic recovery for a bad migration is a forward fix or a restore, not a
scripted reversal.

## Versioned vs. repeatable

- **`V`** — versioned. Runs once, ever. Almost everything.
- **`R__`** — repeatable, no version. Re-runs whenever its checksum changes, after all versioned
  migrations. For views, functions, and stored procedures, where "the current definition" is more
  useful than an accumulation of `CREATE OR REPLACE` diffs.

## Multiple independent histories

Flyway can be scoped to a schema:

```yaml
spring:
  flyway:
    schemas: order_service
```

Each schema then gets its own `flyway_schema_history` and its own independent version numbering —
which is why several services in one database can each have a `V1__` with no conflict.

When one JVM must migrate several schemas (a modular monolith with per-module schemas), Spring Boot's
single auto-configured `Flyway` bean is not enough: you construct one `Flyway` instance per schema,
each with its own `schemas` and `locations`, and run them at startup.

## Practical habits

- **One logical change per migration.** Easier to read, easier to reason about when one fails.
- **Migrations are code.** They get reviewed. A `DROP COLUMN` deserves as much attention as any
  deletion.
- **Test against a real database.** [Testcontainers](../../02-domain/5-testing.md) runs your actual
  migrations against actual PostgreSQL on every test run, which means a broken migration fails your
  build rather than your deployment.
- **Seed data can live in a migration** when it is genuinely part of the schema's meaning — a
  reference table, or a fixed demo catalog. Distinguish that from environment-specific data, which
  should not.


# CORS

*Referenced from [Chapter 2.3 — The HTTP layer](../../02-domain/3-the-http-layer.md).*

---

## The rule being relaxed

Browsers enforce the **same-origin policy**: JavaScript on one origin cannot read responses from
another. An *origin* is scheme + host + port, so `http://localhost:5173` and `http://localhost:8081`
are different origins — same host, different port is enough.

This is not a server-side security control. It is a browser control protecting *users*: without it,
any page you visit could issue authenticated requests to your bank with your cookies attached and read
the results. `curl` is unaffected, which is why an endpoint can work perfectly from a terminal and be
blocked in a browser.

**CORS** (Cross-Origin Resource Sharing) is how a server opts in to being read cross-origin.

## How it works

The server returns headers saying who may read the response:

```
Access-Control-Allow-Origin: http://localhost:5173
Access-Control-Allow-Methods: GET, POST
Access-Control-Allow-Headers: Content-Type, X-Correlation-Id
```

For anything beyond a simple `GET` or form-shaped `POST`, the browser first sends a **preflight**
`OPTIONS` request asking whether the real request is allowed. Only on an affirmative answer does it
send the real one. A misconfigured preflight is the usual cause of "it works in Postman."

Note what the browser is blocking: it blocks *your JavaScript from reading the response*. In the
non-preflighted cases the request may well have reached the server and had its effect. CORS is not
authorization.

## In Spring

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST");
    }
}
```

Driven by configuration, not hard-coded:

```yaml
app:
  cors:
    allowed-origin-patterns: ${APP_CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:*}
```

The local default works out of the box; a deployment overrides it with an environment variable and no
code change.

## Three ways to get it wrong

### `*` with credentials

`Access-Control-Allow-Origin: *` combined with `allowCredentials(true)` is rejected by browsers, and
for good reason — it would let any site make credentialed requests on a user's behalf. Enumerate
origins, or use patterns. Never `*` on anything non-public.

### Assuming one CORS configuration covers everything

Spring MVC serves different things through different handler mappings. `WebMvcConfigurer`'s
`addCorsMappings` covers regular `@RestController` endpoints — but **Actuator endpoints are served by a
separate `WebMvcEndpointHandlerMapping` that does not go through it**, and need their own:

```yaml
management:
  endpoints:
    web:
      cors:
        allowed-origin-patterns: "${app.cors.allowed-origin-patterns}"
        allowed-methods: GET
```

This is a genuinely easy one to miss, because the endpoint works perfectly under `curl` and fails only
in the browser. Verifying in a real browser rather than a terminal is what catches it.

### Reaching for CORS when the answer is a proxy

If the frontend is served from the same origin as the API — a reverse proxy routing `/api` to the
backend, or an ingress putting both behind one hostname — there is no cross-origin request and no CORS
configuration needed at all.

That is usually the better production setup. CORS then exists only for local development, where the
Vite dev server and the backend genuinely are on different ports.


# OpenAPI

*Referenced from [Chapter 1.3 — The state and API contracts](../../01-design-contract/3-state-and-api-contracts.md).*

---

## What it is

A YAML (or JSON) description of an HTTP API: paths, methods, parameters, request and response schemas,
status codes, and prose. Formerly called Swagger, which still names much of the tooling.

```yaml
openapi: 3.1.0

info:
  title: Order Service API
  version: 1.0.0

paths:
  /api/orders:
    post:
      operationId: createOrder
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateOrderRequest'
      responses:
        '201':
          description: Order accepted
```

## Two ways to use it, and they are not the same

**Generated from code.** Annotate your controllers, and a library produces the spec from what the
code does. The spec is always accurate and is pure *documentation* — it describes something that
already exists.

**Written first, code built against it.** The spec is a *contract*. It constrains something that does
not exist yet, which means a frontend and a backend can be built simultaneously by people who never
speak, and either can be tested against the spec rather than against the other.

The second is more work and buys something the first cannot: **on disagreement, there is an
authority.** If generated docs disagree with a client's expectation, the docs are right by
construction and the client is out of luck. If a written contract disagrees with an implementation,
the implementation has a bug — or the contract gets changed deliberately, and everything downstream is
re-checked.

Pick one deliberately. Generated specs are right for an API whose clients you control and ship
together. Written specs are right when the boundary is real.

## What a schema cannot say

The most valuable content in a hand-written spec is usually prose, because the things that most often
surprise a client are not shape:

> **Asynchrony.** `POST /api/orders` returns as soon as the order is persisted and `OrderCreated` is
> published. It does not wait for inventory, payment, or fulfillment. Clients observe the rest of the
> lifecycle by polling `GET /api/orders/{orderId}` or subscribing to `GET /api/orders/stream`.

No schema expresses that. A client author reading only the response shape sees an order with a
`status` field and reasonably concludes the status is *the answer*. It is the *first* answer.

Also worth writing down explicitly:

- **Where the document stops.** "Health and metrics are exposed by Actuator and are deliberately
  outside this document."
- **How it may change.** A frozen contract should say so, and say what the process is.
- **Which paths are what.** If some endpoints are production API and others are demo controls, the
  document should make the split unmissable.

## Useful mechanics

**`$ref` and `components/schemas`.** Define a type once, reference it everywhere. Shared error
envelopes especially — `ApiError` appears in every error response of every path.

**`operationId`.** A unique name per operation. Code generators use it for method names; a missing or
duplicated one produces generated clients with names like `postApiOrders1`.

**`servers`.** Where the API lives, including local development. Useful for mock servers and for
"try it" UIs.

**Examples.** Realistic request and response examples are worth more per line than almost anything
else in the file, especially for anyone reading rather than generating.

## The trap

**A hand-written spec can drift from the implementation, silently.** Nothing enforces the relationship
by default — that is exactly what makes it a contract rather than documentation, and exactly what makes
it able to lie.

Defences, in increasing order of effort: cite the spec from the code (a Javadoc line naming the file
and section, so anyone editing knows where the authority lives); assert response shapes in integration
tests; or generate a client from the spec and use it *in* the tests, so a divergence fails the build.


# Server-Sent Events

*Referenced from [Chapter 5.2 — Server-Sent Events](../../05-scenarios-and-frontend/2-server-sent-events.md).*

---

## What it is

A one-way stream from server to browser over an ordinary HTTP response that is never closed. The
server sets `Content-Type: text/event-stream` and writes text frames as things happen; the browser
parses them and fires events.

```
event: order-status-changed
data: {"orderId":"order-21873","status":"PAID"}

: keep-alive

event: order-status-changed
data: {"orderId":"order-21873","status":"FULFILLED"}

```

The wire format is deliberately trivial:

- `event:` — the event name the client listens for (defaults to `message`).
- `data:` — the payload. Multiple `data:` lines are joined with newlines.
- `id:` — an optional event ID the browser remembers.
- `retry:` — reconnect delay in milliseconds.
- A line starting with `:` is a **comment**, ignored by the parser — which is how keep-alives are
  sent.
- A **blank line** terminates a frame. Forgetting it means the client never fires.

## The client

```js
const source = new EventSource('/api/orders/stream?orderId=order-21873');
source.addEventListener('order-status-changed', (e) => {
  const data = JSON.parse(e.data);   // always a string; parsing is yours
});
source.addEventListener('error', (e) => { /* reconnecting, or dead */ });
source.close();
```

Built into every modern browser. No library, no protocol negotiation.

**Automatic reconnection is the headline feature.** If the connection drops, the browser waits (the
`retry:` interval, default a few seconds) and reconnects on its own. If the server has been sending
`id:` values, the browser sends the last one back as a `Last-Event-ID` header, so a server that wants
to can resume from it.

That is the single biggest practical difference from WebSockets, where reconnection is your problem.

## SSE vs. WebSockets

| | SSE | WebSockets |
|---|---|---|
| Direction | Server → client only | Bidirectional |
| Protocol | Plain HTTP | Upgrade handshake, own framing |
| Reconnection | Automatic | You implement it |
| Client library | None needed | Usually one |
| Payload | UTF-8 text | Text or binary |
| Proxies / infrastructure | Ordinary HTTP; works everywhere | Must permit upgrades |
| Connections per origin (HTTP/1.1) | ~6 browser limit | Not affected |

**Choose SSE when everything flows one way.** Live status, notifications, log tails, progress. The
client's own actions can be ordinary REST calls, which already have a natural request/response shape.

**Choose WebSockets when the client genuinely streams too** — chat, collaborative editing, games,
anything where the client sends a high-frequency stream rather than occasional commands.

The honest failure mode is picking WebSockets reflexively because "live updates" sounds like they
require it, and then owning a reconnect implementation, a heartbeat protocol, and proxy configuration
you did not need.

## The HTTP/1.1 connection limit

Browsers allow roughly **6 concurrent connections per origin** over HTTP/1.1, and an open SSE stream
occupies one for its whole life. Two streams plus normal API traffic is fine; a page opening six
streams will hang on the seventh request with no error, which is a memorable afternoon.

HTTP/2 multiplexes and effectively removes the limit. Worth knowing which one your deployment
actually serves.

## Keep-alives

Idle connections get closed — by proxies, load balancers, and NAT timeouts, typically after 30–60
seconds of silence. A stream that is legitimately quiet looks identical to a dead one.

The fix is a periodic comment frame:

```
: keep-alive

```

Ignored by the parser, indistinguishable from traffic to everything in between. Every 15–30 seconds is
typical. Without it, a quiet stream drops and reconnects on a cycle — which mostly works, and quietly
wastes connections and loses anything that happened during the gap.

## Server-side: Spring's `SseEmitter`

```java
@GetMapping(path = "/stream", produces = "text/event-stream")
public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
    registry.add(emitter);
    emitter.onCompletion(() -> registry.remove(emitter));
    emitter.onTimeout(() -> { emitter.complete(); registry.remove(emitter); });
    emitter.onError(ex -> registry.remove(emitter));
    return emitter;
}

// later, from any thread:
emitter.send(SseEmitter.event().name("order-status-changed").data(payload));
```

Returning an `SseEmitter` releases the servlet thread and leaves the response open. Sends happen later,
from whatever thread has something to say.

Four things to get right:

**`SseEmitter#send` is not thread-safe per emitter.** Spring's own Javadoc says so. Two threads
writing to one emitter can interleave mid-write and corrupt the byte stream — which surfaces as a
client-side parse error or a garbled event, not as a server exception. Synchronize per emitter (not
globally, or one slow client blocks every other).

**Always set a timeout, and always register all three callbacks.** An emitter that is never removed
from your registry is a leak, and dead emitters accumulate silently — you only find out when sends
start failing.

**A send to a disconnected client throws** `IOException` (broken pipe) or `IllegalStateException`
(already completed). Both mean "this client is gone." Remove it and move on; neither is an error worth
logging above `DEBUG`.

**Cleanup can itself throw.** `completeWithError` on a connection that is already unusable can raise a
second exception. If your send loop runs on a thread that is doing something else important, letting
that escape breaks the unrelated work. Wrap cleanup in its own try/catch.

## The error-handling trap

Once a response is committed as `text/event-stream`, **no JSON error body can be written to it**. A
framework exception handler that tries will fail a second time — typically with something like
"no converter for [ApiError] with preset Content-Type 'text/event-stream'" — logged on top of the
original failure and obscuring it.

The fix is a handler that writes nothing at all. In Spring MVC, a `void` return tells the framework
the response is fully handled:

```java
@ExceptionHandler(AsyncRequestNotUsableException.class)
public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex, HttpServletRequest request) {
    log.debug("Client connection is already gone for {} {}", request.getMethod(), request.getRequestURI(), ex);
}
```

A client disconnecting mid-stream is routine, not a server error. `DEBUG`, not `ERROR`.

## Deployment considerations

- **Buffering proxies break SSE.** nginx buffers responses by default and will hold your frames until
  a buffer fills. `proxy_buffering off;` (or `X-Accel-Buffering: no`) is required.
- **Read timeouts must exceed your keep-alive interval**, or the proxy closes the connection between
  keep-alives.
- **A rolling deployment drops every stream.** `EventSource` reconnects, and lands on whichever
  instance the load balancer picks — so any state you hold per connection must be re-establishable, or
  the client must be able to resync after a gap.
- **Compression is usually wrong here.** Buffering compressors defeat the point; disable it for the
  stream endpoint.


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


# Messaging: queues vs. logs

*Referenced from [Chapter 1.1 — Boundaries and ownership](../../01-design-contract/1-boundaries-and-ownership.md).*

---

## First: synchronous vs. asynchronous

Before choosing a messaging technology, there is a prior choice about whether to use one.

**Synchronous request/response.** Service A calls service B over HTTP and waits. The caller learns the
outcome immediately, and the whole workflow reads top to bottom as one function.

Its cost is that **availability multiplies**. Three services at 99% uptime, called in sequence within
one request, give roughly 97%. The request is as slow as the sum of every step. And a restart
downstream does not *delay* the operation — it *fails* it, in front of the caller.

**Asynchronous messaging.** A records what happened and returns. Whoever cares reacts when they can. A
restart downstream delays the work by the length of the restart; the message is still there
afterwards.

Its cost is that **nothing is immediate and nothing is in one place**. The outcome is not knowable at
the moment of the request. The workflow exists nowhere as readable code — it is an emergent property
of who publishes what and who subscribes to what. Debugging spans processes, and failures happen after
the caller has gone away.

Neither is "better." Asynchronous messaging buys independence and pays for it in observability and
immediacy.

---

## Then: queue or log?

Within asynchronous messaging there is a second split, and it is the one that makes Kafka different
from RabbitMQ.

### A message queue

*(RabbitMQ, ActiveMQ, SQS.)*

A message is handed to a consumer and, once acknowledged, **it is gone**. The queue is a buffer between
producer and consumer. Its natural questions are "how deep is the backlog" and "which consumer got
this one."

Strengths: mature routing (exchanges, topics, fanout, headers), per-message acknowledgement and
redelivery, priorities, per-message TTL, and delayed delivery. Operationally lighter than Kafka.

Limits, for the purposes this project cares about: once consumed, a message cannot be re-read. A
second, unrelated consumer that wants the same messages needs its own copy arranged in advance. There
is no position to rewind to, because there is nothing left to rewind through.

### A log

*(Kafka, Pulsar, Kinesis.)*

Records are appended to an ordered, durable sequence and **retained**, independent of who has read
them. Consumption does not remove anything. Each consumer keeps a bookmark — an **offset** — recording
how far it has read.

Three properties follow, and they are the reasons to choose a log:

- **Multiple independent consumers.** Two unrelated consumer groups read the same topic at their own
  paces without knowing about each other, and adding a third later requires nothing from the producer.
- **Replay.** A consumer can be rewound. A bug fixed today can be applied to last week's records.
- **A backlog is visible and drainable.** Stop a consumer, watch records accumulate, start it, watch it
  catch up. Nothing was lost, because nothing was ever removed on read.

The cost is retention: you are storing everything for a configured window (7 days by default),
whether or not anyone needs it. And the routing model is far simpler than a broker's — Kafka has
topics and partitions, and everything else is your consumer's problem.

---

## Choosing

The honest version, which is worth being able to say out loud:

- **Most systems that need a queue need a queue.** Job dispatch, email sending, image resizing —
  work that is consumed once and then genuinely finished. RabbitMQ is lighter, its routing is richer,
  and per-message acknowledgement fits the problem better.
- **Choose a log when replay, multiple independent consumers, or an observable backlog are actually
  worth something to you.** Event distribution across teams, stream processing, audit trails,
  anything where "who else might want these later" is an open question.
- **Throughput is rarely the deciding factor.** Kafka's throughput ceiling is famous and almost never
  the reason a given project needs it.

For a system built to *demonstrate* offsets, consumer groups, replay after an outage, and
dead-lettering, the log is not merely preferable — those concepts do not exist in the same form in a
queue.


# Spring for Apache Kafka

*Referenced from [Chapter 3.1 — Events on the wire](../../03-kafka-and-services/1-events-on-the-wire.md).*

Assumes the concepts in [topics, partitions, keys, and offsets](topics-partitions-keys.md).

---

## What the starter gives you

`spring-boot-starter-kafka` on the classpath plus a `bootstrap-servers` value auto-configures a
`ProducerFactory`, a `ConsumerFactory`, a `KafkaTemplate`, a `KafkaAdmin`, and the listener-container
infrastructure behind `@KafkaListener`.

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    consumer:
      group-id: my-service
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      auto-offset-reset: earliest
```

## Serialization: why `String` is often the right answer

Spring Kafka ships a `JsonSerializer`/`JsonDeserializer` pair that will map objects to and from JSON
for you. It is convenient and it has two properties worth understanding before you adopt it.

**It writes type headers.** `JsonSerializer` adds a `__TypeId__` header naming the Java class, and
`JsonDeserializer` uses it to pick a target type. That couples your wire format to your package
names: rename or move a class and existing records become undeserializable. Trusted-package
configuration is a further ongoing chore.

**It deserializes before your code runs.** A malformed record fails inside the container, not inside
your listener — which changes how you handle it, and makes "publish a deliberately unprocessable
record" harder to reason about.

The alternative is **`StringSerializer`/`StringDeserializer` plus an explicit codec**: the listener
receives a `String` and calls your own Jackson mapping. More typing, and in exchange the wire format
is exactly what you designed, deserialization failures happen at a point you control, and a malformed
record is an ordinary exception in your own code.

For a system with a designed envelope and a versioning rule, explicit wins. For internal
service-to-service traffic where the JSON shape is nobody's contract, the built-in serializer is
fine.

## `auto-offset-reset`

What a consumer group does when it has **no committed offset** — a brand-new group, or one whose
offsets have expired.

- **`earliest`** — start at the beginning of the topic.
- **`latest`** (the Kafka default) — start at the end, ignoring everything already there.

`latest` is a common source of "my consumer receives nothing": it started after the records were
produced, so from its point of view there is nothing new. `earliest` is usually the right choice for
event consumers, and it means a new consumer group replays the retained history — occasionally what
you want, occasionally a surprise.

This setting applies **only** when there is no committed offset. It has no effect on a group that has
run before.

## Producing

```java
kafkaTemplate.send(topic, key, value);
```

`send` returns a `CompletableFuture`. **Not calling `.get()` on it makes the send fire-and-forget** —
the record is batched and sent asynchronously, and a failure is logged by the Kafka client rather than
thrown to your code.

That is a deliberate choice with a real consequence: if your business transaction already committed
and the send then fails, you have a database change with no event. Blocking on the future does not
fix that either — it narrows the window without closing it. The actual fix is the transactional
outbox pattern.

## Consuming

```java
@KafkaListener(id = "inventory-events", topics = "inventory.events", groupId = "order-service")
public void onMessage(String message) {
    // ...
}
```

- **`topics`** — what to subscribe to.
- **`groupId`** — the consumer group. Same group across instances = shared partitions; different
  groups = independent copies of every record.
- **`id`** — a stable name for the listener *container*, which is what makes it addressable at
  runtime through `KafkaListenerEndpointRegistry`. That is how you pause and resume a listener
  programmatically. Give every listener an explicit `id`; the generated ones are not stable.

### Offset commits

By default Spring Kafka uses `BATCH` ack mode: it commits offsets after the listener returns
successfully for a batch of records. **If your method throws, the offset is not committed** and the
record is redelivered — which is exactly what makes error handling and retry work, and exactly why
duplicate delivery is normal rather than exceptional.

### Concurrency

```yaml
spring:
  kafka:
    listener:
      concurrency: 3
```

Runs N consumer threads for the listener, each assigned a subset of partitions. **Setting this above
the partition count buys nothing** — the extra threads are assigned no partitions and idle.

Concurrency here also means your listener method must be thread-safe, and that records for different
keys are genuinely processed in parallel.

## The listener container

`@KafkaListener` is backed by a `MessageListenerContainer`, which owns the poll loop, offset commits,
error handling, and lifecycle. Things you reach for it for:

- **Pausing and resuming.** `registry.getListenerContainer(id).pause()` stops delivery without
  leaving the consumer group — no rebalance, offsets intact, the backlog just builds.
- **Error handling.** A `DefaultErrorHandler` on the container factory controls retry counts,
  backoff, and where a record goes when retries are exhausted.
- **Knowing whether it is running.** `container.isRunning()`, `container.isContainerPaused()`.

## Testing

`spring-kafka-test` provides an embedded broker, and Testcontainers provides a real one via
`KafkaContainer`. Prefer the real broker: an embedded broker is a different implementation with
different timing, and the behavior you most want to test — rebalances, redelivery, offset
semantics — is exactly where implementations differ.

A useful pattern for assertions is a **raw consumer** in a throwaway group:

```java
props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-observer-" + UUID.randomUUID());
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
```

A unique group per test means it reads the whole topic from the start and never competes with the
application's own consumers for partitions.


# Kafka: topics, partitions, keys, and offsets

*Referenced from [Chapter 1.2 — The event contract](../../01-design-contract/2-the-event-contract.md).*

The four nouns that carry most of Kafka. Everything else is built on them.

---

## What Kafka stores

A **record** is a key (bytes), a value (bytes), a timestamp, and optional headers. Kafka does not know
or care what is inside the key or value — that is the entire data model, and it is why an
[envelope](../../01-design-contract/2-the-event-contract.md) is your problem rather than the broker's.

## Topic

A named, append-only log. `orders.events` is a topic. Producers append; consumers read from a
position. Nothing is removed on read — records expire on a **retention policy** (7 days by default),
not on consumption.

## Partition

A topic is split into partitions, and this is where the important properties live.

Each partition is an **independent, ordered, append-only sequence**. Records within one partition have
a strict order and a monotonically increasing position.

> **Kafka guarantees ordering within a single partition. It guarantees nothing across partitions, and
> nothing across topics.**

That one sentence is the source of most of the interesting problems in any Kafka system. Two records
on different partitions of the same topic may be processed in either order. Two records on different
*topics* — even about the same entity — have no defined relative order at all, no matter how they are
keyed.

Partitions are also the **unit of parallelism**. Within a consumer group, each partition is assigned
to exactly one consumer. A topic with 3 partitions supports at most 3 usefully-working consumers in one
group; a fourth is assigned nothing and idles.

Choosing a partition count is therefore choosing a parallelism ceiling. You can increase it later, but
doing so **changes which partition existing keys hash to**, which breaks per-key ordering across the
change. Pick with a little headroom.

## Key

The key decides the partition: `partition = hash(key) % partitionCount`. Same key, same partition,
always — as long as the partition count does not change.

No key means round-robin distribution and no ordering guarantee for anything.

So the key is not metadata. **It is the choice of what you want ordered relative to what.** Keying by
customer orders one customer's records; keying by order ID orders one order's records. Whatever you
key by is the scope of your ordering guarantee, and everything outside that scope is unordered.

The corollary is the part to internalize: if you key by `orderId`, then *one order's* records are
ordered, and any logic that assumes anything about the relative order of *two different orders* is a
bug — one that appears under load and vanishes when you try to reproduce it.

## Offset

A consumer's position in a partition. Reading does not advance anything durable; **committing** does.

The commit is what makes restart behavior work: a consumer that dies resumes from its last committed
offset. It is also where duplicate delivery comes from — process a record, write to your database,
crash before committing, and on restart you read the same record again.

This is why the delivery guarantee is **at-least-once** and why consumers must be idempotent. Kafka
cannot make your database write part of its offset commit.

## Consumer group

A set of consumers sharing a topic's partitions, identified by a `group.id`. Kafka assigns each
partition to exactly one member.

Two different behaviors fall out of the same mechanism:

- **Same group** = shared work. Add an instance, and partitions are redistributed between them.
- **Different groups** = independent copies. Each group has its own offsets and reads everything.

This is how one event feeds two unrelated services with neither aware of the other — a fan-out that
costs the producer nothing.

### Rebalancing

When membership changes — an instance starts, stops, or is deemed dead — the group **rebalances** and
partitions are reassigned. During a rebalance, processing pauses.

Rebalances are routine (deployments, scaling, a slow consumer missing a heartbeat) and are one of the
ordinary causes of duplicate delivery: a partition's uncommitted records get reassigned and reprocessed
by their new owner.

## Replication

A partition can be replicated across brokers. One replica is the **leader** (handling all reads and
writes) and the rest are followers.

`replication factor = 1` means a single copy and no redundancy: lose the broker, lose the data. That is
fine for local development and demos, and worth stating explicitly rather than letting someone assume
otherwise. Production usually runs 3.

## Producer acknowledgement

`acks` controls when a send is considered successful:

| `acks` | Means | Risk |
|---|---|---|
| `0` | Never wait | Records lost silently |
| `1` | Leader wrote it | Lost if the leader fails before followers replicate |
| `all` | All in-sync replicas wrote it | Slowest, safest |

With `replication factor = 1`, `acks=all` and `acks=1` are the same thing — there is only the leader.

## KRaft

Kafka historically needed **ZooKeeper** to store cluster metadata. **KRaft** mode replaces it with
Kafka's own Raft-based consensus, so a cluster is just Kafka brokers.

For a small deployment this removes an entire second distributed system from the picture: one
container instead of two, one thing to configure, one thing to keep healthy. The `apache/kafka` image
runs KRaft by default.


# Kustomize

*Referenced from [Chapter 9.2 — The production overlay](../../09-production/2-the-production-overlay.md).*

---

## The problem

The same application in two environments differs in a handful of ways — image tags, replica counts,
resource limits, one or two extra objects. Three bad answers:

- **Copy the manifests per environment.** They diverge within a month, and a fix applied to one is
  forgotten in the other.
- **Edit before applying.** Not reproducible, not reviewable, not in version control.
- **Templating** (Helm). Works, and introduces a template language between the reader and the YAML.

Kustomize takes a fourth: **keep plain YAML, and describe the differences as patches.** Base manifests
stay valid, applyable Kubernetes objects. An overlay says what to change.

It is built into `kubectl` (`kubectl apply -k`), so there is nothing to install.

## Structure

```
kubernetes/
├── 00-namespace.yaml         base — plain, valid, applyable on its own
├── 04-order-service.yaml
└── production/
    ├── kustomization.yaml    the overlay
    ├── ingress.yaml          an object only production has
    └── patch-tuning.yaml     changes to base objects
```

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - ../00-namespace.yaml
  - ../04-order-service.yaml
  - ingress.yaml            # production-only

patches:
  - path: patch-tuning.yaml
```

Note what `resources` implies: an overlay **enumerates** what it includes. Omitting a base file is how
you exclude an object — which is a deliberate, reviewable act rather than a deletion.

## Two patch styles

### Strategic merge

A partial object, merged by field:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
spec:
  template:
    spec:
      containers:
        - name: order-service         # the merge key
          env:
            - name: JAVA_TOOL_OPTIONS
              value: "-XX:MaxRAMPercentage=60"
```

Readable, and it looks like the thing it modifies. The catch is **lists**: Kubernetes list merging
uses a merge key (usually `name`), and behavior differs by field — some lists merge by key, others
replace wholesale. Adding is reliable; *removing* is where it gets subtle.

### JSON 6902

Explicit operations against paths:

```yaml
- target:
    version: v1
    kind: Service
    name: order-service
  patch: |-
    - op: replace
      path: /spec/type
      value: ClusterIP
    - op: remove
      path: /spec/ports/0/nodePort
```

Verbose, and unambiguous. `op: remove` **deletes a field**, which strategic merge cannot reliably do.

**Rule of thumb:** strategic merge to add or change; JSON 6902 to remove, or when the merge semantics
of a list are not obvious.

## Built-in transformers

Common changes have first-class support rather than needing patches:

```yaml
images:
  - name: order-service                                   # matches the base image name
    newName: ghcr.io/owner/project/order-service
    newTag: latest

namespace: orderfulfillment
namePrefix: staging-
commonLabels:
  environment: production

replicas:
  - name: order-service
    count: 3
```

`images` is the one that earns its keep: base manifests can carry a local tag and stay renderable
without a registry, while the overlay supplies the real reference.

## Composing overlays

An overlay's `resources` can point at another overlay:

```
production/
├── common/          shared production config
├── ghcr/            common + real registry images
└── local-verify/    common + local image tags
```

```yaml
# ghcr/kustomization.yaml
resources:
  - ../common
images:
  - name: order-service
    newName: ghcr.io/owner/project/order-service
```

This separates **design decisions** (what production configuration is) from **environment facts**
(where the images live) — so the design half can be rendered and reviewed with no registry access.

## Working with it

```bash
kubectl kustomize <dir>          # render to stdout — inspect before applying
kubectl apply -k <dir>           # render and apply
kubectl diff -k <dir>            # what would change
```

**Always render before applying.** Kustomize output is plain YAML, so `kubectl kustomize` shows
exactly what the cluster will receive. A patch that silently matched nothing is visible here and
invisible in the source.

## Kustomize vs. Helm

| | Kustomize | Helm |
|---|---|---|
| Mechanism | Patch plain YAML | Render templates |
| Base validity | Base is applyable as-is | Templates are not valid YAML alone |
| Learning curve | Small | A template language plus a values schema |
| Distribution | Not really | Charts, repositories, versioning |
| Release management | None — `kubectl` does it | Install/upgrade/rollback, release history |

**Kustomize** suits your own application in a few environments, where you want the YAML to stay
readable. **Helm** suits software distributed to others, or configuration with real conditional
logic.

They compose — a Helm chart's rendered output can be patched with Kustomize — though needing both is
usually a signal that one of them is doing something it should not.


# Kubernetes: the object model

*Referenced from [Chapter 7.2 — Kubernetes manifests](../../07-containers-and-kubernetes/2-kubernetes-manifests.md).*

---

## The mental model

Kubernetes is a **reconciliation loop**. You declare the state you want; controllers continuously
compare that against reality and act to close the gap.

You never say "start a container." You say "there should be three of these," and something makes it so
— and keeps making it so when a node dies, a process crashes, or someone deletes a pod by hand.

Everything follows from that. `kubectl apply` records intent. Whether reality matches yet is a separate
question, which is what `kubectl get` and `kubectl describe` answer.

## The objects you actually need

### Pod

One or more containers that share a network namespace and can share volumes. Containers in a pod reach
each other on `localhost` and are always scheduled together.

**You rarely create pods directly.** A bare pod that dies stays dead. It is the unit of scheduling, not
the unit you manage.

### Deployment

Declares "N replicas of this pod template," and owns the rollout logic for changing it.

```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  replicas: 1
  selector:
    matchLabels:
      app: order-service        # must match the template's labels
  template:
    metadata:
      labels:
        app: order-service
    spec:
      containers:
        - name: order-service
          image: order-service:local
```

Change the template and the Deployment performs a **rolling update**: new pods up, old pods down,
governed by `maxSurge` (how many extra may exist) and `maxUnavailable` (how many may be missing).

The `selector` matching the template's labels is not redundancy — the selector is how the Deployment
finds pods it owns, and a mismatch is rejected.

**Other workload types**, briefly: `StatefulSet` for stable identities and per-replica storage;
`DaemonSet` for one pod per node; `Job`/`CronJob` for run-to-completion work.

### Service

A stable name and virtual IP in front of a changing set of pods. Pods are ephemeral and get new IPs;
a Service does not.

```yaml
apiVersion: v1
kind: Service
spec:
  selector:
    app: order-service        # any pod with this label
  ports:
    - port: 8081
      targetPort: 8081
```

The selector is evaluated **continuously**. A pod that becomes ready is added to the endpoint list; one
that fails readiness is removed. That is the mechanism behind zero-downtime rollouts.

Within the cluster, `http://order-service:8081` resolves via DNS from any namespace-local pod.

**Types:**

| Type | Reachable from | Use |
|---|---|---|
| `ClusterIP` (default) | Inside the cluster only | Service-to-service |
| `NodePort` | Every node, on a high port (30000–32767) | Local development, simple demos |
| `LoadBalancer` | Externally, via a cloud load balancer | Cloud production |
| `Ingress` (separate object) | Externally, HTTP-aware routing | Anything needing paths, hosts, TLS |

`NodePort` is the blunt one — it opens the same port on **every** node, which is a real security
consideration if those nodes are internet-facing.

### ConfigMap and Secret

Non-secret and secret key/value configuration, injected as environment variables or files.

```yaml
envFrom:
  - configMapRef:
      name: order-service-config
env:
  - name: SPRING_DATASOURCE_PASSWORD
    valueFrom:
      secretKeyRef:
        name: postgres-credentials
        key: POSTGRES_PASSWORD
```

**Secrets are base64-encoded, not encrypted.** `echo <value> | base64 -d` is the entire attack. They
are separate from ConfigMaps so that access can be restricted by RBAC and so they are not printed by
casual `kubectl get -o yaml` habits — not because the encoding protects anything.

**A committed Secret manifest is a committed credential.** Real options: create it imperatively
(`kubectl create secret`) outside version control, use an external store (Vault, cloud secret
managers, External Secrets Operator), or encrypt it in git with SOPS or Sealed Secrets.

**Changing a ConfigMap does not restart pods.** Environment variables are read at container start.
Either roll the Deployment explicitly, or mount the ConfigMap as a volume (which does update, on a
delay) and have the application watch the file.

### Namespace

A scope for names and a boundary for quotas and RBAC. Not a security boundary by itself — pods in
different namespaces can still reach each other unless a `NetworkPolicy` says otherwise.

## Resource requests and limits

```yaml
resources:
  requests:
    cpu: 150m           # 0.15 of a core
    memory: 320Mi
  limits:
    cpu: 500m
    memory: 640Mi
```

**Requests are for scheduling.** The scheduler places a pod on a node with that much *unallocated*
request. It is a reservation, not a measurement.

**Limits are enforced at runtime**, and the two resources behave completely differently:

- **CPU is compressible.** Exceed the limit and you are *throttled* — slowed, not killed. A CPU limit
  that is too low shows up as latency, not failure, which makes it hard to spot.
- **Memory is not.** Exceed the limit and the container is **OOM-killed** and restarted. There is no
  degraded mode.

That asymmetry is the single most useful thing to know here. It is also why a JVM in a container needs
its heap capped explicitly relative to the memory limit — a JVM that sizes its heap from the container
limit and then also allocates metaspace, thread stacks, and direct buffers on top will exceed it.

**Requests without limits** is a legitimate strategy for latency-sensitive workloads: guaranteed a
share, free to burst. **Limits without requests** is almost always wrong.

## Reading the state

```bash
kubectl get pods -n <namespace>            # is it running and ready?
kubectl describe pod <name>                # events: why it isn't
kubectl logs <name> [-f] [--previous]      # --previous = the container before the last restart
kubectl get events --sort-by=.lastTimestamp
```

`kubectl describe` is the one to reach for first. The **Events** section at the bottom is where the
scheduler, kubelet, and controllers explain themselves — failed image pulls, probe failures,
insufficient resources, OOM kills.

`--previous` on logs is what you need after a `CrashLoopBackOff`: the current container has barely
started, and the one that died holds the answer.


# Kubernetes: health probes

*Referenced from [Chapter 7.3 — Probes and resources](../../07-containers-and-kubernetes/3-probes-and-resources.md).*

Three probes, asking three different questions. Getting them confused causes restart loops and outages
in roughly equal measure.

---

## The three questions

| Probe | Question | Failure means |
|---|---|---|
| **Startup** | Has it finished booting? | Keep waiting; suppress the other probes |
| **Readiness** | Should traffic go here **right now**? | Remove from Service endpoints — **no restart** |
| **Liveness** | Is this broken beyond recovery? | **Kill and restart the container** |

The distinction that matters most is readiness versus liveness, and the test for it is simple:

> **Would restarting this container fix the problem?**

If yes — a deadlock, an unrecoverable internal state, a wedged thread pool — it is a liveness concern.
If no — a dependency is down, a cache is warming, a queue is backed up — it is a **readiness** concern.

## Why the distinction is not academic

Consider a service whose database is briefly unreachable, with the dependency check wired into
**liveness**:

1. The database blips. Liveness fails.
2. Kubernetes restarts every pod.
3. The restarted pods still cannot reach the database. Liveness fails again.
4. `CrashLoopBackOff`. Now the service is down *and* thrashing, and when the database returns, every
   pod is in a backoff window.

**Restarting a healthy pod does not fix a sick dependency.** It converts a partial outage into a total
one, plus a restart storm at exactly the moment the dependency is recovering.

Wired into **readiness** instead: pods leave the Service endpoints, traffic stops being routed to them,
nothing restarts, and when the database returns they become ready again on the next probe. The
degradation is proportional to the fault.

**The safe default: liveness checks only the process itself. Readiness checks the dependencies.**

## Configuration

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8081
  initialDelaySeconds: 30
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 6

livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081
  initialDelaySeconds: 45
  periodSeconds: 15
  timeoutSeconds: 3
  failureThreshold: 6
```

| Field | Meaning |
|---|---|
| `initialDelaySeconds` | Wait this long after container start before probing at all |
| `periodSeconds` | How often |
| `timeoutSeconds` | How long one probe may take before counting as failed |
| `failureThreshold` | Consecutive failures before acting |
| `successThreshold` | Consecutive successes to recover (liveness must be 1) |

Time to action is roughly `initialDelay + (period × failureThreshold)`.

**Liveness should be slower and more forgiving than readiness**, in every dimension. Readiness is
cheap to get wrong — a pod briefly leaves the load balancer. Liveness is expensive — a restart, a lost
in-flight request, a cold JVM. Make it require more evidence.

**A too-short `initialDelaySeconds` on liveness is the classic self-inflicted crash loop.** A JVM that
takes 40 seconds to start, probed from second 10 with a threshold of 3, is killed at second 25 and
never starts successfully. It looks like the application is broken.

## Probe types

**`httpGet`** — a 2xx or 3xx passes. The usual choice.

**`tcpSocket`** — can a connection be opened? Cheap, and the right answer for non-HTTP services or for
anything where a heavier check is itself a problem.

**`exec`** — run a command in the container; exit 0 passes. The most flexible and the most expensive:
it forks a process on **every probe, on every pod, forever**. An `exec` probe that starts a JVM is a
constant CPU cost that will find you on a small node.

## Startup probes

For applications with a slow but bounded start, a startup probe is better than a long
`initialDelaySeconds` on liveness:

```yaml
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: 8081 }
  failureThreshold: 30
  periodSeconds: 5          # allows up to 150s to start
```

While a startup probe is failing, readiness and liveness are **not evaluated at all**. Once it
succeeds, it never runs again and the others take over.

This decouples "may take a while to boot" from "must respond quickly once running" — so you get a
generous startup budget *and* a tight liveness check afterwards, instead of trading one for the other.

## Spring Boot specifics

Actuator provides the two endpoints directly:

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true      # /actuator/health/liveness and /actuator/health/readiness
```

By default, **readiness reflects only Spring's own `readinessState`** — application-context lifecycle,
not your dependencies. Health indicators that Spring auto-registers (`db`, `diskSpace`, …) appear in
`/actuator/health` but not in the readiness group unless you add them:

```yaml
management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState,db
```

Two things to check rather than assume:

**Which indicators actually exist.** `GET /actuator/health` with details enabled lists them. Not every
dependency has one — Spring Kafka, for instance, does not register a broker health indicator by
default, so a readiness group cannot include what is not there.

**That the group means what you think.** Adding an indicator to readiness makes that dependency's
failure pull the pod out of rotation. That is usually right for a database and usually wrong for a
non-critical downstream service, whose outage should degrade a feature rather than remove the pod.

## The costs

**Every probe is real traffic.** Five pods × two probes × every few seconds is a constant load floor
that shows up on a small node.

**Probes can lie under load.** A pod that is merely slow will fail `timeoutSeconds` and be restarted —
and the restart removes capacity, making everything else slower. Liveness probes are a genuine
amplifier of load-induced failure, which is the strongest argument for keeping them minimal.

**Readiness gates rollouts.** A rolling update waits for new pods to become ready. Readiness that is
too strict makes a deploy hang; readiness that is too loose sends traffic to a pod that cannot serve
it.


# Maven multi-module builds

*Referenced from [Chapter 2.1 — The project skeleton](../../02-domain/1-project-skeleton.md).*

---

## The problem

One repository, several independently deployable applications, and real code shared between them.
Copying the shared code produces divergent copies within a month; a separate repository for it means
version-bumping and re-releasing for every change.

## The aggregator POM

A `pom.xml` with `<packaging>pom</packaging>` builds no artifact of its own. It lists modules:

```xml
<groupId>com.orderfulfillment</groupId>
<artifactId>order-fulfillment-systems-lab</artifactId>
<version>0.1.0</version>
<packaging>pom</packaging>

<modules>
    <module>services/common</module>
    <module>services/order-service</module>
    <module>services/inventory-service</module>
</modules>
```

`mvn install` at the root builds every module **in dependency order** — Maven computes the order from
inter-module dependencies, not from the listing order. Running it inside one module's directory builds
only that module, resolving its siblings from your local repository.

A module depends on a sibling by ordinary coordinates:

```xml
<dependency>
    <groupId>com.orderfulfillment</groupId>
    <artifactId>common</artifactId>
    <version>${project.version}</version>
</dependency>
```

`${project.version}` is inherited from the parent, so there is one version number for the whole tree
and no per-module bumping.

## Two distinct jobs, often confused

A parent POM does two things that are worth keeping separate in your head:

**`<modules>` — aggregation.** What gets built together.

**`<dependencyManagement>` — version alignment.** *If* a child declares this dependency, use this
version. It does **not** add the dependency. A child still declares what it uses; it just omits the
`<version>`.

These are independent. A POM can aggregate without managing versions, or manage versions for projects
it does not aggregate (which is what a published BOM is).

## Inheriting from `spring-boot-starter-parent`

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
</parent>
```

`spring-boot-starter-parent` carries a `dependencyManagement` section pinning consistent versions for
hundreds of libraries that Spring's own compatibility testing has exercised together.

This is why declaring `spring-boot-starter-web` **without a `<version>`** is correct rather than
sloppy — omitting it is how you opt into the tested set. Pinning your own version there is how you
opt out of it, usually without meaning to.

`<relativePath/>` (deliberately empty) tells Maven not to look for the parent on disk and to resolve
it from the repository instead. Your own child modules do the opposite:

```xml
<relativePath>../../pom.xml</relativePath>
```

## BOMs

A **Bill of Materials** is a POM that exists only to pin versions for a family of related artifacts.
Import one with `<scope>import</scope>`:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.21.4</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

Now every `org.testcontainers:*` dependency in every module gets a consistent version from one place.

**A wrinkle worth knowing:** an imported BOM's entries do not always propagate reliably through more
than one parent hop across all tooling. The practical workaround is for each module to also declare
the dependency it uses directly (still without a version) — the import in the root still keeps the
versions from drifting, which is the point.

## Packaging: which modules produce runnable JARs

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

`spring-boot-maven-plugin` repackages the module's JAR into an executable fat JAR with a nested
classpath and a launcher.

**A library module must not have this plugin.** A Spring Boot fat JAR has a nonstandard internal
layout and cannot be depended on as an ordinary library. Application modules get the plugin; shared
modules do not.

## What belongs in a shared module

This is the decision that determines whether a shared module helps or quietly recreates the coupling
your service boundaries exist to prevent.

**In:** wire contracts expressed as code (message envelopes, payload types), and infrastructure with
no domain opinion (serialization, error models, correlation-ID plumbing, ID generation).

**Out:** anything domain-specific. If two services share a domain type, they are not really separate
services — a change to that type is a change to both, which is exactly the property a boundary is
supposed to remove.


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


# React: components, state, and hooks

*Referenced from [Chapter 2.6 — The first frontend](../../02-domain/6-the-first-frontend.md).*

---

## The model

A React component is a function that takes **props** and returns a description of UI. React calls it,
compares the result against what is currently on screen, and applies the minimal DOM changes.

```tsx
interface Props {
  onSelectOrder: (orderId: string) => void;
}

export function OrdersListPage({ onSelectOrder }: Props) {
  return <button onClick={() => onSelectOrder('order-1')}>Open</button>;
}
```

The mental model that matters: **you describe what the UI should look like for a given state, and
React works out the DOM operations.** You never write "find that row and update its text."

**JSX** is syntax sugar for function calls — `<div className="x">hi</div>` compiles to
`React.createElement('div', { className: 'x' }, 'hi')`. Hence `className` rather than `class` (a
reserved word) and `htmlFor` rather than `for`.

**Rendering must be pure.** Given the same props and state, a component must return the same thing and
must not mutate anything outside itself. React may call it more than once per visible update — in
development, `StrictMode` deliberately double-invokes components to surface impure ones.

## State

`useState` gives a component a value that survives across renders, and a setter that triggers a
re-render:

```tsx
const [customerId, setCustomerId] = useState('demo-customer');
const [lines, setLines] = useState<LineDraft[]>([{ sku: '', quantity: 1 }]);
```

**Update immutably.** Never `lines.push(x)`. Create a new array or object:

```tsx
setLines((prev) => [...prev, { sku: '', quantity: 1 }]);
setLines((prev) => prev.filter((_, i) => i !== index));
setLines((prev) => prev.map((line, i) => (i === index ? { ...line, ...patch } : line)));
```

React decides whether to re-render by comparing references. Mutating in place leaves the reference
unchanged, so React concludes nothing happened and the screen does not update.

**Prefer the functional form** (`setState(prev => …)`) whenever the new value depends on the old one.
The direct form closes over the value from the render it was written in, which is stale if several
updates are queued in one tick.

## Props and lifting state up

Data flows **down** through props; changes flow **up** through callback props.

```tsx
<OrdersListPage onSelectOrder={(orderId) => navigate(`/orders/${orderId}`)} />
```

The child does not know what selecting an order means. It reports the event; the parent decides. This
keeps components reusable and is what makes them straightforward to test.

When two siblings need the same state, it moves to their nearest common parent — "lifting state up."
When that parent is many levels away, **context** (`createContext` + `useContext`) passes it without
threading props through every intermediate layer.

## Lists and keys

```tsx
{data.content.map((order) => (
  <tr key={order.id} onClick={() => onSelectOrder(order.id)}>…</tr>
))}
```

`key` tells React which element corresponds to which item across renders, so it can move rows rather
than rebuild them.

**Use a stable identity from your data — never the array index.** Index keys break the moment the list
reorders or an item is removed: React reuses the wrong DOM node, and any state inside it (an input's
value, a scroll position, focus) attaches to the wrong row.

## Effects, and why you probably want fewer

`useEffect` runs code *after* render, for synchronizing with something outside React:

```tsx
useEffect(() => {
  const unsubscribe = subscribeToStream(url, handlers, ['order-status-changed']);
  return unsubscribe;   // cleanup: runs on unmount and before the next effect
}, [url]);
```

Three parts: the effect body, an optional **cleanup function** returned from it, and the **dependency
array** controlling when it re-runs (`[]` = once on mount; omitted = every render).

**The cleanup function is not optional for anything with a lifetime.** Subscriptions, timers, event
listeners, and open connections all leak without it — and in `StrictMode` React deliberately mounts,
unmounts, and remounts components in development specifically to make a missing cleanup visible.

The most common React mistake is using effects for things that are not synchronization:

- **Fetching data** — use a query library ([TanStack Query](tanstack-query.md)). Hand-rolled fetch
  effects have to reimplement caching, deduplication, race-condition handling, and cleanup, and
  usually get the race conditions wrong.
- **Deriving values from props or state** — just compute it during render. An effect that sets state
  from other state causes a second render and can desynchronize.
- **Responding to a user action** — put it in the event handler.

## Rules of hooks

Two, and they are absolute:

1. **Only call hooks at the top level** — never inside conditions, loops, or nested functions.
2. **Only call them from components or other hooks.**

React tracks hooks by call order. A conditional hook changes that order between renders and the state
of one hook gets handed to another. The ESLint rules that enforce this are worth having on.

## Custom hooks

A function whose name starts with `use` and which calls other hooks. That is the whole mechanism — it
is how you extract stateful logic for reuse:

```tsx
function useOrderStream(orderId: string) {
  const [status, setStatus] = useState<OrderStatus | null>(null);
  useEffect(() => { /* subscribe, return cleanup */ }, [orderId]);
  return status;
}
```

No special API, no registration. Just a function that follows the naming convention so the lint rules
can check it.


# TanStack Query

*Referenced from [Chapter 2.6 — The first frontend](../../02-domain/6-the-first-frontend.md).*

---

## Server state is not client state

The insight the library is built on: **data that lives on a server is a fundamentally different thing
from data that lives in your component**, and treating them the same is why so much frontend code is
tangled.

| Client state | Server state |
|---|---|
| You own it | Someone else owns it |
| Always current | A cached copy that may already be stale |
| Synchronous | Asynchronous, and can fail |
| Only you change it | Changes without telling you |

Putting server state in `useState` + `useEffect` means hand-writing, per endpoint: loading flags,
error flags, caching, deduplication of concurrent requests, refetching, invalidation after writes, and
cleanup of responses that arrive after the component unmounted or after a newer request already
resolved. That last one is a race condition most hand-rolled implementations get wrong.

## Queries

```tsx
const { data, isLoading, isError, error } = useQuery({
  queryKey: ['orders'],
  queryFn: listOrders,
  refetchInterval: 4000,
});
```

- **`queryKey`** — the cache identity. Two components using `['orders']` share one cache entry and one
  in-flight request. The key is also what invalidation targets.
- **`queryFn`** — any function returning a promise. It knows nothing about React; it can be a plain
  `fetch` wrapper.
- The return gives you loading, error, and success states as data rather than as branches you
  maintain.

**Keys are hierarchical arrays**, and this is the part worth designing deliberately:

```tsx
['orders']                  // the list
['orders', orderId]         // one order
['orders', { status }]      // a filtered list
```

Invalidating `['orders']` invalidates everything beneath it. Getting the hierarchy right up front
makes cache management nearly free later.

## Mutations

```tsx
const queryClient = useQueryClient();

const mutation = useMutation({
  mutationFn: createOrder,
  onSuccess: (accepted) => {
    queryClient.invalidateQueries({ queryKey: ['orders'] });
    onOrderCreated(accepted.id);
  },
});

mutation.mutate({ customerId, items });
```

Mutations are for writes. They are not cached and are triggered imperatively rather than on render.

**`invalidateQueries` is the core idea.** Rather than manually patching the cache after a write, you
mark the affected keys stale and let the library refetch. The server stays the source of truth, and
you never have to reimplement its logic client-side to predict what the new list looks like.

`mutation.isPending`, `mutation.error`, and `mutation.isSuccess` cover the UI states, so a submit
button's disabled state is derived rather than tracked by hand.

## Freshness

Two settings, and the distinction between them matters:

- **`staleTime`** — how long data is considered fresh. While fresh, remounting a component uses the
  cache with **no** network request. Default `0`: everything is stale immediately.
- **`gcTime`** — how long an *unused* cache entry is kept before being discarded. Default 5 minutes.

Refetches happen on window focus, on reconnect, on remount, and on `refetchInterval` — all
configurable, globally or per query.

```tsx
const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 2000, retry: 1 } },
});
```

## Polling, and when to stop

`refetchInterval` polls. It is the right tool when the data changes without you and you have no push
channel — and the wrong tool when you *do*.

For a resource that changes rapidly and pushes updates (a live status stream), the better pattern is a
push subscription that writes into the cache directly:

```tsx
queryClient.setQueryData(['orders', orderId], (old) => ({ ...old, status: newStatus }));
```

or an `invalidateQueries` call from the push handler, which refetches once rather than every N seconds
forever. A poll left in place next to a working stream is a common and easy-to-miss waste.

## Errors

`queryFn` communicates failure by **throwing**. That is worth stating because `fetch` does not throw
on a 4xx or 5xx — it resolves with `ok: false`. A wrapper that does not check `response.ok` will hand
an error body to your success path as if it were data.

Throwing a typed error subclass lets components discriminate:

```tsx
const message = mutation.error instanceof ApiRequestError
  ? mutation.error.apiError.message     // the server's own message
  : mutation.error?.message;            // network failure, parse failure
```

`retry` defaults to 3 attempts with exponential backoff — usually right for reads, and usually wrong
for writes, which is why mutations default to no retries.


# Spring Boot: auto-configuration and component scanning

*Referenced from [Chapter 2.1 — The project skeleton](../../02-domain/1-project-skeleton.md).*

---

## Auto-configuration

Spring Boot inspects the classpath at startup and configures what it finds. PostgreSQL driver present
and a `spring.datasource.url` set? It builds a connection pool. Flyway on the classpath? It runs
migrations. Spring Kafka present with a `bootstrap-servers` value? It builds producer and consumer
factories.

This is why a `pom.xml` is worth reading as a statement of what an application *is*:

```xml
<dependency>...spring-boot-starter-web</dependency>          <!-- HTTP + JSON + embedded server -->
<dependency>...spring-boot-starter-data-jpa</dependency>     <!-- JPA/Hibernate + transactions -->
<dependency>...spring-boot-starter-validation</dependency>   <!-- Bean Validation -->
<dependency>...spring-boot-starter-actuator</dependency>     <!-- health, metrics -->
```

A **starter** is a POM with no code of its own that pulls in a curated, version-aligned set of
dependencies. `spring-boot-starter-web` brings Spring MVC, Jackson, an embedded Tomcat, and the
auto-configuration that wires them.

### The rule that makes it safe

Auto-configuration is **conditional and yields to you**. Its classes are covered in annotations like:

- `@ConditionalOnClass` — only if this type is on the classpath
- `@ConditionalOnMissingBean` — **only if you have not defined one yourself**
- `@ConditionalOnProperty` — only if this config value is set

The second is the important one. Define your own `ObjectMapper` bean and Spring Boot steps aside.
Auto-configuration is a set of defaults, not a framework taking over.

### Seeing what it did

Run with `--debug` (or `debug: true`) and Spring Boot prints a **condition evaluation report**: every
auto-configuration considered, which matched, which did not, and why. When something you expected to
be configured is not, this is the first place to look — not the documentation.

## `@SpringBootApplication`

```java
@SpringBootApplication
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

Three annotations in one:

- **`@Configuration`** — this class may itself declare `@Bean` methods.
- **`@EnableAutoConfiguration`** — do the classpath inspection described above.
- **`@ComponentScan`** — find annotated classes to manage.

### Component scanning, and the package trap

`@ComponentScan` with no arguments scans **the annotated class's own package and everything below
it**. That is why the application class conventionally sits at the root of your package tree:

```
com.orderfulfillment.order          ← OrderServiceApplication here
com.orderfulfillment.order.dto      ← scanned
com.orderfulfillment.common         ← NOT scanned
```

`com.orderfulfillment.common` is a *sibling*, not a child. Its `@Component`s are invisible to that
scan, and the symptom is a `NoSuchBeanDefinitionException` for a class that is very obviously
annotated and very obviously on the classpath.

Three ways out, in rough order of preference:

1. **`@SpringBootApplication(scanBasePackages = {"com.orderfulfillment.order", "com.orderfulfillment.common"})`**
   — explicit and obvious.
2. **Put the shared beans behind an auto-configuration** registered in
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. This is how a
   real shared library should do it: consumers get the beans by depending on the artifact, with no
   scanning configuration at all.
3. **Move the application class up** to a common ancestor package. Simple, and it drags in more than
   you meant as the tree grows.

## Configuration properties

Values come from `application.yml`, environment variables, command-line arguments, and several other
sources, in a defined precedence order (later wins): defaults → `application.yml` → profile-specific
YAML → environment variables → command-line arguments.

The `${VAR:default}` form reads an environment variable with a fallback:

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
```

**Relaxed binding** means `KAFKA_BOOTSTRAP_SERVERS`, `kafka.bootstrap-servers`, and
`kafka.bootstrapServers` are all the same property. That is what makes the environment-variable
override work without any translation layer, and it is why containerized Spring applications are
configured almost entirely through the environment.

### Typed properties

For anything beyond a value or two, bind to a record instead of scattering `@Value`:

```java
@ConfigurationProperties(prefix = "orderfulfillment.outbox")
public record OutboxProperties(int pollIntervalMs, int batchSize, int sendTimeoutMs) { }
```

One type, validated at startup, discoverable by IDE autocomplete, and testable.

## Profiles

`@ActiveProfiles("test")`, `application-production.yml`, `@Profile("!test")` on a bean. A profile
switches configuration and bean sets for an environment.

Useful, and worth using sparingly: a bean that exists in production but not in tests is a bean your
tests do not cover. Prefer configuring the same beans differently over having different beans.


# Bean Validation

*Referenced from [Chapter 2.3 — The HTTP layer](../../02-domain/3-the-http-layer.md).*

Jakarta Bean Validation (JSR 380), implemented by Hibernate Validator. Annotation-driven constraint
checking on objects.

---

## How it fits together

You annotate fields. Something walks the object and collects violations. In Spring MVC, that
"something" is triggered by `@Valid` on a handler parameter, and the violations become a
`MethodArgumentNotValidException` before your method body ever runs.

```java
public record CreateOrderRequest(
        @NotNull @Size(min = 1, max = 64) String customerId,
        @NotEmpty @Size(min = 1, max = 20) @Valid List<CreateOrderItem> items
) { }

public record CreateOrderItem(
        @NotNull @Pattern(regexp = "^SKU-[0-9]{3}$") String sku,
        @NotNull @Min(1) @Max(100) Integer quantity
) { }
```

## The constraints you'll actually use

| Annotation | Applies to | Checks |
|---|---|---|
| `@NotNull` | anything | not null |
| `@NotEmpty` | String, Collection, Map, array | not null **and** not empty |
| `@NotBlank` | String | not null and contains non-whitespace |
| `@Size(min, max)` | String, Collection, Map, array | length or size in range |
| `@Min` / `@Max` | numbers | value in range |
| `@Positive` / `@PositiveOrZero` | numbers | sign |
| `@Pattern(regexp)` | String | matches |
| `@Email` | String | plausible email shape |
| `@Past` / `@Future` | temporal types | relative to now |
| `@Valid` | nested object or collection | **recurse into it** |

## Four things that catch people out

### `@Valid` on a collection is what makes it recurse

```java
@NotEmpty @Size(min = 1, max = 20) @Valid List<CreateOrderItem> items
```

Without `@Valid`, the list is checked for emptiness and size, and **the constraints on each element
are never evaluated**. Nested validation is opt-in. This is a quiet bug: the endpoint appears
validated, and element-level rules do nothing.

### Use boxed types, not primitives

```java
@NotNull @Min(1) Integer quantity     // good
@Min(1) int quantity                  // subtly wrong
```

A primitive `int` cannot be null, so an absent field silently becomes `0` and then fails `@Min(1)`
with a message about the value being too small rather than about the field being missing. Boxing lets
`@NotNull` distinguish "absent" from "zero."

### Bound everything that comes from outside

Every string and every collection from an untrusted source needs an upper bound — `max = 64`,
`max = 20`, `@Max(100)`. Not because the specific number is meaningful, but because an unbounded
input is a resource-consumption problem. A request with 500,000 items would otherwise be dutifully
parsed, priced, and inserted.

### Validate shape here, business rules elsewhere

`@Pattern(regexp = "^SKU-[0-9]{3}$")` checks that a SKU is *shaped* like a SKU. Whether `SKU-999` is
a real product is a different question, requiring domain data, and it belongs in the service layer —
throwing a domain exception with its own error code, not a validation annotation.

The same goes for cross-field invariants ("a SKU may appear at most once per order"). Bean Validation
can express these with a custom class-level constraint, but a plain check in the service is usually
clearer and keeps the rule where the rule lives.

## Reporting violations

`MethodArgumentNotValidException` carries a `BindingResult` with every field error. Two reasonable
strategies:

```java
// One error, simple
String message = ex.getBindingResult().getFieldErrors().stream()
        .findFirst()
        .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
        .orElse("Validation failed");

// All errors, better for form-driven clients
List<FieldError> all = ex.getBindingResult().getFieldErrors();
```

Reporting only the first is a legitimate simplification for an API whose clients submit one field at
a time; a form UI generally wants all of them so it can mark every bad field at once. Pick
deliberately — it is part of your API contract either way.

## Validating outside controllers

`@Valid` on a controller parameter is Spring MVC's integration. To validate elsewhere:

- **`@Validated` on a `@Service` class** plus constraints on method parameters — Spring proxies the
  bean and validates on call, throwing `ConstraintViolationException`.
- **Inject a `Validator`** and call `validator.validate(obj)` for full manual control.

Note the different exception type: `ConstraintViolationException`, not
`MethodArgumentNotValidException`. If you validate in the service layer, your exception handler needs
a case for it, or those failures become 500s.


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


# Spring: dependency injection and stereotypes

*Referenced from [Chapter 2.1 — The project skeleton](../../02-domain/1-project-skeleton.md).*

---

## The idea

A class declares what it needs and never constructs it. Something else builds the object graph.

```java
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final SkuPriceCatalog priceCatalog;

    public OrderService(OrderRepository orderRepository, SkuPriceCatalog priceCatalog) {
        this.orderRepository = orderRepository;
        this.priceCatalog = priceCatalog;
    }
}
```

Nothing anywhere calls `new OrderService(...)`. At startup Spring sees the class is a `@Service`,
inspects its constructor, finds a **bean** for each parameter type, and wires it. A *bean* is just an
object Spring manages.

## Why it's worth the indirection

**Testability.** A test can construct `OrderService` with a stub repository. If the class constructed
its own dependencies, the only way to test it would be to make the real ones work.

**One place decides wiring.** Swapping an implementation is a configuration change, not an edit
scattered across every call site.

**Lifecycle management.** Beans are singletons by default, created once at startup, with a defined
initialization and shutdown order — which matters for connection pools, listener containers, and
anything that needs to close cleanly.

The cost, and it is real: **a lot happens that you did not write**. A failure at startup is a failure
in a graph you never drew. Getting comfortable reading Spring's startup errors is part of the job.

## Constructor injection, always

Three forms exist. Only one is a good idea.

```java
// Constructor injection — use this
private final OrderRepository repository;
public OrderService(OrderRepository repository) { this.repository = repository; }

// Field injection — avoid
@Autowired private OrderRepository repository;

// Setter injection — rarely justified
@Autowired public void setRepository(OrderRepository r) { this.repository = r; }
```

Constructor injection wins on four counts:

- **Fields can be `final`.** The object is immutable and complete once constructed.
- **The object is never in an invalid state.** Field injection produces an instance whose fields are
  null until Spring finishes with it.
- **It can be constructed in a plain unit test**, with no Spring at all.
- **It makes bloat visible.** A constructor with nine parameters is uncomfortable to look at, and it
  should be — that class is doing too much. Field injection hides the same problem behind nine tidy
  annotations.

Since Spring 4.3, **a class with a single constructor needs no `@Autowired`**. The constructor is
implicitly the injection point. Most modern Spring code has no `@Autowired` in it at all.

## Stereotypes

`@Component` marks a class as something Spring should manage. The rest are specializations —
functionally near-identical, but they document intent and some add behavior:

| Annotation | Use for | Extra behavior |
|---|---|---|
| `@Component` | Anything that doesn't fit below | — |
| `@Service` | Business logic | None; purely intent |
| `@Repository` | Data access | Translates persistence exceptions into Spring's `DataAccessException` hierarchy |
| `@RestController` | HTTP endpoints | `@Controller` + `@ResponseBody` |
| `@Configuration` | Bean definitions via `@Bean` methods | Proxies the class so `@Bean` methods return the singleton on repeat calls |

Spring Data repositories are the exception to all of this: `OrderRepository` is an **interface** with
no annotation and no implementation. Spring Data generates the implementation at startup. See
[Spring Data repositories](data-repositories.md).

## `@Bean` methods, for objects you don't own

Stereotypes need a class you can annotate. For third-party types, declare them in a
`@Configuration` class:

```java
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic ordersEventsTopic() {
        return TopicBuilder.name("orders.events").partitions(3).replicas(1).build();
    }
}
```

The method name becomes the bean name, and the return value becomes the bean. Method parameters are
themselves injected, so `@Bean` methods can depend on other beans.

## Scopes and the one that bites

Beans are **singletons** by default — one instance for the whole application, shared by every
injector.

The consequence people trip over: **a singleton must be thread-safe.** Two HTTP requests handled on
different threads share the same `OrderService` instance. Mutable instance state on a Spring bean is
shared mutable state across concurrent requests. The usual answer is not to have any: keep beans
stateless, and pass per-request data as method arguments.

Where per-request state is genuinely needed, Spring has `@Scope("request")` and `@Scope("prototype")`
— but reach for them knowing why the default did not work, because both come with their own
surprises when injected into a singleton.

## Common startup failures

| Message | Usual cause |
|---|---|
| `NoSuchBeanDefinitionException` | The class isn't annotated, or isn't inside the component-scanned package tree. See [auto-configuration](auto-configuration.md). |
| `NoUniqueBeanDefinitionException` | Two beans match one type. Use `@Qualifier`, or mark one `@Primary`. |
| `BeanCurrentlyInCreationException` | A circular dependency (A needs B needs A). Almost always a design problem; extract the shared part into a third bean. |


# Spring MVC: controllers and exception handling

*Referenced from [Chapter 2.3 — The HTTP layer](../../02-domain/3-the-http-layer.md).*

---

## Controllers

`@RestController` = `@Controller` + `@ResponseBody`. Every handler method's return value is
serialized into the response body — JSON, via Jackson — rather than resolved as a view name.

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @PostMapping
    public ResponseEntity<OrderAccepted> createOrder(@Valid @RequestBody CreateOrderRequest request) { ... }

    @GetMapping("/{orderId}")
    public OrderDetail getOrder(@PathVariable String orderId) { ... }

    @GetMapping
    public OrderPage listOrders(@RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "0") int page) { ... }
}
```

| Annotation | Binds |
|---|---|
| `@RequestMapping` on the class | A path prefix every method inherits |
| `@GetMapping` / `@PostMapping` / … | Method + path |
| `@RequestBody` | The deserialized request body |
| `@PathVariable` | A `{placeholder}` path segment |
| `@RequestParam` | A query parameter (`required`, `defaultValue`) |
| `@RequestHeader` | A header value |
| `@Valid` | Triggers Bean Validation before the method body runs |

### Path matching precedence

Literal segments beat variables, regardless of declaration order. `/api/orders/stream` resolves to a
`@GetMapping("/stream")` even if `@GetMapping("/{orderId}")` is declared first. Relying on that is
correct but not obvious, so declaring the literal route first anyway is worth doing as documentation.

### Return types

Return the DTO directly for a plain `200 OK`. Return `ResponseEntity<T>` when you need to control the
status or headers:

```java
return ResponseEntity.created(URI.create("/api/orders/" + id)).body(accepted);
```

`201 Created` plus a `Location` header. Also available: `ResponseEntity.noContent()` (204),
`.accepted()` (202), `.status(...)` for anything else.

**`202 Accepted` deserves a mention.** It means "I have taken this and will process it; the outcome is
not known yet" — literally the semantics of an asynchronous workflow. `201 Created` is the right
choice when a resource genuinely now exists to be fetched, which is why this project uses it; `202` is
the right choice when it does not.

### What belongs in a controller

Bind input, call one service method, shape the response. **No business logic.**

Not a style rule. Logic in a controller is reachable only from an HTTP request — it cannot be reused
by a message consumer or a scheduled job, and it can only be tested by standing up a web layer.

---

## Exception handling

### The problem

Errors reach a client from places that know nothing about each other: your code throwing
deliberately; the validation layer; Jackson failing to parse a body; the dispatcher finding no
handler; anything throwing unexpectedly. Left alone, each produces a differently-shaped response, and
some leak stack traces.

### `@RestControllerAdvice`

Registers exception handlers across every controller in the application.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }
}
```

Handlers are matched **most-specific-first**, so a catch-all `@ExceptionHandler(Exception.class)`
coexists safely with narrower ones.

### The exceptions worth handling explicitly

Everything not handled falls through to the catch-all and is reported as **500** — which is wrong for
several very common cases. These four are the ones most projects miss:

| Exception | Thrown when | Correct status |
|---|---|---|
| `MethodArgumentNotValidException` | `@Valid` fails | **400** |
| `HttpMessageNotReadableException` | The body is malformed JSON | **400** |
| `NoResourceFoundException` | No handler matches the path | **404** |
| `HttpRequestMethodNotSupportedException` | Path exists, wrong HTTP method | **405** |

The last two are the sneaky ones. A request for a URL that does not exist is not a server error, and
neither is a `POST` to a `GET`-only route. Reported as 500s they also get logged at `ERROR`, which
turns ordinary client mistakes into noise in the logs you would use to find real failures.

### The catch-all, done correctly

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request);
}
```

Two halves, both required:

- **Tell the client nothing.** No exception type, no message, no stack trace — those describe your
  internals and are an information-disclosure risk.
- **Log everything**, at `ERROR`, with the exception passed as the last argument so the stack trace
  is attached.

Getting the second half wrong produces the worst possible outcome: a 500 that appears in no log. The
client knows something broke and you have no way to find out what.

### A handler that returns `void`

Occasionally the right response is *no response at all*:

```java
@ExceptionHandler(AsyncRequestNotUsableException.class)
public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex, HttpServletRequest request) {
    log.debug("Dropping unusable async request — client connection is already gone", ex);
}
```

A `void` return tells Spring MVC the response is fully handled and it should write nothing. This is
the correct shape when the connection is already broken, or when the response is already committed
with a `Content-Type` no error body can be written into — a long-lived `text/event-stream`, for
instance. Attempting to write a JSON error onto a committed SSE response fails a second time, on top
of the original failure.

---

## Content negotiation and Jackson

`spring-boot-starter-web` configures Jackson for JSON automatically. Worth knowing:

- **Java records serialize cleanly** — component names become field names. This is why records make
  such good DTOs.
- **`Instant` serializes as ISO-8601** when `jackson-datatype-jsr310` is present, which the starter
  includes. Without it you get an epoch number, which is a common and ugly surprise.
- **Unknown fields on deserialization** fail by default. Turning that off
  (`fail-on-unknown-properties: false`) is what lets a consumer tolerate a message written by a newer
  producer — an explicit requirement of this project's event versioning rule.


<hr style="page-break-after: always;"/>

