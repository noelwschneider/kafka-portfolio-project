---
name: sprint-close
description: Close out a sprint - reconcile the board against what actually happened and leave the record accurate. Use when a sprint's goals are complete, before running sprint-review.
argument-hint: [optional sprint number; inferred if omitted]
disable-model-invocation: true
model: sonnet
effort: high
---

# Closing a sprint

## Which sprint

Work it out; do not ask. The sprint directories on disk are the source of truth:

```bash
ls -d docs/planning/sprint-*/ 2>/dev/null | sed 's|.*sprint-||;s|/||' | sort -n | tail -1
```

That is the latest documented sprint. You are closing **that one** - it is the sprint currently in flight.

An explicit number in the arguments overrides this. State which sprint you are acting on in your first
response so a wrong inference is caught immediately rather than after the work is done.

## 0. Check before you act

- **The sprint you were given is the current one** per `docs/planning/README.md`. If not, confirm which
  sprint is meant before touching the board.
- **Nothing is still `In Progress`.** List anything that is and confirm with the developer before
  continuing. Closing over in-flight work moves it back to `Backlog` and clears its Sprint, which
  silently erases that it was ever started.

## 1. Reconcile the board against reality

List the sprint's items and check each against what actually landed:

```bash
gh project item-list 7 --owner noelwschneider --format json
```

- Everything finished moves to `Done`.
- Anything not finished goes back to `Backlog` with its `Sprint` cleared, so it does not read as
  delivered.
- **Unplanned work gets added retroactively.** Urgent work that jumped the queue, incidents, and
  follow-up fixes all count. A board showing only planned work is a board that is quietly wrong.

Check the commit history for the sprint's window to find work that never made it onto the board.

## 2. Confirm the goals are actually met

For each goal, confirm the deliverable exists and works — not that a report claims it does. Delegate
to the `verifier` preset for anything consequential rather than accepting the implementing agent's
own account.

## 3. Leave the record accurate

ADRs for architectural decisions made during the sprint. Update any doc the sprint made stale —
especially README claims about how the project is run, and any pointer to a file that moved.

## 4. Hand off to review

Closing establishes what happened. Deciding what changes because of it is `/sprint-review` — run it
next, as a separate pass.
