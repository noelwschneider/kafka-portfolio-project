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
