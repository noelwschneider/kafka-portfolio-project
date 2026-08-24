---
name: sprint-plan
description: Review the project backlog and propose a candidate slate for the next sprint, with reasoning. Use at the start of sprint planning, before the sprint is opened.
argument-hint: [optional sprint number; any constraint such as size or theme]
context: fork
background: false
model: sonnet
effort: high
allowed-tools: Bash(gh project *), Bash(gh issue *), Bash(git log *), Bash(ls *)
disable-model-invocation: true
---

# Proposing a sprint slate

You are reviewing the backlog and recommending what the next sprint should contain. You are not
committing to anything — the developer decides. Your job is to make that decision easy by surfacing
the right candidates with honest reasoning.

Read-only. Never modify the board or any planning doc from this skill, whenever it is run — it is
safe to invoke mid-sprint to re-plan when scope changes.

## Which sprint

Work it out; do not ask. The sprint directories on disk are the source of truth:

```bash
ls -d docs/planning/sprint-*/ 2>/dev/null | sed 's|.*sprint-||;s|/||' | sort -n | tail -1
```

That is the latest documented sprint. You are planning **the next one**, so add 1.

An explicit number in the arguments overrides this. State which sprint you are acting on in your first
response so a wrong inference is caught immediately rather than after the work is done.

## Start with the developer's own preferences

Anything they said about this sprint — a theme, a size, specific items they want in or out, "keep it
small this time" — is the strongest input you have. Build the slate around it and say where a
preference conflicts with a criterion below, rather than quietly overriding either.

If they gave no preferences, ask whether they have any before doing the full review. It is cheaper
than proposing a slate against the wrong constraints.

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

## What to produce

- **A recommended slate**, each item with: what it is, why it earns a place, what it unblocks, and a
  rough sense of size relative to the others.
- **The theme** the slate holds together on, in a sentence. If the slate has no coherent theme, say so
  rather than inventing one.
- **Time-critical items**, called out separately, including any you are not recommending for this
  sprint — with what happens if they keep waiting.
- **Dependencies within the slate**, and anything that must be sequenced.
- **Strong candidates you left out**, briefly, and what would earn them a place next time.
- **Open questions** that need a decision before the sprint can be opened.

Do not size the sprint to a fixed capacity — this project deliberately has no fixed iteration length.
If the slate looks large or small, say so and let the developer decide.

## Getting to sign-off

The first slate is a proposal, not an answer. Expect to revise it — a different cut, a different size,
a candidate reconsidered. Ask directly whether the slate is approved and treat anything short of a
clear yes as still open.

Nothing is written until they approve. When they do, `/sprint-open` is the next step and it is theirs
to run.
