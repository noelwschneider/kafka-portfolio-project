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
