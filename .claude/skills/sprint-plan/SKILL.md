---
name: sprint-plan
description: Plan a sprint - decide its scope with the developer and write it down as it's decided, both the plan doc and the board. Use to start sprint planning, to continue an in-progress one, or to re-plan mid-sprint when scope changes.
argument-hint: [optional sprint number; any constraint such as size or theme]
model: sonnet
effort: high
allowed-tools: Write, Edit, Bash(gh project *), Bash(gh issue *), Bash(gh api *), Bash(git log *), Bash(ls *)
disable-model-invocation: true
---

# Planning a sprint

You are having a working conversation with the developer about what the next sprint should contain,
and writing the result down as you go — the plan doc and the board — rather than holding the decision
in conversation until some later approval step. There is no separate sign-off: the doc and the board
*are* the record, live and reviewable the whole time. Planning is done whenever the developer is
satisfied enough to run `/sprint-open`, not when a particular message gets sent.

This runs in your current session, not a forked subagent — an extended back-and-forth is the point,
and a fork can't carry that across turns the way this conversation can.

## Which sprint

Work it out; do not ask. The sprint directories on disk are the source of truth:

```bash
ls -d docs/planning/sprint-*/ 2>/dev/null | sed 's|.*sprint-||;s|/||' | sort -n | tail -1
```

That is the latest **documented** sprint — but the board can be ahead of the docs (a decision made
and written to the board before this skill's doc-writing habit existed, or before this particular
session got the chance). Check both signals for the candidate number (latest + 1):

```bash
gh project item-list 7 --owner noelwschneider --format json
```

If `docs/planning/sprint-N/sprint-N-plan.md` exists **or** the board already has items tagged
`Sprint N`, you're continuing or reconciling an already-started sprint — go to "Re-entering a plan
already in progress" below before doing anything else, even if the doc itself is missing. Only start
fresh at latest + 1 when neither signal has anything for it.

An explicit number in the arguments overrides this. State which sprint you are acting on in your first
response so a wrong inference is caught immediately.

## Re-entering a plan already in progress

Read `docs/planning/sprint-N/sprint-N-plan.md` and the board's current items for that sprint before
saying anything. Treat what's already there as settled unless the developer is explicitly revisiting
it — don't re-propose from scratch and don't silently discard a prior decision.

When something does change, update the doc and the board to the new, correct state. Don't narrate the
change inside the doc itself (no "previously this said X" — see the documentation rule); state the
change to the developer in conversation, and let the doc just be right.

## Start with the developer's own preferences

Anything they said about this sprint — a theme, a size, specific items they want in or out, "keep it
small this time" — is the strongest input you have. Build around it and say where a preference
conflicts with a criterion below, rather than quietly overriding either.

If they gave no preferences and this is a fresh sprint, ask before doing the full review — cheaper
than proposing against the wrong constraints.

## Read the current state

```bash
gh project item-list 7 --owner noelwschneider --format json
```

Read the previous sprint's plan under `docs/planning/sprint-N/` for what was deferred and why, and
`docs/planning/sprint-2/pre-sprint-planning.md` for the standing backlog vocabulary. Check recent
`git log` for work that happened but may not be reflected on the board.

## Selection criteria, in priority order

1. **Anything time-sensitive or critically important is always presented**, whether or not it fits the
   theme and whether or not you recommend it. Say plainly that it is time-critical and why. Never
   silently drop something in this category for coherence.
2. **Highest overall benefit to the project.** Weigh what materially improves the system, its
   credibility, or the developer's ability to work on it — not what is easiest to finish.
3. **Unblocking value.** Work that removes a dependency for several future items is worth more than
   its own payload. Say explicitly what each candidate unblocks.
4. **Coherence.** Related and similar work grouped into one sprint produces better focus and less
   context-switching. This is a strong preference, not a hard rule — state when you are breaking it
   and why the benefit is worth it.

Tier 1 outranks Tier 2 at equal benefit. `Shelved` items stay shelved unless something has changed
that you can name.

## Write and update as each decision is reached

Don't hold a mental slate and dump it at the end. As soon as something is actually decided:

- **Update `docs/planning/sprint-N/sprint-N-plan.md`** to the current, correct state — input, theme,
  goals, sequencing, dependencies, definition of done — following the shape of the previous sprint's
  plan. Create the file as soon as there's real content for it (even just a theme and one goal), and
  keep it accurate as more gets decided. State current content only; this is a plan doc, not a log.
- **Update the board to match**, using the `board` skill for the mechanics: convert a draft to a real
  Issue once it's genuinely scheduled into this sprint, set `Status: Planned` and `Priority`, set
  `Sprint` (add the option first if this sprint doesn't have one yet — see the `board` skill). When an
  Initiative turns out to have several genuinely separable deliverables, decompose it into native
  GitHub sub-issues now, per `engineering-rules.md`'s Initiative/Task policy — that's a planning-time
  judgment call, not something to defer. Run the `board` skill's duplicate-check and worthiness-check
  for anything new.

Scope should stay thematically coherent — items that don't share the sprint's theme wait for one that
matches them, unless they're urgent enough for the narrow exception in `engineering-rules.md`.

Do not size the sprint to a fixed capacity — this project deliberately has no fixed iteration length.
If it looks large or small, say so and let the developer decide.

## Talking it through

Present a full recommendation before writing anything on a fresh sprint — what's in, why, what it
unblocks, what's time-critical even if left out, what's coherent about it, strong candidates left out
and why. Once the developer is engaging with specifics rather than the shape of the whole thing,
that's the signal to start writing decisions down as they land, not to wait for one final approval.

If effort feels like it's degrading over a long planning conversation, re-invoke `/sprint-plan` to
refresh the tier for your next turn, or use `/effort high` directly.

When the developer is satisfied, tell them the plan doc and board are ready and that `/sprint-open` is
next — theirs to run.
