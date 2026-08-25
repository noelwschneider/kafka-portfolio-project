---
name: sprint-open
description: Open a sprint that has already been planned - flip the current-sprint pointer, confirm the plan doc and board actually agree, carry forward what was learned, and give the go-ahead for delegation to start targeting it. Use once planning is settled, right before the first task of the sprint gets delegated.
argument-hint: [optional sprint number; inferred if omitted]
disable-model-invocation: true
model: sonnet
effort: medium
---

# Opening a sprint

Planning already produced the plan doc and the board state — `/sprint-plan` did that, live, as
decisions were made. This step is narrower and matters precisely because it's the one thing worth
keeping deliberate: report paths and "which sprint is current" both resolve from the pointer this
flips, so it has to happen once, on purpose, not drift in gradually the way planning does.

## Which sprint

Work it out; do not ask. The sprint directories on disk are the source of truth:

```bash
ls -d docs/planning/sprint-*/ 2>/dev/null | sed 's|.*sprint-||;s|/||' | sort -n | tail -1
```

That is the latest documented sprint — the one you're opening, since planning already created its
directory.

An explicit number in the arguments overrides this. State which sprint you are acting on in your first
response so a wrong inference is caught immediately.

## 0. Check before you act

Stop and say what is wrong rather than proceeding if any of these fail:

- **`docs/planning/sprint-N/sprint-N-plan.md` exists and is more than a stub.** If planning hasn't
  produced a real plan doc yet, stop — run `/sprint-plan` first, or continue it if it's mid-way.
- **The plan doc and the board actually agree.** Every item the plan doc lists as in-scope should be
  on the board tagged `Sprint N`; every board item tagged `Sprint N` should appear in the plan doc. If
  they've drifted apart, say exactly where and let the developer decide which one is right rather than
  silently trusting either.
- **The previous sprint is closed.** Check `docs/planning/README.md` and the board. If the previous
  sprint still has items in `In Progress`, say so — opening over an unclosed sprint loses the record of
  what was actually finished.

## 1. Flip the current-sprint pointer

`docs/planning/README.md` states which sprint is current. Change it. Agents read it to decide which
docs apply and where their reports go — a stale index sends them to the wrong sprint.

## 2. Finish the board setup

- Update the `Current Sprint` view's filter to the new sprint value.
- Confirm every in-scope item's `Status` is `Planned` (not left in `Backlog`) — this is a check, not a
  bulk action; planning should already have set these.

## 3. Carry forward what was learned

Check the previous sprint's close and review notes. Anything identified as a process gap should
already have been fixed at review time, in the durable place — a preset, a rule, a hook, or a skill.
If something was flagged but never actually fixed, fix it now rather than let it carry a second time.

## 4. Give the go-ahead

Tell the developer the sprint is open — delegation can now target it, and reports will file to the
right place. This is the actual signal this whole skill exists to produce.
