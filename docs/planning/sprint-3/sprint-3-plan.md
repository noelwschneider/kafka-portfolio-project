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

Neither goal has a checklist-shaped completion this time — both are judged by the developer's own
satisfaction, not by a fixed deliverable list, and that's deliberate rather than a planning gap:

- **Workflow Refinement** ends when the developer is satisfied with the resulting workflow. It's an
  open-ended working conversation by design (see `workflow-agent-briefing.md`) — there is no
  checklist to complete.
- **Study Guide** is a personal reference expected to keep growing past this sprint's close, not a
  frozen artifact with a final page count. "Done" for this sprint means the structure is agreed (a
  chapter breakdown grounded in the project's real build history, plus a pattern-consolidation
  approach) and a first rough pass exists across it — not that every chapter is in its final,
  developer-approved state.

Same re-scoping standard as Sprint 2 applies if either goal needs to change shape mid-sprint — just
judged against the goals above rather than a fixed checklist.

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
