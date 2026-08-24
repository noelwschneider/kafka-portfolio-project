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
