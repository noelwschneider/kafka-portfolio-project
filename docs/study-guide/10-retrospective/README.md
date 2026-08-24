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
