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
