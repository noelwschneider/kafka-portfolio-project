# Sprint 8 Plan

- **Input:** the developer's own brainstorming session on frontend appeal, filtered against the
  existing Orders-page backlog (#21, #33) and two drafts it turned out to match closely, plus one
  Tier 1 bug carried in regardless of theme per Sprint 7's own precedent.
- **Theme:** frontend visual/UX upgrade — making the UI look genuinely appealing, not just
  functionally polished (Sprint 4) or recruiter-legible (Sprint 6). The one exception is
  [#52](https://github.com/noelwschneider/kafka-portfolio-project/issues/52), carried in because it's
  the same live-correctness defect class as #46/#47, not because it fits the theme.

## Goals

1. **[#52](https://github.com/noelwschneider/kafka-portfolio-project/issues/52)
   `InventoryContentionScenario` has the same stock-depletion defect as Scenario 8 (SKU-004)** —
   flagged in [PR #47](https://github.com/noelwschneider/kafka-portfolio-project/pull/47)'s own body
   as an identical bug shape to the #46 fix (seeded 2, consumes 2, reservation never released) left
   out of that PR's scope. The fix pattern is already proven; this is applying it to the second
   scenario.
2. **[#53](https://github.com/noelwschneider/kafka-portfolio-project/issues/53) Frontend styling
   contract** (Initiative) — a shared internal contract for CSS custom-property naming/structure and
   authoring conventions, so the interactive theme session and the scenario-run flow visualization
   reference one token namespace instead of each independently inventing colors that later collide.
   Mirrors this project's existing cross-service contract pattern (`docs/openapi/`, `docs/events/`)
   applied one layer down, internal to the frontend. Two sub-issues:
   - **[#54](https://github.com/noelwschneider/kafka-portfolio-project/issues/54)** writes the
     contract — naming/structure only (e.g. `--color-service-order`, `--status-*`, spacing scale),
     deliberately no final palette/spacing values. Values are decided live in the interactive theme
     session so the contract doesn't need revision once real visual iteration starts.
   - **[#55](https://github.com/noelwschneider/kafka-portfolio-project/issues/55)** refactors
     `frontend/src/index.css` and existing components onto the new contract. Should produce zero
     visible change — one commit.
3. **[#56](https://github.com/noelwschneider/kafka-portfolio-project/issues/56) Interactive theme
   session** — a new site theme, run as a live dev-server + browser-preview loop with the developer
   watching and giving real-time feedback rather than a one-shot diff. Assigns actual values to #54's
   token namespace. Touches every page — `frontend/src/index.css` has no existing token layer beyond
   light/dark to lean on.
4. **[#57](https://github.com/noelwschneider/kafka-portfolio-project/issues/57) Scenario-run timeline:
   graphical service/topic flow indicator** — per timeline step, a color-coded symbol for the service
   it occurred in and an arrow to the next service labeled with the topic/endpoint it routed through.
   The information already shown becomes the in-depth/detail part of each row. The service/topic data
   is already surfaced as raw detail-row keys on `ScenarioRunDetailPage.tsx`; this is a new rendering
   layer, not new data plumbing. Consumes #54's `--color-service-*` tokens (depends on #54 only, not
   #55/#56) so it doesn't invent its own palette ahead of the theme session. Delegate-then-review, not
   a live session. Stretch, not committed: a foundation flexible enough to extend to more complex
   scenarios (e.g. duplicate event) rather than just the common single-flow case.
5. **[#33](https://github.com/noelwschneider/kafka-portfolio-project/issues/33) Orders list
   pagination** — the order-service API already accepts `page`/`size` server-side
   (`docs/openapi/order-service.yaml`); `OrdersListPage.tsx` just never wired them up. Frontend-only.
6. **[#21](https://github.com/noelwschneider/kafka-portfolio-project/issues/21) Orders table
   filtering + lookup** — per-column filtering, expanded in scope to include direct lookup fields for
   order ID (route straight to the existing detail-by-id page) and customer (wired to the existing
   `customerId` query param). `status` and `customerId` are already backend-supported filters, so
   this is frontend wiring, not a backend change.
7. **[#50](https://github.com/noelwschneider/kafka-portfolio-project/issues/50) Give each scenario run
   a distinct customer name** — assign each scenario a distinct, recognizable customer name in its
   order-creation path so the Orders table's Customer column makes the association visible with no
   backend change. Grouping orders by scenario run (rather than just visually distinguishing them) is
   a further idea, not committed — it needs either a real backend join (`ScenarioRun` has `orderId`
   but nothing joins it back for display today) or a frontend heuristic keyed off the name.
8. **[#51](https://github.com/noelwschneider/kafka-portfolio-project/issues/51) `OrderDetailPage`
   timestamps** — apply #20's short `Intl.DateTimeFormat` treatment to the three remaining
   full-timestamp call sites. Small, mechanical.
9. **[#58](https://github.com/noelwschneider/kafka-portfolio-project/issues/58) Homepage: render the
   "no data" hint conditionally** — `OverviewPage.tsx` always shows the no-data disclaimer regardless
   of whether any component is actually in that state. Render it only when one is.

## Sequencing

**The styling contract's structure gates the two design-judgment items; everything else is
independent.** #54 must exist before #56 or #57 start (both would otherwise invent their own palette
ahead of the contract), but neither needs #55's refactor finished first. The Orders-page items and
#58 don't touch the contract at all and can run any time.

```
#52 (SKU-004 fix) — independent

#54 (write contract) ──┬──► #55 (refactor onto contract) — independent thereafter
                        ├──► #56 (interactive theme session)
                        └──► #57 (timeline flow indicator, delegate-then-review)

#33 (pagination) — independent
#21 (filtering + lookup) — independent
#50 (customer name per scenario) — independent
#51 (OrderDetailPage date formatting) — independent
#58 (homepage no-data cleanup) — independent
```

## Explicitly not in scope

**Grouping orders by scenario run** (#50's stretch) and **a flexible multi-scenario foundation for
the flow indicator** (#57's stretch) — both named, neither committed. Revisit only if the simpler
version each item ships with proves insufficient.

**#34 frontend test harness** — real and Tier 2, but not a "look" item; mixing test infrastructure
into a visual sprint would cut against the sprint's own coherence.

## Developer involvement

- **#52 (bug fix)** — no developer involvement expected.
- **#54 (write contract)** — delegatable outright; no live session needed, structure/naming decisions
  don't depend on seeing it rendered.
- **#55 (refactor onto contract)** — mechanical, delegatable, verifiable by diffing rendered output
  before/after (should be zero visible change).
- **#56 (interactive theme session)** — the developer is directly in the loop for this one: live
  dev-server + browser preview, real-time feedback while the agent works.
- **#57 (flow indicator)** — delegate-then-review. No live session, but the developer reviews the
  result before it's considered done, since icon/color/arrow choices are taste-heavy.
- **#33, #21, #50, #51, #58** — no developer involvement expected; loop in only if a genuine judgment
  call comes up mid-task.

## Dependencies

No dependency on any other sprint's work. Within the sprint, see the sequencing diagram above.

## Planning docs this sprint needs

No new backend/frontend/high-level design docs. #54 produces the styling contract itself as its
deliverable (location and exact doc format are that task's own decision, not pre-specified here).

## Closing state

Eight of nine planned goals shipped: #52, #54, #56, #57, #33, #21, #50, #51, #58. **#55** (refactor
existing components onto the styling contract) did not ship — a direct check against
`frontend/src/index.css` found the flat `--success`/`--failure`/`--pending`/`--expected` tokens the
contract calls for renaming into the `--color-status-*` namespace are still under their old names.
Moved back to Backlog with its Sprint cleared, alongside its parent initiative **#53**, rather than
marked delivered.

Two unplanned items came out of a full local review of the sprint's combined changes and shipped the
same day: **#71** (Home nav-tab rename, a real CSS-specificity bug making visited nav tabs render
accent-green instead of neutral, a scenario-card redesign, and removing a redundant back-button) and
**#72** (streamlining the Architecture page to a diagram, a technology table, and repository links,
dropping duplicated content and verbose rationale prose).

**#46**'s fix (issue tracked outside this sprint, fixed via PR #47 before Sprint 8 opened) remains
undeployed — the plan is to deploy it together with this sprint's own changes, so it stays open and
out of Sprint 8's Done count.

One real process incident: merging #65 initially landed on a stale intermediate branch instead of
`main` (its base had never been retargeted after an earlier branch-closure/replacement), and deleting
that branch during cleanup orphaned the merge commit — the feature briefly existed only in already-
pushed remote refs and local git objects, not on any branch. Caught by directly checking for the
feature's files on `origin/main` rather than trusting `gh pr merge`'s reported success, and recovered
by pushing the still-reachable commit to a fresh branch and re-merging it against the correct base.
Every other merge this sprint was independently re-verified by the same direct content check after
the fact, confirming no other instance of the same gap.
