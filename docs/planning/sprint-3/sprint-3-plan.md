# Sprint 3 Plan

- **Input:** [`pre-sprint-planning.md`](../sprint-2/pre-sprint-planning.md) (Tier 2, items 6 and 10 —
  Study Guide and Agentic Workflow Refinement), plus the GitHub Project board's `Backlog` view.
- **Theme:** the developer's own knowledge and workflow, not the application. Sprint 2's theme was
  production readiness; this sprint is deliberately about putting the developer in a position to work
  effectively on the project going forward — understanding it well enough to discuss it credibly (a
  LinkedIn share is planned within the week), and tightening how agentic work on it gets directed now
  that tasks have grown more complex.

## Goals

1. **Agentic Workflow Refinement** — a fresh Claude Code session researches how Claude Code / the
   Claude Agent SDK / the Claude API can be used more effectively for this project's kind of work, and
   makes concrete improvements. Briefing: [`workflow-agent-briefing.md`](workflow-agent-briefing.md).
2. **Study Guide** — a fresh Claude Code session builds a personal interview-prep document covering
   every major design decision in this project. Briefing:
   [`study-guide-agent-briefing.md`](study-guide-agent-briefing.md).

Both goals run as separate Claude Code sessions the developer starts directly (same pattern as Sprint
2's VPS and deployment agents), not as background subagents of the orchestrating session — each is a
genuinely independent, long-running track that benefits from its own dedicated session.

## Sequencing

**Workflow Refinement first, or at least substantially ahead of Study Guide.** Whatever it finds —
better delegation patterns, reusable subagent presets, a clearer model-tiering practice — should
inform how the Study Guide work actually gets directed, not just apply to sprints after this one.
Starting Study Guide before Workflow Refinement has produced its key findings means potentially
redoing the "how" partway through the biggest single item on this project's backlog.

Study Guide does not need to wait for Workflow Refinement to fully *finish* — only for its concrete,
actionable findings to exist. Once those land, Study Guide can start (or continue, if some prep work
began in parallel) using whatever new practice resulted.

## A note on what almost got included, and didn't

TLS origin hardening and a couple of small verification tasks (bug-hunt follow-ups, real CI) were
considered for this sprint and deliberately left out — they don't share this sprint's theme
(understanding and workflow, not infrastructure), and none of them were urgent enough to justify
breaking that coherence. They remain on the board's `Backlog` view, `Tier 1`/`Tier 2` as already set,
for a future sprint with a matching theme. Sprint scope should stay thematically coherent going
forward unless something is genuinely urgent enough to justify the narrow exception in
`agent-guidance.md`'s Sprint workflow section.

## Dependencies

```
workflow refinement ──► study guide
```

No dependency on any other sprint's work — both goals are self-contained relative to the application
codebase.

## Definition of done

Both goals delivered, or explicitly re-scoped with a documented reason — same standard as Sprint 2.
Given the Study Guide's own briefing explicitly expects it may span multiple sessions, "done" for that
goal means a complete first pass across all sections in `portfolio-plan.md` §31's categories plus the
20 questions in §32, not necessarily a document the developer considers finished forever — it's a
personal reference, not a frozen artifact, and can keep growing after this sprint closes if needed.

## Planning docs this sprint needs

- **[`orchestration-retrospective.md`](orchestration-retrospective.md)** — required reading for the
  Workflow Refinement agent; a synthesis of how this project's agentic work has actually been
  orchestrated across Sprint 1 and Sprint 2, written by the orchestrating session since that history
  isn't recorded anywhere a fresh session could otherwise reach it.
- **[`workflow-agent-briefing.md`](workflow-agent-briefing.md)** and
  **[`study-guide-agent-briefing.md`](study-guide-agent-briefing.md)** — standalone context for each
  separate agent session, per the template established in `sprint-2/vps-agent-briefing.md` and
  `sprint-2/deployment-agent-briefing.md`.

No design docs (backend/frontend/high-level) or execution-plan.md are needed — neither goal touches
the application's architecture, and each briefing above already specifies its own model/effort tier
directly rather than needing a separate tier table.
