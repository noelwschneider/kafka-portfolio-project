# 35. AI Agent Implementation Guidance

This document may be supplied to one or more AI coding agents.

Agents should follow these constraints.

## Core directive

Build the smallest coherent system that truthfully demonstrates the architecture described here.

Do not optimize for line count, service count, or technology count.

---

## Sprint workflow

Work past the initial MVP build (Sprint 1) is planned and executed in sprints, tracked under
`docs/planning/sprint-N/`. The cadence is **plan → work → plan**: a sprint's full scope is decided
during planning (see that sprint's own plan doc, e.g. `sprint-2/sprint-2-plan.md`), then executed to
completion, then planning begins for the next sprint from whatever's left plus whatever's been
learned.

This is not a fixed-length or fixed-capacity iteration in the Scrum sense. There is currently no
established limit on how much work belongs in one sprint — that's being felt out empirically over
the first several sprints, not fixed in advance. Don't assume a sprint should be small just because
earlier ones were, or object to a large one on that basis alone.

Within a sprint: complete everything scoped into it before starting anything that wasn't — with one
exception. Urgent or unblocking work that emerges mid-sprint (a production-breaking bug, a dependency
one planned item turns out to actually need) may jump the queue. That's a deliberate, narrow
exception, not a general license — routine "while I'm in here" scope additions still wait for the
next planning cycle, even if they seem small.

This section describes the current process, not a permanent one. If the cadence changes, update it
here rather than letting later sprints silently drift from what's written.

---

## GitHub Project board

The [Kafka Portfolio Project](https://github.com/users/noelwschneider/projects/7) board is the
operational tracking layer. It does not replace the sprint plan docs — the board tracks live status;
`docs/planning/sprint-N/sprint-N-plan.md` still owns the narrative (goals, dependencies, tradeoffs,
rationale). Don't duplicate rationale into board item descriptions, and don't try to track live status
in the markdown docs — each artifact does the job the other one is bad at.

**Fields:** `Status` (`Backlog` → `Planned` → `In Progress` → `Done`), `Priority` (`Tier 1` / `Tier 2`
/ `Shelved`, matching `pre-sprint-planning.md`'s existing vocabulary), `Sprint` (a plain single-select
value like `Sprint 3`, added by hand per sprint — not GitHub's built-in Iteration field, which assumes
fixed calendar-length cycles this project deliberately doesn't have).

**Views:** `Backlog` (table, filtered to `Status: Backlog`, grouped by Priority — the full unscheduled
list) and `Current Sprint` (board, grouped by Status — the active sprint's working view). At each
sprint boundary, update the `Current Sprint` view's filter to the new sprint value.

**Draft items vs. real Issues:** backlog entries start as draft items (cheap, no repo noise). Convert
to a real repo Issue only once something is pulled into a sprint — real Issues can be referenced from
commits/PRs (`Closes #12`), giving traceability a draft item can't. This also keeps the Issues tab
showing only things actually worked, not the full someday/maybe list.

**Initiative / Task hierarchy:** two levels, not three — no separate "Story" tier, no custom field for
it. An **Initiative** is a big-picture objective (could be sprint-sized or much smaller); a **Task** is
one concrete, independently-completable unit of work, sized to map to a single subagent delegation.
Most backlog items are already Task-sized and need nothing else — the Issue *is* the Task. Decompose an
Initiative into sub-issues (GitHub's native parent/child issue links, not a label) only when it turns
out to have multiple genuinely separable deliverables — and decide that lazily, at sprint-selection
time when the real seams are visible, not upfront on every backlog item. Sprint 2 goal 2
("Correctness & Reliability Cleanup") is a good retroactive example of what should have been a parent
Issue with sub-issues: four separable deliverables (outbox pattern, `FAILED` transition, SSE-defect
check, retention policy) bundled under one goal.

**Log unplanned work too**, even retroactively — a board that only reflects planned work quietly
becomes inaccurate the first time something urgent jumps the queue (see the exception in "Sprint
workflow" above).

---

## Agent rules

1. Do not invent product requirements beyond this document unless required for implementation.
2. Prefer boring, conventional code over clever abstractions.
3. Keep service boundaries explicit.
4. Keep DTOs separate from persistence entities.
5. Do not expose JPA entities directly from controllers.
6. Use database migrations.
7. Provide meaningful automated tests.
8. Preserve idempotency and event metadata.
9. Keep demo APIs isolated under `/demo`.
10. Scenario behavior must be real, not frontend simulation.
11. Do not introduce extra infrastructure without documenting the reason.
12. Keep the project runnable at every major phase.
13. Favor incremental commits/milestones.
14. Add README instructions whenever startup requirements change.
15. Add an ADR for major architectural changes.
16. Avoid hidden magic in shared libraries.
17. Use consistent logging with correlation IDs.
18. Do not claim stronger delivery/consistency guarantees than are implemented.
19. Do not make Kubernetes a prerequisite for early local development.
20. Keep frontend styling polished but secondary to system visibility.

---

# 36. Recommended AI Agent Work Breakdown

If multiple agents are used, split by bounded responsibility rather than allowing all agents to edit everything.

**Staging note:** this per-service ownership split applies starting at Phase 3 (Extract Services), not from project start. Phases 0–1 (contracts, then the modular monolith) are built by a single foundation workstream so there is no divergent domain logic to reconcile before boundaries exist. See the companion execution plan for the exact agent-by-phase schedule, model/effort tiers, and which phases run sequentially versus in parallel.

## Agent A — Architecture / Contracts

Own:

- OpenAPI contract,
- event catalog,
- schemas,
- order state machine,
- ADRs,
- shared conventions.

Should finish initial contracts before heavy parallel implementation.

---

## Agent B — Order Service

Own:

- order REST API,
- order persistence,
- order event publication,
- order lifecycle projection,
- tests.

---

## Agent C — Inventory Service

Own:

- inventory persistence,
- reservation logic,
- concurrency protection,
- inventory events,
- contention tests.

---

## Agent D — Payment + Fulfillment

Own:

- deterministic payment simulator,
- payment events,
- compensation hooks,
- shipment creation,
- tests.

These can initially remain one workstream because they are smaller.

---

## Agent E — Frontend

Own:

- React/TypeScript app,
- Orders UI,
- Scenarios UI,
- timeline,
- Event Explorer,
- System Health,
- SSE client,
- architecture page.

Frontend agent must consume defined APIs rather than invent alternate contracts.

---

## Agent F — Platform / Infrastructure

Own later:

- Docker,
- Docker Compose,
- Kubernetes manifests,
- health probes,
- CI/CD,
- observability stack.

Do not begin with complex Kubernetes work before backend contracts stabilize.

---

# 37. Agent Coordination Rules

Use shared contracts as the integration boundary.

Before parallel work:

- freeze initial endpoint names,
- freeze event envelope,
- freeze initial event names,
- freeze order status enum,
- document database ownership.

When an agent needs a contract change:

1. modify contract documentation first,
2. state why,
3. update affected implementations,
4. add/adjust tests.

Avoid silent divergence.

---
