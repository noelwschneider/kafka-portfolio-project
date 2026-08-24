---
name: board
description: Add to or update the GitHub Project board - create backlog items, move status, set priority and sprint, convert drafts to real issues, and log unplanned work. Use whenever work is identified, started, or finished.
argument-hint: [what to add or change]
allowed-tools: Bash(gh project *), Bash(gh issue *), Bash(gh api *)
---

# Kafka Portfolio Project board

Board: https://github.com/users/noelwschneider/projects/7 — owner `noelwschneider`, number `7`,
project id `PVT_kwHOB38DIc4BhEqT`.

The board tracks live status. `docs/planning/sprint-N/sprint-N-plan.md` owns goals, dependencies, and
rationale. Do not duplicate rationale into board items, and do not track live status in the markdown.

## Fields

| Field | Field id | Values (option id) |
|---|---|---|
| Status | `PVTSSF_lAHOB38DIc4BhEqTzhgB0vE` | Backlog `f75ad846`, Planned `9935e4cd`, In Progress `47fc9ee4`, Done `98236657` |
| Priority | `PVTSSF_lAHOB38DIc4BhEqTzhgB9Dw` | Tier 1 `81a17eaa`, Tier 2 `97537018`, Shelved `7c38e779` |
| Sprint | `PVTSSF_lAHOB38DIc4BhEqTzhgB9D0` | Sprint 2 `e3cf966f`, Sprint 3 `f8e19f28` |

Option ids change if a field is edited. Re-read them rather than trusting this table when a write
fails:

```bash
gh project field-list 7 --owner noelwschneider --format json
```

## Common operations

List items, with their item ids:

```bash
gh project item-list 7 --owner noelwschneider --format json
```

Add a backlog entry as a draft item:

```bash
gh project item-create 7 --owner noelwschneider --title "<title>" --body "<one-line scope>"
```

Set a single-select field on an item:

```bash
gh project item-edit --project-id PVT_kwHOB38DIc4BhEqT --id <item-id> \
  --field-id <field-id> --single-select-option-id <option-id>
```

## Before creating anything: check for an existing item

List the full backlog and read titles *and bodies* for overlap — not just exact title matches. A
gap phrased differently from an existing item is still the same item.

```bash
gh project item-list 7 --owner noelwschneider --format json
```

- **Same scope as an existing item:** do not create a duplicate. Report that it already exists and
  where.
- **Genuinely overlaps but adds something the existing item doesn't cover:** say so rather than
  deciding unilaterally. Creating a second item and letting them silently drift apart is worse than
  asking which one should hold the additional scope.
- **Related but distinct** (shares a component or a root cause, not the actual work): create it, and
  reference the related item in the body so the connection isn't lost.

When genuinely unsure whether something overlaps, say so and let the developer decide rather than
guessing either way.

## Is this worth a card?

Not everything worth mentioning is worth tracking. Before creating an item, check:

- **Would it be lost otherwise?** If it is already written down somewhere durable — a contract gap in
  `docs/CHANGELOG-contracts.md`, a rule in `.claude/rules/`, a decision in an ADR — a board item is a
  second, driftable copy of the same fact. Don't create one.
- **Is it Task-sized?** A concrete, independently-completable unit someone could pick up without
  first having to figure out what it even means. If it's still a vague direction rather than a
  scoped task, say so instead of creating a stub item that will need to be redefined before anyone
  can act on it.
- **Is it small enough to just fix?** A typo, a one-line stale note, a broken link. Fixing it costs
  less than tracking it. Do that instead, and mention it under `## What changed` in whatever report
  you're already filing.

A defect or gap named honestly in a report's `## Deliberately not covered` is the common source for
this — and naming it there was already the right call. Converting it to a board item only when it
clears these three checks is what keeps the backlog signal instead of noise.

## Rules

**Drafts until scheduled.** Backlog entries start as draft items — cheap, no repo noise. Convert to a
real repo Issue only when something is pulled into a sprint, so it can be referenced from commits
(`Closes #12`) and the Issues tab shows only work actually being done.

**Two levels, not three.** An *Initiative* is a big-picture objective; a *Task* is one concrete,
independently-completable unit sized to a single delegation. Most items are already Task-sized and the
Issue is the Task. Decompose an Initiative into sub-issues, using GitHub's native parent/child links
rather than a label, only when it genuinely has multiple separable deliverables — and decide that at
sprint-selection time when the real seams are visible, not upfront.

**Log unplanned work too, retroactively if needed.** A board that reflects only planned work becomes
inaccurate the first time something urgent jumps the queue.

**A new item needs a title, a Priority, and a Status.** Set Sprint only when it is actually scheduled.
