# Sprint 6 Plan

- **Input:** the Tier 2 frontend/ops backlog accumulated since Sprint 4, filtered against a single
  question — what does a recruiter or interviewer actually see in a short visit to the live site.
- **Theme:** recruiter-facing polish and demo reliability. Not new features and not the deep backend
  correctness work of Sprint 5 — visible gaps and small defects on the deployed site, plus the one
  deployment-pipeline gap that could silently prevent this sprint's own frontend work from ever going
  live.

## Goals

1. **[#37](https://github.com/noelwschneider/kafka-portfolio-project/issues/37) `redeploy.sh` doesn't
   restart the frontend Deployment** — `infrastructure/kubernetes/production/redeploy.sh`'s
   `DEPLOYMENTS` list covers only the five backend services. If the frontend image is rebuilt and
   pushed, nothing in the documented redeploy workflow restarts that Deployment to pick it up. Decide
   and fix: either add frontend to the sequential restart, or deliberately exclude it (documented, with
   reasoning, in `infrastructure/kubernetes/production/README.md`'s Redeploying section) if it's judged
   stateless/lightweight enough not to need the same rollout care as the backend services. Sequenced
   first — every other goal in this sprint is frontend work, and this is the one thing that could make
   it ship without actually reaching the live site.
2. **[#30](https://github.com/noelwschneider/kafka-portfolio-project/issues/30) Add a Kafka health
   indicator** — Home's System Status table is the first thing a visitor sees, and Kafka's row has read
   "no data" since Sprint 4 enabled `show-components` for every other service. Spring Boot 4.1 ships no
   `KafkaHealthIndicator`; needs a custom `HealthIndicator` bean on a `KafkaAdmin`/`AdminClient`,
   deciding what "healthy" means from one consumer's point of view.
3. **[#31](https://github.com/noelwschneider/kafka-portfolio-project/issues/31) Restore per-component
   health diagnostic detail** — the removed System Health page showed each service's individual
   Actuator components (db, kafka, diskSpace); Home's System Status table only shows one aggregate
   UP/DOWN row per service. Decide whether to add an expandable per-service detail view on Home now
   that #30 gives Kafka real component data too, or accept the loss of granularity as intentional.
   Sequenced with #30 since both touch the same Home health section and share context.
4. **[#36](https://github.com/noelwschneider/kafka-portfolio-project/issues/36) Scenario-run timeline
   never shows EVENT-kind entries** — root-caused during Sprint 5's #27 fix:
   `RunRegistry.finish()` (`services/scenario-service/.../RunRegistry.java:47-50`) clears the
   correlationId→runId mapping as soon as a scenario run completes, so any Kafka record consumed after
   that point (including `DuplicateEventScenario`'s async republish, which routinely lands after
   `awaitTerminal()` returns) can no longer find a run to append an EVENT-kind timeline entry to — even
   though the event projects correctly. Two candidate fixes already identified: (a) have
   `DuplicateEventScenario.run()` wait for the second projection before returning, or (b) have
   `RunRegistry` retain the mapping briefly after a run completes. This is Sprint 4's flagship UX
   feature (issue #6/#18's step-by-step scenario replay) silently not showing what it's supposed to.
5. **[#32](https://github.com/noelwschneider/kafka-portfolio-project/issues/32) Expose per-SKU pricing
   to the frontend** — the New Order form's inventory table has no price column: `InventoryItem`
   carries no price field, and `OrderItem.unitPrice` is only ever captured server-side by Order Service
   from its own seeded SKU price map at order-creation time. A contract-shaped decision is needed —
   either a read-only price-lookup endpoint on Order Service, or moving price ownership to Inventory
   Service — before the frontend can show a price prior to ordering.
6. **[#20](https://github.com/noelwschneider/kafka-portfolio-project/issues/20) Orders table row
   clickability affordance and column formatting** — no hover/cursor affordance signals that a row is
   clickable; the Total column isn't right-aligned with tabular numerals or a fixed-position `$`; the
   Created column shows year and seconds it doesn't need. Purely visual, no design decisions required —
   the cheapest item in the sprint.

## Sequencing

**#37 first, as a preflight; #30 before #31; #36, #32, and #20 are independent of everything else.**

```
#37 (redeploy.sh frontend fix) — first, unblocks trust that the rest of this sprint actually ships

#30 (Kafka health indicator) ──► #31 (per-component detail view)

#36 (scenario timeline EVENT bug) — independent
#32 (per-SKU pricing) — independent, contract decision needed before frontend work
#20 (Orders table polish) — independent, no dependencies
```

1. **#37 first.** Confirms the deployment pipeline actually picks up frontend changes before the
   sprint's frontend-heavy goals are built on top of it.
2. **#30 before #31.** #31's per-service detail view is more valuable once Kafka has real component
   data to show, not just db/diskSpace.
3. **#36, #32, #20** have no dependency on each other or on #30/#31/#37 and can run in parallel.

## Explicitly not in scope

**[#21](https://github.com/noelwschneider/kafka-portfolio-project/issues/21) Orders table per-column
filtering** — considered alongside #20 since both touch the Orders table, but it's a feature addition
rather than a fix for something visibly incomplete, and doesn't share this sprint's "fix what a visitor
would notice" framing. Left for a sprint themed around Orders-page functionality rather than polish.

**[#33](https://github.com/noelwschneider/kafka-portfolio-project/issues/33) Orders pagination** and
**[#34](https://github.com/noelwschneider/kafka-portfolio-project/issues/34) frontend test harness** —
real value, but invisible to a browsing recruiter and don't serve this sprint's presentability goal.
Backlog explicitly notes #33 as low urgency at current data volume; #34 is a strong candidate for a
future reliability/workflow sprint.

**Bug-hunt follow-ups** (`HttpMediaTypeNotSupportedException`, Kafka consumer rebalance mid-transaction,
`DemoResetService` concurrent reset race) and **Inventory: release reservations on FULFILLED (Option
B)** — backend edge cases unlikely to surface in a short recruiter visit. Left for a reliability-themed
sprint like Sprint 5.

**Workflow items** (worktree isolation, `acceptEdits` permission mode, semantic verification gate) —
don't touch the app at all; orthogonal to this sprint's theme.

## Dependencies

No dependency on any other sprint's work. Within the sprint, see the sequencing diagram above — #37
should land before other goals are considered fully verified live; #30 gates #31's scope; #36, #32, and
#20 are independent.

## Planning docs this sprint needs

No new design docs (backend/frontend/high-level) or execution-plan.md — every goal is a fix or a small,
already-scoped addition rather than new architecture. #32 needs a contract decision (price ownership)
made and recorded per the coordination protocol if it touches `docs/openapi/`; #30/#31 delegate to the
`implementer` preset once the health-indicator approach is chosen; #36 delegates to `investigator` first
given the fix still has two unevaluated candidate approaches, then `implementer`; #37, #20 delegate
directly to `implementer`.

## Closing state

All six goals shipped and independently verified against a running stack.

- **#37** — `frontend` added to `redeploy.sh`'s sequential restart, placed last after the five backend
  services since it doesn't share their `maxSurge: 0` memory-pressure ordering rationale.
  `infrastructure/kubernetes/production/README.md` updated to document why. Verified via the real
  script's control flow against a stubbed `kubectl`; not verified against a live cluster or the
  production box itself (Docker Desktop's memory budget couldn't hold a `kind` cluster alongside the
  already-running compose stack) — real-cluster confirmation deferred to the next actual image push.
- **#30** — a shared `KafkaHealthIndicator` added to `services/common`, built on a dedicated
  `AdminClient.describeCluster()` call, picked up automatically by all five services. Verified live:
  `kafka` reports `UP` across all five `/actuator/health` endpoints, correctly flips to `DOWN` when the
  Kafka container is stopped, and recovers to `UP` on restart.
- **#31** — click-to-expand disclosure rows added to Home's System Status table, showing every
  Actuator health component (not just the `kafka`/`db` keys already surfaced) per service. Sequenced
  after #30 so there was real per-component data to show.
- **#36** — root-caused as structural rather than `DuplicateEventScenario`-specific: every scenario's
  completion is order-status-driven via a separate SSE stream, independent of Scenario Service's own
  projection consumer group catching up, so the gap could affect any order-status-driven scenario plus
  `PoisonMessageScenario`'s DLQ wait. Fixed by splitting `RunRegistry.finish()` into an immediate
  `releaseSlot()` (keeps the 409 guard instant) and a deferred `retireCorrelation()` (10s grace period,
  configurable). `TimelineRecorder.forget()` deferred alongside it — an early `forget()` would have let
  a late append collide with `scenario_run_timeline`'s `UNIQUE (run_id, sequence)` constraint inside the
  same transaction as Sprint 5's event-projection fix.
- **#32** — added a read-only `GET /api/prices` endpoint on Order Service exposing its existing seeded
  SKU price map; ownership unchanged, no write path, not consulted at checkout. Logged as a contract
  change per the coordination protocol in `docs/openapi/order-service.yaml`, `docs/db-ownership.md`, and
  `docs/CHANGELOG-contracts.md`.
- **#20** — Total column right-aligned with a fixed `$` position; Created column drops year and seconds.
  Row-clickability affordance turned out to already exist in the code before this sprint started; scope
  narrowed accordingly during delegation.

**[#21](https://github.com/noelwschneider/kafka-portfolio-project/issues/21) Orders table per-column
filtering** was left out as planned — a feature addition rather than a fix for something visibly
incomplete, per the sprint's own scope framing.

One process gap surfaced during execution, not part of this sprint's original scope: none of the four
subagent presets (`implementer`, `investigator`, `verifier`, `platform`) reference the existing Hetzner
dev box (`infrastructure/dev-box/`, documented in
`docs/agent-reports/sprint-2/hetzner-dev-box-setup.md`) as an option for resource-heavy delegated work.
Running this sprint's five independent goals as parallel background agents against the local
docker-compose stack produced two OOM incidents (Kafka killed by concurrent full-stack `docker build`
runs) — both self-recovered via Kafka's persistent volume with no data loss, the same class of
contention Sprint 4's OOM incident hit. Deliberately not fixed inline; logged as its own backlog item
("Parallel-agent resource contention: dev box vs local stack policy") for a future workflow-themed
sprint rather than resolved in passing during this one.
