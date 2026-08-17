# ADR-001: Use Kafka for asynchronous order lifecycle events

- **Status:** Accepted. Implemented in Phase 2 (in-process), distributed in Phase 3.
- **Date:** 2026-08-17 (Phase 0)

## Context

An order passes through inventory reservation, payment authorization, and shipment creation. Each
step is owned by a different service, each can fail independently, and one of them (payment) can fail
in a way that requires undoing an earlier step.

The project's purpose constrains the choice more than the domain does. Per
`docs/planning/project-overview.md`, this is a portfolio system whose product *is* the
distributed-systems sandbox: it has to make partition-level ordering, consumer groups, offsets,
replay after an outage, duplicate delivery, and dead-letter handling visible and reproducible. A
four-step workflow over four demo SKUs does not need a distributed log on its own merits. What needs
it is the set of behaviors the project exists to demonstrate.

A second constraint comes from the API design: `POST /api/orders` must return once the order is
accepted, not once it is fulfilled. Something has to carry the remaining work after the response is
sent.

## Decision

Use Apache Kafka (the `apache/kafka` image in KRaft mode, per
`docs/planning/project-overview.md`'s Pinned Technology Decisions table) as the transport for all
order lifecycle transitions between services.

- Domain-oriented topics, one per publishing service, plus one DLQ per consumer domain
  (`docs/events/event-catalog.md` §2).
- `orderId` as the record key on every topic, so an order's events keep their relative order within
  a partition.
- At-least-once delivery, with consumers made idempotent (ADR-005).
- No synchronous service-to-service REST call anywhere in the order workflow. The one place services
  do call each other over HTTP is the demo control plane (ADR-002), which is not workflow.

## Alternatives considered

**Synchronous REST orchestration.** Order Service calls Inventory, then Payment, then Fulfillment,
and returns when done. Simpler to write, simpler to debug, and genuinely the right answer for a
system of this size — which is why `docs/planning/portfolio-plan.md`'s interview checklist expects
the developer to be able to say when REST would be preferable. Rejected because it couples the
order's availability to every downstream service's availability, makes the HTTP request as slow as
the slowest step, and demonstrates none of the behaviors this project is built to show. An
inventory-service restart would fail live order creation instead of delaying it.

**RabbitMQ or another traditional broker.** Adequate for the workflow, and lighter to operate. But
messages are consumed off a queue rather than retained in a log, so an offline consumer's backlog
and the replay-after-recovery behavior in Scenario 5 are much weaker demonstrations, and offsets —
one of the concepts the project wants to teach — don't exist in the same form. Rejected for that
reason, not on throughput grounds; throughput is irrelevant at this scale.

**Database-backed job queue** (a `pending_work` table with pollers). No new infrastructure, and
transactional with the business write — which would have removed the dual-write problem ADR-006
exists to address. Rejected because it demonstrates nothing about messaging, and because polling
latency and lock contention become the interesting problems instead of the ones the project is
about.

**Event sourcing / CQRS.** Explicitly a non-goal in `docs/planning/project-overview.md`. Events here
are notifications between services, not the system of record; each service keeps its own state in
its own tables.

## Consequences and tradeoffs

**Accepted costs.**

- Every read-your-writes expectation is gone. `POST /api/orders` returns `PENDING`, and the UI must
  be built to observe status changes afterwards — which is why SSE exists (ADR-003).
- Kafka must run for the workflow to progress at all. Local development needs Docker Compose from
  Phase 7 onward, and Kafka's own operational complexity exceeds every application service's.
- At-least-once delivery makes duplicate handling mandatory rather than optional (ADR-005). A
  consumer that is not idempotent is a latent double-charge.
- Publishing after commit introduces a window where a database change exists with no event
  (ADR-006).
- Debugging spans process boundaries, which is why correlation IDs are a required envelope field
  rather than a nice-to-have.

**What it buys.**

- Services fail independently: an inventory outage delays orders instead of rejecting them, and the
  backlog drains on recovery.
- `PaymentAuthorized` is consumed by two services in different consumer groups for different
  reasons, with neither aware of the other — fan-out that costs nothing to add.
- Every scenario in `docs/scenarios.md` from 4 through 8 becomes demonstrable with real
  infrastructure behavior rather than simulation.

**Honest limits.** This project provides at-least-once delivery with idempotent consumers, and
per-partition ordering for a single order's events. It does not provide exactly-once semantics, and
no document, UI string, or README may claim it does
(`docs/planning/agent-guidance.md` rule 18). Cross-order ordering is not guaranteed and must not be
relied on.
