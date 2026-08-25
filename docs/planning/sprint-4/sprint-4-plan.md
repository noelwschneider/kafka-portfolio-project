# Sprint 4 Plan

- **Input:** the developer's own scoping, entered directly onto the GitHub Project board ahead of
  this plan doc.
- **Theme:** the frontend, not the backend or the developer's own workflow. Sprint 3 was about the
  developer's knowledge and how agentic work gets directed; this sprint returns to the application
  itself — a visual/UX pass across every existing page, tightening structure, copy, and the
  simulated-vs-real distinction that a demo recording depends on reading clearly.

## Goals

Tracked as GitHub Initiative
[#4 "Frontend Polish Sprint"](https://github.com/noelwschneider/kafka-portfolio-project/issues/4).
The developer's live review of the initial 9-issue pass produced three further rounds of sub-issues
(#14–19, #22–24, #25–26) covering badge color, header copy, scenario-card content/visuals, the
scenario-run timeline's narrative and per-card titles, the New Order form's inventory table and
styling, and a click-drag modal bug — all tracked as further sub-issues of #4. The original 9:

1. **[#5](https://github.com/noelwschneider/kafka-portfolio-project/issues/5) Merge Scenarios into
   Home as the primary landing page** — Scenarios becomes the homepage content; drop the standalone
   Scenarios nav item. Move the recent-orders and scenario-run tables off Home (superseded by #7).
   Redesign scenario cards so essential information is scannable at a glance, fuller descriptions on
   expand. Remove the visitor-facing "Reset demo environment" button; reset stays an internal/ops
   action.
2. **[#7](https://github.com/noelwschneider/kafka-portfolio-project/issues/7) Orders page rework** —
   move the orders table here from Home with a genuine table redesign. Add a per-order event timeline
   using the existing event-filtering functionality, replacing the standalone Event Explorer nav item
   (filtering capability kept, scoped to an order). Redesign the New Order form's styling and replace
   its jarring full-page navigation with an inline panel or modal.
3. **[#9](https://github.com/noelwschneider/kafka-portfolio-project/issues/9) Drop System Health as a
   standalone page** — remove the nav item and route; it's redundant with Home's service-status
   section.
4. **[#8](https://github.com/noelwschneider/kafka-portfolio-project/issues/8) Architecture page
   rework** — replace the dense system-overview diagram with a lighter page: keep the happy-path
   sequence diagram, link out to the repo's own architecture docs instead of duplicating them.
5. **[#6](https://github.com/noelwschneider/kafka-portfolio-project/issues/6) Scenario run
   step-by-step review** — make it clear at every point that something is happening, what the
   scenario is supposed to do, and what actually happened; let a completed run be reviewed
   step-by-step after the fact.
6. **[#12](https://github.com/noelwschneider/kafka-portfolio-project/issues/12) Distinguish
   simulated/expected outcomes from real app failures** — one consistent visual/copy treatment for
   Home's Kafka/PostgreSQL status pills, order status badges (PAYMENT FAILED / REJECTED OUT OF
   STOCK), and scenario expected-terminal-state labels, applied everywhere the ambiguity shows up.
7. **[#11](https://github.com/noelwschneider/kafka-portfolio-project/issues/11) Add loading states
   across data-fetching views** — every page that fetches on mount gets a loading indicator instead
   of rendering an empty shell.
8. **[#10](https://github.com/noelwschneider/kafka-portfolio-project/issues/10) Sitewide copy pass** —
   tighten wording site-wide, prioritizing Home/Scenarios and Orders.
9. **[#13](https://github.com/noelwschneider/kafka-portfolio-project/issues/13) Add a favicon.**

## Sequencing

**Structural first, then feature depth, then polish.** Later goals build on the page shapes earlier
ones produce; doing this in reverse means redoing polish work against structure that's about to move.

```
┌─ #5 merge Scenarios→Home ─┐
├─ #7 Orders rework         ├──► #6 scenario-run review ──┐
├─ #9 drop System Health    │                             ├──► #11 loading states ──► #10 copy pass
└─ #8 Architecture rework   └──► #12 simulated/real ───────┘
                                                                                          #13 favicon (no dependency, any time)
```

1. **Structural/nav** — #5, #7, #9, #8. These change what pages exist and where content lives; every
   later goal should build on the landed shape, not the current one.
2. **Feature depth** — #6, #12. Define the simulated-vs-real treatment and the step-by-step review
   once the structure is stable, so both get applied consistently across Home, Orders, and the
   scenario-run detail page rather than needing rework after #5/#7 land.
3. **Polish** — #11, then #10. Loading states and copy are cheapest to get right once final page
   shapes exist, not before. #13 has no dependency and can happen at any point.

## Explicitly not in scope

The README demo recording and an accompanying documentation content pass are deliberately separate,
unscheduled future sprints (per [b2c6e6b](https://github.com/noelwschneider/kafka-portfolio-project/commit/b2c6e6b93a098b7c2d4c291bdeb01d6c7c5b2ca5)) —
not this one. This sprint is the prerequisite (a frontend that looks finished), not the recording
itself.

## Dependencies

No dependency on any other sprint's work — self-contained relative to the rest of the codebase.
Within the sprint, see the sequencing diagram above.

## Closing state

Nav is reduced to Home (Scenarios merged in), Orders, and Architecture — Scenarios, Event Explorer,
and System Health no longer exist as standalone nav items/routes. Every original goal (#5–#13) and
every review-driven follow-up except two shipped and is verified live against a running stack. Not
completed this sprint, returned to the backlog unscheduled: Orders table row-clickability affordance
and column formatting (#20), and per-column filtering (#21) — both untouched, no work started.

Three backend correctness bugs were found incidentally while verifying scenario/event-timeline
behavior (#25's investigation) and filed separately, outside this sprint's frontend theme: #27 (the
root cause — Kafka's event-projection dedup key collides after a broker reset, breaking the
`duplicate-event` demo scenario and Order Detail's event timeline for affected orders), #28
(`reserved_quantity` not zeroing after `FULFILLED`), and #29 (whether real domain services'
idempotency ledgers share #27's collision-prone key pattern — unresolved, potentially more serious
than #27 if true).

The frontend is otherwise in a state the developer considers ready to record the README demo
walkthrough against — that recording itself remains out of scope for this sprint (see above).

## Planning docs this sprint needs

No design docs (backend/frontend/high-level) or execution-plan.md — this sprint doesn't touch backend
architecture or introduce new contracts. Per-item work should delegate to the `implementer` preset per
`docs/workflow/agent-workflow.md`, briefed directly from each sub-issue's description and this plan's
sequencing.
