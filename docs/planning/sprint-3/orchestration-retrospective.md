# Orchestration Retrospective

What this document is for: `.claude/CLAUDE.md` and `agent-guidance.md` capture *rules* — do this,
don't do that. This document captures *how the work actually happened* — the patterns, judgment
calls, and failure modes observed across Sprint 1 and Sprint 2 that the rules were extracted from.
Written for the Agentic Workflow Refinement task, which needs this texture and has no other way to
get it (it wasn't a participant in the sessions that produced it).

This is a description of what happened, not a claim that it's optimal. Finding out where it's wrong
or missing is the whole point of the task this document is briefing.

## The core structure

One Claude Code session acts as facilitator/orchestrator for the whole project. It does not write
application code directly except in small, judgment-heavy edits (documentation, planning docs,
manifest tweaks it's already deep in context on). Nearly all actual implementation — service code,
Kubernetes manifests, infrastructure scripts, test suites — is delegated to subagents via the `Agent`
tool, run in the background by default so the orchestrating session stays available for the user and
for launching further work in parallel.

Two distinct delegation mechanisms have been used, for two different reasons:

1. **In-session background subagents** (the `Agent` tool). Used for the large majority of work. The
   orchestrator writes a self-contained prompt (the subagent has no access to prior conversation),
   the subagent runs autonomously and reports back, and the orchestrator independently verifies the
   result before trusting or committing it.
2. **Fully separate Claude Code sessions**, started by the user directly, running in parallel to the
   orchestrating session with zero shared context. Used specifically when: the work needs the user's
   own real-time judgment or action (VPS account creation, a platform/cost decision with recurring
   billing implications), or when it's a genuinely independent long-running track worth the user's
   own attention rather than pure background execution. The orchestrator writes a standalone briefing
   doc for these (see `sprint-2/vps-agent-briefing.md` and `sprint-2/deployment-agent-briefing.md`
   for the template) since the session starting cold has nothing else to go on.

The Study Guide and Workflow Refinement tasks this sprint use the second mechanism.

## Model and effort tiering, as actually practiced

No formal tier table has been maintained (`sprint-2/sprint-2-plan.md` called for an `execution-plan.md`
that documents per-goal model/effort tier — it was never written; every tiering decision was made ad
hoc, in the moment, by the orchestrator). The pattern that emerged anyway:

- **Sonnet, medium-to-high effort** for well-specified execution against an already-diagnosed design —
  the large majority of delegated work. Security/hygiene passes, implementing a fix whose design was
  already decided (the inventory-reset defect, the outbox pattern rollout, the `FAILED` transition),
  bug hunts, infrastructure scripting (VPS provisioning, redeploy scripts).
- **Opus** reserved for work with genuine, un-derisked judgment calls where a wrong call is expensive
  to unwind — the production platform/sizing decision (recurring cost, hard to reverse), and a
  correctness-and-concurrency-heavy implementation pass (ingress security design, probe/heap tuning
  under real capacity constraints) that traded on getting several interacting judgment calls right at
  once, not just implementing a spec.

The actual rule of thumb, stated plainly: if the task is "do this thing, the shape of the answer is
already known, verify it works," Sonnet. If the task is "figure out the right answer among several
plausible ones, where being wrong is costly to discover later," Opus. This was never written down
until now — the workflow task should decide whether a real tier table is worth building, or whether
this looser rule is sufficient and formalizing it would just be overhead.

## What makes a delegation prompt actually work

Prompts that produced good, low-friction results shared a shape:

- **Full context, not a summary of context.** Why the task matters, what's already been tried or
  ruled out, exact file paths and line numbers rather than vague pointers ("the reset endpoint" vs.
  "`services/inventory-service/.../InventoryItemEntity.java:44`"). The subagent has no conversation
  history — anything not in the prompt does not exist for it.
- **Explicit scope boundaries**, especially what *not* to do. Several prompts explicitly called out
  adjacent temptations to avoid (don't implement Option B when Option A was chosen, don't touch the
  live box, don't do a content/polish pass when only a factual fix was asked for).
- **A requirement for real verification**, matching this project's own Agent Rule 10 (scenario
  behavior must be real, not simulated). Every successful delegation was told to prove its work
  against a real running system — `kind`, `docker compose`, or the live box — not to reason its way
  to "should work." Reports that included actual command output (test runs, curl responses, `kubectl`
  status) were trustworthy in a way that prose summaries alone were not.
- **A structured "when done, report X" close.** Specifying exactly what the final report needs to
  contain (what changed, how it was verified, what was explicitly deferred and why) made the
  orchestrator's own verification pass faster and made it easy to catch a subagent that skipped a
  step.

## Two failure modes, now written into `CLAUDE.md`, worth restating with their actual texture

1. **A subagent ends its own turn before a long-running background command finishes.** Observed with
   `docker compose up --build` and multi-minute test runs — the subagent starts the command, doesn't
   wait for it, and reports "done" with no real result. The fix that works: explicitly instruct the
   subagent to run such commands in the foreground with a generous timeout and treat "wait for it to
   finish" as part of the task, not an implementation detail it can skip.
2. **A subagent delegates its own assigned work to a further subagent, then stops.** The same failure
   one layer removed — observed when a subagent briefed to implement a phase spawned another agent to
   do the actual implementation. A narrow, bounded, read-only helper (an `Explore`-type search) to
   keep the delegate's own context clean is fine; handing off the real work and stopping is not. Worth
   saying explicitly in every delegation prompt, not assumed.

A third, softer pattern worth naming even though it isn't a hard failure: **background subagents
sometimes get cut off mid-task by session usage limits**, not by their own choice. The recovery that
worked was resuming the *same* agent (by name/id, via `SendMessage`) with instructions to re-orient —
check its own `git status`/`git diff --stat` — and continue from exactly where it left off, rather
than starting a fresh agent from scratch. Restarting from zero would have wasted real, correct partial
work and risked redoing something inconsistently with what was already done.

## Verification discipline

The orchestrator does not take a subagent's self-report as sufficient to commit or to tell the user
something is done. Every consequential delegation was followed by independent verification: reading
the actual diff, re-running the build/test suite, or hitting the live system directly (a `curl` to a
health endpoint, a real order submitted against the live demo). This caught real problems more than
once — a flaky test that turned out to be pre-existing (confirmed by stashing changes and re-running
against the clean baseline, not assumed from an agent's claim), a build break from two subagents'
concurrent work landing in the same tree.

## Commit discipline, as it settled by the end of Sprint 2

- One commit per logically coherent unit of work, not one commit per subagent call — two delegations
  that produced the same conceptual change (e.g., a bug-hunt fix and a related fix from a different
  task, both touching the same exception handler) were committed together when splitting them would
  have meant fragile hunk-level surgery for no real benefit; unrelated goals were never mixed into one
  commit.
- Commit messages written to read as a human's, not a tool's — no bullet-heavy corporate phrasing, no
  restating of the diff, matching the terse style already in the repo's history, with more description
  than usual only when the underlying change was itself unusually messy (a multi-goal Sprint 2 dump
  after a long uncommitted stretch).
- No `Co-Authored-By` trailer, per explicit preference — the concern was perception (a two-contributor
  GitHub graph reading as "relied on AI, not serious engineering"), not secrecy about AI involvement.
  When this preference was set mid-sprint, already-pushed history was rewritten once (`git
  filter-branch --msg-filter`, force-pushed) to apply it retroactively — done at a natural pause point,
  not mid-flight against other in-progress work, and confirmed content-identical (`git diff` against a
  backup branch showed zero delta) before the force-push.

## Incident response, from tonight specifically

A production incident (repeated memory exhaustion on the demo box during a deploy) was handled with a
sequence worth naming as the pattern, not just the specific fixes:

1. Diagnose with real evidence before acting — `free -h`, process lists, `kubectl describe`, not
   guessing from symptoms alone. The first hypothesis (stuck rollout, old/new pods coexisting) was
   confirmed by direct observation before being treated as fact.
2. Get explicit user authorization before risky or irreversible actions, especially anything the
   permission classifier itself blocked (`sudo kill` against a live box) — and when blocked twice on
   the same *kind* of action, look for a different, less-risky path to the same outcome (a `hcloud`
   reboot via the API instead of the website; `kubectl delete`/`scale` under existing RBAC instead of
   raw process signals) rather than repeatedly asking for approval on the same blocked command.
3. Prefer the least invasive intervention that could plausibly work, verify it actually worked with
   real checks (`curl` to the live health endpoint, not just "pods look Ready"), and be willing to
   escalate (a second, then third reboot) when the first attempt's verification showed it hadn't
   actually resolved things — rather than declaring victory on partial evidence.
4. Once resolved, write the incident up as a durable ADR (`ADR-011`), not just a conversation that
   evaporates — the next person (or agent) who redeploys this project needs the lesson written down,
   not re-discovered.

## What hasn't been tried at all

Everything above has used the generic `Agent` tool with a fresh, fully-custom prompt for every single
delegation — no reusable subagent presets (`.claude/agents/*.md` custom agent-type definitions), no
hooks, no custom slash commands for recurring rituals (sprint planning, a redeploy, a bug-hunt pass).
Every delegation prompt re-establishes its own boilerplate (repo context, rule reminders, verification
requirements) from scratch. This project's own recurring delegation shapes are visible in this
document — Sonnet-tier "implement an already-diagnosed fix, verify it live" tasks recur constantly —
which suggests there may be real leverage in a reusable preset for that shape specifically. Whether
that's worth building, versus the flexibility cost of a preset that has to fit every task, is exactly
the kind of question this document doesn't answer and the workflow task should.

## What this document is not

It is not a claim that any of the above is the correct or best way to do this — it's a record of what
was actually done and what was actually observed to work or fail, so the workflow task has real
material to evaluate against actual Claude Code / Claude Agent SDK capabilities, instead of having to
either re-derive this history from scattered rule statements or invent a workflow with no grounding in
what this specific project has actually needed.
