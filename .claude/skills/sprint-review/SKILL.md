---
name: sprint-review
description: Look forward at the end of a sprint - capture new backlog items, route process gaps to the layer that should hold them, and refine the workflow itself. Use after sprint-close, before planning the next sprint.
argument-hint: [optional sprint number; inferred if omitted]
disable-model-invocation: true
model: sonnet
effort: high
---

# Reviewing a sprint

`/sprint-close` makes the record of what happened accurate. This step decides what changes because of
it. Run it as a separate pass — a close feels finished once the board is green, and the forward-looking
work gets skipped if it is bundled into the same step.

Its output is the input to `/sprint-plan`.

## Which sprint

Work it out; do not ask. The sprint directories on disk are the source of truth:

```bash
ls -d docs/planning/sprint-*/ 2>/dev/null | sed 's|.*sprint-||;s|/||' | sort -n | tail -1
```

That is the latest documented sprint. You are reviewing **that one** - the sprint just closed.

An explicit number in the arguments overrides this. State which sprint you are acting on in your first
response so a wrong inference is caught immediately rather than after the work is done.

## 0. Check before you act

Confirm the sprint has been closed — the board reconciled, unplanned work added. If it has not, say so
and offer to run `/sprint-close` first: an unreconciled board makes step 1 miss items that were worked
but never tracked.

Running this mid-sprint is legitimate and does not need a close. Fixing friction the moment you notice
it is the point of the stage. In that case skip step 1's board sweep, which needs a settled board, and
go straight to steps 2 and 3.

## 1. Capture new backlog items

Sweep for work the sprint surfaced but did not do:

- `## Deliberately not covered` in every agent report filed this sprint. This is the richest source and
  the easiest to lose — those gaps were named honestly and then never read again.
- `## Contract gaps found` sections, and anything in `docs/CHANGELOG-contracts.md` left open.
- TODOs, follow-ups, and "worth doing later" notes in the sprint's commits.
- Anything you worked around rather than fixed.

Use the `board` skill for each candidate — it covers checking for an existing item first and deciding
whether something clears the bar for a card at all. A gap that stays in a report and never becomes
one, when it should have, is a gap nobody will act on.

## 2. Route process gaps to a durable layer

For anything that went wrong in *how* the work happened, decide where the fix belongs and put it there
now. A gap recorded in a sprint doc is a gap that will recur.

| The gap is | Fix it in |
|---|---|
| An agent behaved wrongly despite being told not to | a hook, or a preset tool restriction |
| A standing instruction was inconsistently applied | a preset body or `.claude/rules/` |
| A procedure was re-derived from scratch each time | a skill |
| A project fact was missing or wrong | `.claude/CLAUDE.md` |
| A preset lacked a tool, or had the wrong tier | that preset's frontmatter |

## 3. Refine the workflow

Ask directly, and answer honestly:

- Which part of the workflow did you route around, ignore, or find annoying? That is the part that is
  wrong, whether or not it is the part that failed.
- Did a gate block something it should have allowed, or allow something it should have blocked?
- Did a preset get used for work it does not fit? Either the brief was wrong or the roster is.
- Was a skill invoked repeatedly with the same adjustment? Fold the adjustment in.
- Did anything get done by hand that recurs often enough to encode?

Make the changes now, while the evidence is fresh. Small, immediate refinements are the whole point of
this step existing — the alternative is letting friction accumulate until a whole sprint has to be
spent on workflow.

## 4. Hand off to planning

State briefly what changed and what the next sprint should know: new backlog items added, workflow
changes made, and anything unresolved that planning should weigh.
