# User guide

How a sprint runs, and what you do at each stage. Commands are listed in
[`commands.md`](commands.md); the reasoning behind this shape is in
[`agent-workflow.md`](agent-workflow.md).

## The short version

1. **`/sprint-plan`** — a working conversation. What lands gets written to the plan doc and the
   board as you go, not held for a final approval.
2. **`/sprint-open`** — flips the switch. Confirms the doc and board agree, then delegation can start.
3. **For each task** — you write a brief and name a preset. It works in the background. You review the
   diff and commit.
4. **`/sprint-close`** — the board is reconciled against what actually happened.
5. **`/sprint-review`** — leftovers become backlog items, friction gets fixed. Feeds step 1.

You decide scope, tradeoffs, and whether work is good. Everything else is mechanical.

The four sprint commands set their own model and effort — you never tier them manually. Nothing else
does: day-to-day conversation and delegation briefs still need `/tier` or `/effort` when the work
shifts in weight. See [`commands.md`](commands.md) for what each sprint command runs at.

---

## Plan

**You run:** `/sprint-plan`, plus anything you already want in or out.

The sprint number is worked out from the directories under `docs/planning/` — you never type it. Every
sprint command does this, and each states which sprint it is acting on up front so a wrong guess is
caught before it does anything. Pass a number explicitly to override.

Preferences are a first-class input — theme, size, "I want the TLS work in this one," "keep it small,
I'm travelling." Say them up front and the recommendation is built around them.

**This is a conversation, and it writes as it goes.** You'll get a recommendation first — what's in,
why, what it unblocks, what's time-critical even if left out, what's coherent about it. Push back, ask
for a different cut. As specifics get settled — an item confirmed in, a theme agreed — they land in
`docs/planning/sprint-N/sprint-N-plan.md` and on the board immediately, not held until some final
approval. The doc and the board are the record the whole time; there's no separate sign-off moment.
Planning is done whenever you're satisfied, not when a particular message gets sent.

Re-running `/sprint-plan` later — to keep planning, or to re-plan mid-sprint when scope changes — reads
what's already there first and reconciles, rather than starting over.

## Open

**You run:** `/sprint-open`, once planning has settled.

**What happens:** the plan doc and board are already there from planning — this step checks they
actually agree with each other, flips the current-sprint pointer in `docs/planning/README.md`,
re-filters the Current Sprint view, and confirms in-scope items are `Planned` rather than still sitting
in `Backlog`.

**Your involvement:** little — this is deliberately the thin, mechanical step. If it flags a mismatch
between the plan doc and the board, that's the one thing worth your attention here.

**This must happen before any of the sprint's work is delegated.** Report paths and "which sprint is
current" both resolve from the pointer this flips — delegate first and the output files against the
previous sprint.

## Delegate, verify, land

The repeating middle. Once per task.

**You delegate:** name a preset and give it the brief — exact paths with line numbers, what's already
been ruled out, and what it should *not* touch. `/tier` picks the preset if it isn't obvious;
`/delegate` gives you the checklist. Don't restate verification rules or the report format; those are
in the preset.

Work runs in the background. `/tasks` shows what's running.

**Verification is partly automatic.** The agent cannot finish without filing a report containing real
command output — that gate runs itself and you never invoke it. It proves the agent showed its work.
It cannot tell you the work is right.

**So for anything consequential, you delegate a second pass** to `verifier`. It runs the suite itself
and reports pass or fail per criterion. It can't edit source, so it can only tell you what it found.

**You land it.** Read the diff, not the report's description of the diff. Read the report's
`## Deliberately not covered` section first — that's where the honest signal is. Commit in coherent
units, then `/board` to move the item to Done.

**Your involvement is the judgment:** is this the right tradeoff, does it match what you meant. The
mechanical checking is delegated. You are not the first line of defense.

## Close

**You run:** `/sprint-close`, when the sprint's goals are done.

**What it handles:** the board reconciled against reality — finished work moved to `Done`, unfinished
work returned to `Backlog` with its Sprint cleared so it doesn't read as delivered, and **unplanned
work added retroactively** by checking commits against the board. Goals confirmed against the artifact
rather than a report. Stale docs corrected.

**What it does not handle:** deciding what changes because of any of it. That's review.

**Your involvement:** confirm the goals are genuinely met. Close is bookkeeping; it can't tell you a
goal was met in spirit.

## Review

**You run:** `/sprint-review`, as a separate pass.

**What it handles:** every agent report's `## Deliberately not covered` section swept into backlog
items — the richest source of follow-up work and the easiest to write once and never read again. Process
gaps routed to the layer that should hold them: a hook, a preset, a rule, a skill. Workflow refinements
made now, while the evidence is fresh.

**Your involvement:** answer one question honestly — **what did you route around?** The part you
quietly worked around is the part that's wrong, whether or not it ever broke. It won't appear in any
failure log.

Its output feeds the next `/sprint-plan`.

---

## Order, and what happens if you get it wrong

Only two orderings actually matter:

- **Open before delegating.** Otherwise reports file against the previous sprint and the board has no
  value to set.
- **Close before review.** Review reads a reconciled board; an unreconciled one makes it miss items.

Everything else is safe in any order, and each sprint command checks its own preconditions and stops
rather than acting on a state that doesn't make sense. None of them can be triggered by an agent — you
invoke them all deliberately.

Two out-of-order uses are worth doing on purpose:

- **`/sprint-plan` mid-sprint** re-plans when scope changes. It reads what's already on the
  board and in the plan doc first and reconciles against that, rather than re-proposing from
  scratch — safe to invoke repeatedly for exactly that reason.
- **`/sprint-review` mid-sprint** fixes workflow friction the moment you notice it. Waiting for a
  boundary to fix something that's annoying you now defeats the point of the stage.

## When something goes wrong

| Situation | What to do |
|---|---|
| An agent stopped early or was cut off | Ask it to continue by name — it keeps its history and resumes. Don't start a fresh one and lose the partial work |
| An agent is blocked by the report gate | Read the message; it names what's missing. Usually it hasn't verified against a running system yet |
| An agent lacks a tool it needs | Add it to that preset's `tools` list in the `noel-workflow` plugin's `agents/` — note this changes it for every project using the plugin |
| Board writes fail with a bad ID | Option IDs changed. `/board` has the refresh command |
| You want to undo Claude's edits | `/rewind` — though background agents' edits fall outside checkpoints, so use git for those |
| Context is getting long | `/compact`, `/clear`, or `/context` to see what's consuming it |
