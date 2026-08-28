# Issue #82 — project handoff document, wired into /sprint-close

## What changed

- `docs/planning/handoff.md` (new) — the handoff doc itself: what the project is, current deploy
  state/URL, current sprint (Sprint 9) status, three notable recent incidents (PR #65 stale-base
  merge, Sprint 7's unwatched CI push, issue #46's same-delegation merge), backlog highlights
  (nothing time-sensitive beyond Sprint 9's own in-flight items), and a reading-order pointer to
  `docs/planning/README.md`.
- `docs/planning/README.md` — added one row (row 5) to the "Always read first" table pointing at
  `handoff.md`, "Resuming the project after time away." No other change to the file.
- `.claude/skills/sprint-close/SKILL.md` — inserted a new numbered step 4 ("Update the handoff doc")
  between the existing step 3 ("Leave the record accurate") and the former step 4 ("Hand off to
  review"), which is now renumbered to step 5.

## How this was verified

Content in `handoff.md` was pulled from the primary sources named in the brief, not invented:

```
$ head -60 README.md   # deploy URL and what's built
**https://fulfillment-demo.noelschneider.com**
...

$ grep -n "Closing state" -A 60 docs/planning/sprint-8/sprint-8-plan.md
120:## Closing state
122-Eight of nine planned goals shipped: #52, #54, #56, #57, #33, #21, #50, #51, #58. **#55** ...
139-One real process incident: merging #65 initially landed on a stale intermediate branch ...

$ cat docs/planning/sprint-9/sprint-9-plan.md   # theme/goal lines used verbatim in handoff.md

$ gh issue view 82 --json title,body,state,labels
{"body":"No existing doc serves this role today...","state":"OPEN",...}

$ gh project item-list 7 --owner noelwschneider --limit 200 --format json
87 items returned; scanned for anything Backlog/time-sensitive beyond Sprint 9's own
in-flight items (#79/#80/#81/#82/#83) — nothing else stood out as blocking or deadlined.
```

CI on the PR (docs-only change, no service code touched):

```
$ gh pr checks 84
Required checks	pass	4s	.../job/98958603150
frontend	skipping	0	.../job/98958603433
fulfillment-service	skipping	0	...
inventory-service	skipping	0	...
order-service	skipping	0	...
payment-service	skipping	0	...
scenario-service	skipping	0	...
changes	pass	6s	.../job/98958561206
```

All five backend/frontend build jobs correctly skip via path filtering (nothing under
`services/*` or `frontend/*` changed); `Required checks` and `changes` both pass. PR:
https://github.com/noelwschneider/kafka-portfolio-project/pull/84, branch
`docs/handoff-doc-sprint-close`.

Markdown link targets in `handoff.md` (`project-overview.md`, `sprint-8/sprint-8-plan.md`,
`sprint-9/sprint-9-plan.md`, `README.md`) were checked by hand against the actual files in
`docs/planning/` — all exist at the paths referenced.

## Judgment calls

- **Backlog highlights section stayed short.** The brief said "not a full dump — name only anything
  genuinely time-sensitive or blocking, if there is any." Scanning all 87 board items, nothing
  outside Sprint 9's own five in-flight items (#79/#80/#81/#82/#83) carries a deadline or external
  blocker — the rest is ordinary backlog (bug-hunt follow-ups, deferred styling refactor, etc.),
  consistent with sprint-9-plan.md's own "Explicitly not in scope" section calling those items
  "none time-sensitive." I named the in-flight sprint items and said explicitly that nothing else
  qualifies, rather than inventing urgency or listing the whole backlog.
- **Incidents list kept to three.** The brief pointed at PR #65 as the clear example and said "check
  `docs/agent-reports/sprint-8/` and recent sprint plans' Closing state for others worth a one-line
  mention." I added two more that each changed a standing rule (Sprint 7's unwatched-CI-push incident
  behind the `gh pr checks --watch` requirement, and issue #46's same-delegation merge behind the
  `block-subagent-merge-deploy.py` hook) and stopped there — the sprint-8 agent-report directory has
  several tactical bug-fix reports (stock depletion, SSE refetch storm, etc.) that were routine work,
  not process-changing incidents, so I left those out per the brief's "don't pad this with routine
  work" instruction.
- **Renumbering the sprint-close skill's steps.** The brief said "fit it into the skill's existing
  numbered steps (0 through 4) rather than bolting on an unnumbered afterthought," which required
  either inserting before the existing step 4 (renumbering it to 5) or appending as a new step 5
  after review-handoff. I inserted before "Hand off to review" since updating the handoff doc belongs
  to *closing* (establishing what happened), not to the separate `/sprint-review` pass that follows —
  matches the skill's own step 3/4 boundary ("Leave the record accurate" vs. "Hand off to review").
- **Did not touch `docs/planning/sprint-9/`.** A `sprint-9-plan.md` file already existed on disk but
  untracked in git (not committed by this task or any prior commit); I left it alone since it's out
  of this task's scope and not mine to commit.

## Deliberately not covered

- **Developer review of the doc's section structure has not happened.** The issue and sprint plan
  both say explicitly this needs the developer's own read before being considered final ("the
  developer reviews the doc's section structure once before it's finalized, since it exists to serve
  the developer's own future need"). This report and the PR are ready for that review — **do not
  treat this as done pending only a merge**; the structure itself (which sections exist, in what
  order, at what depth) is what's still open for sign-off, not just line-level correctness.
- **`/sprint-close` step 4 has not been exercised end-to-end** — no sprint has closed since this
  change landed, so the new step's actual wording hasn't been proven against a real closing pass.
  It's a natural-language addition to an existing skill file, not code, so there's nothing to unit
  test; the first real verification will be the next time `/sprint-close` runs (end of Sprint 9).
- **Sprint 9's Goal 1 items (#79/#80/#81/#83) are still in progress** — this task didn't touch them
  and the handoff doc's "current sprint" section describes their status as of the time this report
  was written, not a live feed. It will go stale the moment any of them close, same as any other
  point-in-time doc, until the next `/sprint-close` pass refreshes it.
