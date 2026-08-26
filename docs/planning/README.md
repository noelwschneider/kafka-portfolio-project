# Order Fulfillment Systems Lab — Planning Docs

This directory holds the project's planning docs, organized by sprint. **Any agent picking up this
project should read this index first**, then the files relevant to its assigned task — not the full
set.

Four files apply across every sprint and live at this top level. Everything else is scoped to the
sprint that produced it, under `sprint-N/`, since each sprint gets its own execution plan and (where
relevant) its own design/roadmap docs the way `sprint-1/` did for the original MVP build.

## Always read first (cross-sprint)

| # | File | What's in it | Read when |
|---|---|---|---|
| 1 | [`project-overview.md`](project-overview.md) | Purpose, product definition, pinned technology decisions, scope do's/don'ts, non-goals | Always first — sets the frame everything else assumes |
| 2 | [`portfolio-plan.md`](portfolio-plan.md) | Why this project exists (portfolio goals), recruiter/engineer presentation, interview knowledge checklist, "portfolio complete" checklist, resume bullets | Before writing any user-facing copy or judging "is this done" |
| 3 | [`engineering-rules.md`](engineering-rules.md) | Engineering constraints: core directive, the 20 agent rules, contract coordination | Read by every agent before starting any task |
| 4 | This file | Index and current-sprint pointer | Always first |

## Sprints

### `sprint-1/` — MVP build (Phase 0–11, complete)

The original pre-implementation design docs for the MVP, split from a single draft (preserved at
[`../_old/order-fulfillment-systems-lab-action-plan.md`](../_old/order-fulfillment-systems-lab-action-plan.md)).
**Treat these as a frozen historical record of how the MVP was designed and built** — they describe
the system as it was planned pre-implementation, not a living spec. Prefer the frozen contracts
(`docs/openapi/`, `docs/events/`, `docs/order-state-machine.md`, `docs/db-ownership.md`,
`docs/adr/`) or the current code over re-deriving shapes from this prose.

| File | What's in it |
|---|---|
| [`sprint-1/backend-design.md`](sprint-1/backend-design.md) | The four backend services, event-driven lifecycle, event envelope, Kafka topic strategy, reliability patterns (idempotency/retry/DLQ/outbox), PostgreSQL data model, order state machine, seed data, REST API design |
| [`sprint-1/frontend-design.md`](sprint-1/frontend-design.md) | Frontend product direction, page-by-page spec, SSE strategy, the 8 required demo scenarios, frontend UX principle |
| [`sprint-1/high-level-design.md`](sprint-1/high-level-design.md) | Kubernetes design, health probes, Docker/local dev, observability, testing strategy, API error model, security scope, repo strategy, ADR format, CI/CD |
| [`sprint-1/implementation-phases.md`](sprint-1/implementation-phases.md) | Phase 0–11 definitions and exit criteria (the technical roadmap for the MVP build) |
| [`sprint-1/execution-plan.md`](sprint-1/execution-plan.md) | Sprint 1's operational plan: execution model, Claude model/effort tier per workstream, repo/worktree isolation strategy, phase-by-phase agent+input+output+gate table, verification-pass process |

### `sprint-2/` — production readiness (complete)

| File | What's in it |
|---|---|
| [`sprint-2/pre-sprint-planning.md`](sprint-2/pre-sprint-planning.md) | The post-MVP brainstorm and prioritized backlog Sprint 2's scope was chosen from |
| [`sprint-2/sprint-2-plan.md`](sprint-2/sprint-2-plan.md) | Sprint 2's goals, scope, parallelization plan, and the planning docs it needed |

### `sprint-3/` — developer knowledge and workflow (complete)

Theme: the developer's own knowledge and workflow, not the application. See
[`sprint-3/sprint-3-plan.md`](sprint-3/sprint-3-plan.md) for the full plan.

| File | What's in it |
|---|---|
| [`sprint-3/sprint-3-plan.md`](sprint-3/sprint-3-plan.md) | This sprint's goals, sequencing, and the planning docs it needed |
| [`sprint-3/orchestration-retrospective.md`](sprint-3/orchestration-retrospective.md) | How this project's agentic work has actually been orchestrated across Sprint 1–2 — required reading for the Workflow Refinement agent |
| [`sprint-3/workflow-agent-briefing.md`](sprint-3/workflow-agent-briefing.md) | Standalone briefing for the Agentic Workflow Refinement agent session |
| [`sprint-3/study-guide-agent-briefing.md`](sprint-3/study-guide-agent-briefing.md) | Standalone briefing for the Study Guide agent session |

### `sprint-4/` — frontend polish (closed; two items carried to backlog)

Theme: a visual/UX pass across the React frontend, the prerequisite for the README demo recording
(itself out of scope for this sprint). Closed with the Orders table's row-clickability/formatting
and per-column filtering unscheduled and returned to the backlog; everything else shipped. See
[`sprint-4/sprint-4-plan.md`](sprint-4/sprint-4-plan.md) for the full plan and closing state.

| File | What's in it |
|---|---|
| [`sprint-4/sprint-4-plan.md`](sprint-4/sprint-4-plan.md) | This sprint's goals, sequencing, and closing state |

### `sprint-5/` — backend correctness and reliability (complete)

Theme: whether the system's core delivery guarantees actually hold, and closing the gap between the
project's Day-1 CI decision and what exists today — a deliberate pivot from Sprint 4's frontend focus.
See [`sprint-5/sprint-5-plan.md`](sprint-5/sprint-5-plan.md) for the full plan and closing state.

| File | What's in it |
|---|---|
| [`sprint-5/sprint-5-plan.md`](sprint-5/sprint-5-plan.md) | This sprint's goals, sequencing, and closing state |

### `sprint-6/` — recruiter-facing polish and demo reliability (complete)

Theme: visible gaps and small defects on the deployed site, filtered against what a recruiter or
interviewer actually sees in a short visit — not new features, and not the deep backend correctness
work of Sprint 5. All six goals shipped. See
[`sprint-6/sprint-6-plan.md`](sprint-6/sprint-6-plan.md) for the full plan and closing state.

| File | What's in it |
|---|---|
| [`sprint-6/sprint-6-plan.md`](sprint-6/sprint-6-plan.md) | This sprint's goals, sequencing, and closing state |

### `sprint-7/` — workflow and process hardening (current)

Theme: how the project itself is run, not the application — closing demonstrated process gaps in
delegated-agent infrastructure usage and production deploys, plus retrospective audits of bug
patterns and documentation staleness. Primarily non-code, with one Tier 1 bug fix carried in
regardless of theme. See [`sprint-7/sprint-7-plan.md`](sprint-7/sprint-7-plan.md) for the full plan.

| File | What's in it |
|---|---|
| [`sprint-7/sprint-7-plan.md`](sprint-7/sprint-7-plan.md) | This sprint's goals, sequencing, developer-involvement checkpoints, and dependencies |

## Cross-reference note

Files reference each other by filename + section title (e.g. "backend-design.md's PostgreSQL Data
Model section"), not by number. The original single-document numbering (§0–§42) does not carry over
cleanly across the split — several files independently restart at §1, so a bare "§9" is now
ambiguous. If you add new cross-references, cite the filename (and sprint folder, if the file isn't
one of the four cross-sprint ones above).

## Known intentionally-dropped content

During the original split, three narrative/stretch sections from the source draft were deliberately
not carried forward (per-project decision to define stretch goals only once MVP is reached): the
Stretch Goals list, the "First-Rendition"/Version 0.2 milestone framing, and the closing
README-opening copy. If you want any of these restored, they're intact in `_old/`.

The order-state-machine and seed-data content *was* restored (now in `sprint-1/backend-design.md`)
after being dropped unintentionally during the initial split — flagging here in case that history is
useful.
