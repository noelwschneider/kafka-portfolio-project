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
