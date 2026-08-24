---
name: sprint-open
description: Open a new sprint - create its planning doc, carry forward the operating practices, and set up the board. Use at a sprint boundary, after the previous sprint is closed.
argument-hint: [optional sprint number; inferred if omitted]
disable-model-invocation: true
model: sonnet
effort: medium
---

# Opening a sprint

This ritual exists because practices lapse at sprint boundaries. Work through every step; a step that
looks like paperwork is usually the one that quietly stopped happening last time.

## Which sprint

Work it out; do not ask. The sprint directories on disk are the source of truth:

```bash
ls -d docs/planning/sprint-*/ 2>/dev/null | sed 's|.*sprint-||;s|/||' | sort -n | tail -1
```

That is the latest documented sprint. You are opening **the next one**, so add 1 - this skill creates that directory.

An explicit number in the arguments overrides this. State which sprint you are acting on in your first
response so a wrong inference is caught immediately rather than after the work is done.

## 0. Check before you act

Stop and say what is wrong rather than proceeding if any of these fail:

- **An approved slate exists.** If no slate has been agreed in this conversation, stop — run
  `/sprint-plan` first. Opening a sprint with no agreed scope creates a plan doc nobody signed off on.
- **`docs/planning/sprint-N/` does not already exist.** If it does, this sprint is already open;
  confirm what is actually intended before writing anything.
- **The previous sprint is closed.** Check `docs/planning/README.md` and the board. If the previous
  sprint still has items in `In Progress`, say so — opening over an unclosed sprint loses the record of
  what was actually finished.

## 1. Create the sprint directory and plan

`docs/planning/sprint-N/sprint-N-plan.md`, following the shape of the previous sprint's plan: input
(where the scope came from), theme, goals, sequencing, dependencies, definition of done, and what was
considered and left out.

Scope should be thematically coherent. Items that do not share the theme wait for a sprint that
matches them, unless they are urgent enough for the narrow exception in `engineering-rules.md`.

## 2. Record tier deviations only

Model and effort tiers live in the presets under `.claude/agents/`. The plan records only where this
sprint's work needs something different — a goal warranting Opus, a task needing a preset that does
not exist yet. Do not restate the defaults.

## 3. Update the index

`docs/planning/README.md` states which sprint is current. Change it. Agents read it to decide which
docs apply, and a stale index sends them to the wrong sprint.

## 4. Set up the board

- Add the new value to the `Sprint` single-select field if it does not exist yet.
- Update the `Current Sprint` view's filter to the new sprint value.
- Move the sprint's items from `Backlog` to `Planned`, and set their `Sprint`.
- Convert each scheduled item from a draft to a real Issue so commits can reference it.

Use the `board` skill for the mechanics.

## 5. Carry forward what was learned

Check the previous sprint's close notes. Anything identified as a process gap gets fixed now, in the
durable place — a preset, a rule, a hook, or a skill — not written into the new plan as a reminder.
