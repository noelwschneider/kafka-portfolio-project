# Issue #40 — Documentation staleness and consolidation review

Audit of everything under `docs/` against `docs/planning/README.md`'s own index of what is
authoritative vs. historical. This is phase one of two: findings and a proposed action for each,
no file moved, deleted, merged, or rewritten. Phase two (execution) happens only after developer
sign-off on the recommendations below.

## Summary

`docs/` holds up better than its six-sprint age would suggest. The frozen contracts
(`docs/db-ownership.md`, `docs/order-state-machine.md`, `docs/scenarios.md`,
`docs/architecture-diagram.md`, `docs/events/`, `docs/adr/*`) are current, internally consistent, and
correctly cross-referenced — every recent contract change has a matching `docs/CHANGELOG-contracts.md`
entry, and spot-checks against the actual database-ownership and pricing content confirm the prose
matches the latest change. The per-sprint planning/report split scales fine on its own: thin sprints
produce one plan file, heavier ones produce more, and no folder is unwieldy.

The real problems are concentrated in three places: two dead cross-references in currently-read docs,
one `git`/`.gitignore` accident that put two directories into the tracked repo that were never meant
to be there and whose own text still denies being there, and a structural risk (not yet a contradiction,
but already trending toward one) from `docs/study-guide/` narrating a point-in-time snapshot of
contracts that have since moved.

## Note on uncommitted working-tree state found at the start of this audit

Before this task began, the working tree already carried uncommitted changes unrelated to this audit:
`docs/planning/README.md` had the Sprint 7 index entry added (expected, matches this sprint's own plan
opening), `docker-compose.yml` had a temporary, explicitly-labeled repro change for issue #41
(`TEMP-REPRO-ISSUE-41`, marked "MUST be reverted"), two `services/` files had in-progress edits, and
—directly relevant to finding 3 below — **`docs/study-guide/study-guide-complete.md` was already
deleted, uncommitted, in the working tree** (a clean 15,025-line removal, no other change to it). None
of this was made by this audit; it predates this session and none of it was touched, reverted, or
built on here. It's noted because a reviewer diffing this report's file list against `git status`
should not mistake pre-existing, unrelated in-flight work for something this task did. It's also a
useful data point for finding 3: someone independently already started narrowing
`docs/study-guide/`'s footprint, which is consistent with the direction recommended below, though the
other 82 files in that directory (including the ones with the same false "gitignored, never committed"
claim) are still present and untouched.

## Findings

### 1. Dead cross-reference: `project-overview.md` → `execution-plan.md` (real defect, not frozen)

`docs/planning/project-overview.md:36`:

> For **who builds it, in what order, with which Claude model/effort tier, and using what
> tooling**, see [`execution-plan.md`](execution-plan.md)

This resolves to `docs/planning/execution-plan.md`, which does not exist. The file moved to
`docs/planning/sprint-1/execution-plan.md` when the original single draft was split into the
per-sprint structure; this link was never updated. `project-overview.md` is one of the four
cross-sprint files everyone reads first (`docs/planning/README.md`'s own table), so this is a
first-touch broken link for anyone following the doc as written — not a stale corner nobody visits.

**Proposed action:** fix the link to `sprint-1/execution-plan.md`. `project-overview.md` is a living
cross-sprint file, not frozen sprint-1 content, so this is a normal edit, not a coordination-protocol
change.

### 2. Dead cross-reference: `sprint-1/execution-plan.md` → `README.md` (frozen, flag only)

`docs/planning/sprint-1/execution-plan.md:4`:

> see [`README.md`](README.md) for what's in each

This resolves to `docs/planning/sprint-1/README.md`, which does not exist — the actual index is one
directory up, `docs/planning/README.md`. Per `.claude/CLAUDE.md`, sprint-1 docs are frozen historical
record; the fix here (`../README.md`) is trivial but out of scope for this pass. Flagging per the
"flag issues there rather than fixing them" instruction rather than touching the frozen file.

**Proposed action:** leave `sprint-1/execution-plan.md` untouched. If sprint-1 docs are ever revisited
for any other reason, correct this link then; not worth a standalone frozen-doc exception for one
relative path.

### 3. `docs/study-guide/` and `docs/external/` are tracked and committed, contradicting their own text

`docs/study-guide/README.md:7-8` and `docs/study-guide/study-guide-complete.md:7-8` both state:

> This is personal study material. It lives in the repo for tidiness but is gitignored
> (`.gitignore:6`) and never gets committed or pushed.

This is false on both counts today. `.gitignore` line 6 is `__pycache__/`, not a study-guide rule, and
`git ls-files docs/study-guide | wc -l` returns 83 tracked files including the 15,025-line
`study-guide-complete.md`. `docs/external/claude-effort.md` — an unrelated reference doc about
Claude API effort-level tuning, with no connection to the order-fulfillment system — is tracked too.

The mechanism, reconstructed from git history:

- `3cb38e8` (`update gitignore`) had `docs/_old/`, `docs/agent-reports/`, `docs/external/`, and
  `docs/study-guide/` all ignored.
- `e8b79b8` (`update gitignore`), the very next commit, collapsed those four lines to a single
  `*/_old/` pattern — dropping the ignore rule for `docs/agent-reports/`, `docs/external/`, and
  `docs/study-guide/` in one edit, with no commit message explaining which of the three were meant to
  stop being ignored.
- `7a9ff50` (`add previously-ignored docs to git tracking`), immediately after, committed everything
  that was newly unignored: all of `docs/agent-reports/` (clearly intentional — that directory is
  load-bearing for the whole workflow) plus all 33 `docs/study-guide/` files and
  `docs/external/claude-effort.md` (33 and 1 file respectively, confirmed via `git show 7a9ff50
  --stat`).

Un-ignoring `docs/agent-reports/` looks like the actual goal (a genuine bug — reports that should have
always been tracked, weren't). `docs/study-guide/` and `docs/external/` riding along looks like an
unreviewed side effect of the same pattern collapse, not a separate deliberate decision — nothing in
any commit message says "also start tracking the study guide and the effort-tuning reference," and
the study guide's own text was never updated to match its new tracked status.

Two separate problems follow from this:

- **`docs/external/claude-effort.md` has no reason to be in a portfolio repo.** It is Anthropic-tool
  reference material (effort-level guidance for Claude models), not project documentation, and reveals
  nothing about the order-fulfillment system. A recruiter or engineer browsing `docs/` has no use for
  it, and per the project's own framing (a demonstration of *this developer's* systems-engineering
  work), it's noise at best.
- **`docs/study-guide/` (83 files, ~1.5 MB, including one 15,025-line file) is now permanently part of
  the public repo's `docs/` tree**, despite its own header describing it as personal, gitignored,
  never-committed material. Whether that's a mistake to reverse or a decision to keep and re-document
  is exactly the kind of call this audit is supposed to surface, not resolve unilaterally — see
  Judgment calls.

**Proposed action:**
- `docs/external/`: remove from tracking (`git rm -r --cached`) and restore the `docs/external/`
  ignore rule. There is no plausible argument for shipping Claude-effort tuning notes in this repo.
- `docs/study-guide/`: developer decision required — either (a) restore the original ignore rule and
  untrack it, matching what its own text has claimed all along, or (b) keep it tracked and rewrite
  the now-false "gitignored, never committed" claim in `docs/study-guide/README.md` and
  `study-guide-complete.md` to describe its actual status. Recommend (a): the guide is explicitly
  written as personal build-along material, not portfolio-facing content, and section 4 below shows it
  is already drifting from the frozen contracts it was written against — keeping it tracked commits
  the project to maintaining a second, informal description of the architecture indefinitely.

### 4. `docs/study-guide/` is already drifting from the frozen contracts it narrates

`docs/study-guide/` was committed in its entirety on 2026-08-24. Two frozen-contract changes have
landed since, per `docs/CHANGELOG-contracts.md`:

- 2026-08-25 — the Scenario Service `events` table dedupe key extended to `(topic, partition, offset,
  event_id)`.
- 2026-08-26 (today) — `order-service.yaml` gained `GET /api/prices`.

`docs/study-guide/01-design-contract/` and `docs/study-guide/02-domain/` narrate the design contract
and domain model in tutorial form, covering the same ground as `docs/db-ownership.md` and
`docs/order-state-machine.md`. Neither of the above changes is reflected anywhere in the study guide
(confirmed by `grep` — no `price` hits outside domain-model chapters written before the endpoint
existed, no `event_id` dedupe-key mention). This isn't a defect in the sense of something being wrong
today — the guide narrates history ("Phase 0" through "Sprint 2-3", per its own chapter table) and
was never a living spec — but every sprint that touches a frozen contract now silently makes the study
guide one step more inaccurate, and nothing in the workflow updates it. If it stays tracked (finding
3, option b), this will only compound.

**Proposed action:** covered by the decision in finding 3. If the guide is kept, its README should say
plainly that it is a snapshot as of Sprint 3 and not maintained against later contract changes, so a
reader doesn't mistake it for current.

### 5. `docs/_old/` is referenced as "preserved" but does not exist in the tracked repo

`docs/planning/README.md:25`:

> The original pre-implementation design docs for the MVP, split from a single draft (preserved at
> [`../_old/order-fulfillment-systems-lab-action-plan.md`](../_old/order-fulfillment-systems-lab-action-plan.md)).

`docs/_old/` is correctly excluded by `.gitignore`'s `*/_old/` pattern (confirmed via `git check-ignore
-v`) and `git ls-tree -r HEAD` shows nothing under `_old` — it has never been committed. The file
exists on this machine only. Anyone working from a fresh clone (a new agent session, a recruiter who
clones the repo, a future contributor) follows this link and finds nothing. `docs/agent-reports/sprint-1/phase-0.md`
carries the same reference.

This is different from findings 1-2: it isn't a typo, it's a doc asserting that something is
"preserved" in a place the repository itself doesn't ship. Whether that's intentional (the raw
pre-split draft genuinely is personal scratch material, same reasoning as the study guide) or
accidental determines the fix.

**Proposed action:** developer decision. If `docs/_old/` staying local-only is intentional, reword
`docs/planning/README.md`'s "Known intentionally-dropped content" section (and the sentence at line
25) to say the source draft exists only in the author's local history, not that it is "preserved" in
the repo. If it should ship (it's small — 80 KB — and is genuinely load-bearing for the one paragraph
in `docs/planning/README.md` that references it for content provenance), untrack the `*/_old/` rule
for this specific path or move the one still-cited file out of the ignored directory.

### 6. Frozen contracts, ADRs, and `db-ownership.md`: checked, found current

Read in full or spot-checked against the latest `docs/CHANGELOG-contracts.md` entries:
`docs/db-ownership.md` (outbox tables for all four services, `deferred_transitions`, the `events`
table, and the "Where prices come from" section all match the changelog exactly, including the
2026-08-26 price-endpoint entry), `docs/order-state-machine.md`, `docs/scenarios.md` (all 8 scenarios
present, matching `docs/planning/project-overview.md`'s scope), `docs/architecture-diagram.md`, all 11
ADRs (status/date fields internally consistent, ADR-006's status line correctly carries its Sprint-2
correction, ADR-009/ADR-011 correctly describe what's implemented vs. designed), and
`docs/events/event-catalog.md` against the actual files in `docs/events/schemas/`. No dead links,
no stale claims, no contradictions with the changelog found in any of these. This is a real result,
not an absence of looking: these are the files most likely to actively mislead a reader if wrong
(they're the integration boundary), and they hold up.

**Proposed action:** none. Leave as-is.

### 7. `§`-numbered cross-references outside sprint-1: not a defect

`docs/planning/README.md`'s cross-reference convention warns against citing sections by number because
the original single-draft numbering (`§0`-`§42`) doesn't carry over cleanly across the sprint-1 split
files, which independently restart at `§1`. A repo-wide `grep` for `§` turns up 38 files using it,
including every frozen contract, most ADRs, and `docs/CHANGELOG-contracts.md`. On inspection this is
not the ambiguity the convention warns about: `docs/db-ownership.md`, `docs/order-state-machine.md`,
`docs/reliability-pattern.md`, etc. are each a single file with their own self-contained, linear
numbering, so "`db-ownership.md` §3" is unambiguous — there's exactly one document with that number
sequence. The convention's concern was specifically the sprint-1 prose files that share a numbering
lineage; contracts and ADRs numbering their own sections independently is a different, unproblematic
pattern.

**Proposed action:** none. No change needed; noting this so a future pass doesn't waste time re-flagging
it.

### 8. Structural fit: per-sprint shape holds up, with one honest gap

File counts by sprint:

| | `planning/sprint-N/` | `agent-reports/sprint-N/` |
|---|---|---|
| 1 | 5 | 17 |
| 2 | 4 | 14 |
| 3 | 4 | 1 |
| 4 | 1 | 14 |
| 5 | 1 | 7 |
| 6 | 1 | 7 |
| 7 | 1 (plan) | 3 so far |

The shape scales naturally: sprints with genuine design work (1, 2, 3) produce multiple planning docs;
sprints that are pure execution against an already-stable design (4-7) produce exactly one plan file
each. Neither directory is at a size where flat listing or discovery is a problem, and
`docs/planning/README.md`'s index already does the job of routing a reader to the right sprint without
requiring a further subdivision. No structural change is needed on file-count grounds alone at 7
sprints, and nothing suggests that changes at 10 or 12 sprints either — the shape doesn't accumulate
per-sprint, it's bounded by each sprint's own scope.

The one visible gap: `docs/agent-reports/sprint-3/` has a single file
(`fix-stale-monthly-audit-comment.md`, an unrelated small fix), despite sprint 3 producing two of the
largest deliverables in the whole project — `docs/workflow/*` and all of `docs/study-guide/`. This
isn't a defect to fix; the `agent-report` contract (the `SubagentStop` gate requiring a filed report
under `docs/agent-reports/sprint-N/`) was introduced later, in the 2026-08-24 "workflow enhancements"
commit, so sprint 3's work predates the mechanism that would have produced reports for it. Naming it
here so nobody mistakes the empty-looking folder for lost work or an audit miss.

**Proposed action:** none — no retroactive report-backfilling; the absence is explained, not a gap to
close.

### 9. `docs/CHANGELOG-contracts.md`: current and complete against recent changes

Every contract change found during this audit (outbox extension, `deferred_transitions`, `events`
table, `reserved_quantity` CHECK constraint, dedupe-key extension, price endpoint) has a matching
changelog entry with the required shape (what/why/who's affected). No gaps found between what changed
in the frozen contracts and what the changelog records.

**Proposed action:** none. Leave as-is.

## Deliberately not covered

- **`docs/study-guide/`'s 83 files were not read in full** — the drift check (finding 4) is based on
  targeted `grep`s against known recent contract changes, not a chapter-by-chapter read against
  current code. If the developer chooses to keep the study guide tracked (finding 3), a fuller
  content-accuracy pass is a separate, larger task this audit didn't attempt.
- **`docs/workflow/*` (`user-guide.md`, `commands.md`, `agent-workflow.md`) were spot-checked, not
  fully audited** — confirmed `docs/workflow/README.md`'s skill table matches `.claude/skills/*`
  (`deploy` is the one exception: an empty, untracked directory with no `SKILL.md`, evidently
  in-progress local scratch state for sprint-7's own issue #42, not a docs defect since nothing
  references it yet). Did not line-by-line verify every command description in `commands.md` against
  current preset behavior.
- **ADR content bodies beyond the status/date header were not re-verified against current code** —
  only the header consistency and the specific sections already cross-referenced by
  `docs/CHANGELOG-contracts.md` were checked for currency.
- **`docs/openapi/*.yaml` were not diffed field-by-field against the running services** — checked only
  the specific additions already documented in the changelog (the new `/api/prices` path, the demo
  restore endpoint). A full schema-vs-implementation diff is out of scope for a staleness-of-docs
  review and closer to a contract-conformance audit.
- **No search for broken links *into* `docs/` from outside it** (e.g. `README.md` at repo root, code
  comments) — scope was `docs/` internal consistency per the task description.
- **Anchor-level link checks** (e.g. `file.md#some-heading` where the heading text itself changed) were
  not performed — the link audit checked file-path resolution only, not that referenced heading
  anchors still exist. Given how few file-path links were found broken (2 real, both above), this is a
  plausible remaining gap but a low-yield one to chase further in the time available.

## What changed

Nothing. This is an audit-only task per the task scope ("do not reorganize or delete anything") and
per Sprint 7's plan for issue #40 ("Audit and recommendation only in this pass"). No file was moved,
deleted, merged, or rewritten — including this report's own subject matter (`docs/study-guide/`,
`docs/external/`, `docs/planning/project-overview.md`, `docs/planning/README.md`, and
`docs/planning/sprint-1/execution-plan.md` are all untouched). The only file created is this report.

## How this was verified

Every claim above is a direct read or a direct command against the real repository state (working
tree + `git` history), not inference. Representative commands and their actual output:

Confirming the `project-overview.md` → `execution-plan.md` dead link and its correct target:

```
$ grep -n 'execution-plan.md\|README.md' docs/planning/project-overview.md
36:This document, along with its 6 companion architecture/product docs, defines **what** to build and **why**. For **who builds it, in what order, with which Claude model/effort tier, and using what tooling**, see [`execution-plan.md`](execution-plan.md) — the operational reference for AI agent execution. See [`README.md`](README.md) for the full doc index and reading order.

$ python3 -c "import os; print(os.path.exists('docs/planning/execution-plan.md'), os.path.exists('docs/planning/sprint-1/execution-plan.md'))"
False True
```

Confirming `docs/study-guide/` and `docs/external/` are tracked despite claiming otherwise:

```
$ git ls-files docs/study-guide/study-guide-complete.md
docs/study-guide/study-guide-complete.md

$ git check-ignore -v docs/study-guide/study-guide-complete.md
(no output — not ignored)

$ grep -n 'study-guide' .gitignore
(no output — no rule present)

$ sed -n '6,8p' docs/study-guide/study-guide-complete.md
This is personal study material. It lives in the repo for tidiness but is gitignored
(`.gitignore:6`) and never gets committed or pushed.

$ sed -n '6p' .gitignore
__pycache__/
```

Reconstructing the git-history mechanism:

```
$ git log --oneline -- .gitignore | head -4
6d97da8 merge artifact cleanup
e8b79b8 update gitignore
3cb38e8 update gitignore
351bb87 fix .gitignore never actually being tracked, add docs/study-guide/

$ git show e8b79b8 -- .gitignore
diff --git a/.gitignore b/.gitignore
index ebc4412..186ab19 100644
--- a/.gitignore
+++ b/.gitignore
@@ -5,10 +5,7 @@
 .claude/.agent-marks/
 .claude/scheduled_tasks.lock
 
-docs/_old/
-docs/agent-reports/
-docs/external/
-docs/study-guide/
+*/_old/
 
 # Maven build output ...

$ git show 7a9ff50 --stat | grep -c 'docs/study-guide'
33
$ git show 7a9ff50 --stat | grep 'docs/external'
 docs/external/claude-effort.md                     |    76 +
```

Confirming `docs/_old/` is genuinely absent from the tracked repo despite being cited as "preserved":

```
$ git check-ignore -v docs/_old/order-fulfillment-systems-lab-action-plan.md
.gitignore:9:*/_old/	docs/_old/order-fulfillment-systems-lab-action-plan.md

$ git ls-tree -r HEAD --name-only | grep '_old'
(no output)
```

Confirming the frozen contracts are current against the latest changelog entry (price endpoint,
2026-08-26 — today):

```
$ grep -n 'price\|Price' docs/db-ownership.md
350:### Where prices come from
...
370:Order Service exposes this map read-only at `GET /api/prices` (`docs/openapi/order-service.yaml`) so
```

Confirming the study-guide/changelog drift (finding 4):

```
$ git log -1 --format='%ad' --date=short -- docs/study-guide
2026-08-24

$ grep -n '^## 2026' docs/CHANGELOG-contracts.md | head -2
16:## 2026-08-26 — `order-service.yaml`: new read-only `GET /api/prices`
48:## 2026-08-25 — `db-ownership.md`: Scenario Service `events` table dedupe key extended to include `event_id`
```

The full link-resolution pass (all 1,371 local markdown links across `docs/`, checked by resolving
each relative target against the linking file's directory and testing existence) was run via a
short Python script rather than pasted in full here — it produced the two real dead links in findings
1-2, plus expected internal-anchor mismatches inside `docs/study-guide/study-guide-complete.md` (a
flattened concatenation of the chapter files, so sibling-file relative links like
`(1-boundaries-and-ownership.md)` don't resolve from the concatenated file's own directory — an
artifact of how that file is built, not a genuine dead reference, since the per-chapter source files'
own links do resolve correctly).

## Judgment calls

- **Treated `docs/study-guide/` and `docs/external/` as an open decision, not something to fix
  unilaterally**, even though their own text says they shouldn't be tracked at all. Untracking a
  directory is exactly the kind of irreversible-feeling structural action this phase is scoped to
  propose, not execute — and there's a real argument either way (personal material vs. now genuinely
  part of the repo's history, 83 files deep). Recommended untracking in the findings above, but left
  the final call to the developer per the task's own two-phase framing.
- **Distinguished `docs/external/` from `docs/study-guide/` in the recommendation strength.** Both were
  swept in by the same accidental `.gitignore` collapse, but `docs/external/claude-effort.md` has no
  plausible argument for staying (it's not about this project at all), while `docs/study-guide/` at
  least documents this project, just not in a form intended for the public repo. Recommended removal
  for one and a real decision for the other rather than bundling them.
- **Did not treat `§`-numbered cross-references as a defect** after checking what the convention note
  actually warns about (cross-sprint-1-file ambiguity from a shared numbering lineage) versus what's
  actually happening (single-file self-contained numbering in the frozen contracts). Flagging this
  explicitly as "checked, not a problem" rather than silently passing over it, since the initial `grep`
  hit count (38 files) looked alarming before reading the actual convention text closely.
- **Did not attempt to determine whether `docs/study-guide/` being committed was truly accidental** vs.
  a decision made outside written record. The commit messages (`update gitignore` ×2, `add
  previously-ignored docs to git tracking`) support "accidental side effect of fixing
  `docs/agent-reports/`" as the most consistent reading, but this is inference from git history, not a
  confirmed intent — stated as the most consistent reading in finding 3, not as certain fact.
- **Scoped the link-audit tooling to file-path resolution, not heading-anchor validation**, given the
  time available and that the file-path pass already surfaced the two real defects; a heading-anchor
  pass is listed under Deliberately not covered rather than attempted partially.
