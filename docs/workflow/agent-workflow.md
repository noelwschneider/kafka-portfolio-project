# Agent Workflow

How agentic work on this project is planned, delegated, verified, and landed.

This document owns the intent and reasoning. The executable pieces live in the `noel-workflow`
plugin — subagent presets, skills, and the verification gate — and are tracked in version control
in that plugin's own source so they cannot silently lapse. This repo keeps only what is specific to
it: the `deploy` and `dev-box` skills, and `.claude/workflow.json`, which supplies the project's
coordinates to the plugin's logic.

## The problem this solves

Every good practice this project has established was written into a document that a human had to
remember to carry forward. Each one lapsed at the moment nobody was looking:

- `sprint-1/execution-plan.md`'s **Model / Effort Tier Assignments** is a detailed per-workstream
  table with rationale, an escalation rule, and an operational note that effort reverts to High
  unless passed explicitly. Sprint 1 agents cited it directly. Sprint 2 had no execution plan, so
  the table stopped applying — and the tiering it encoded stopped happening.
- The same file's **Verification Passes** section specifies a fresh-context pass, distinct from the
  implementing agent's self-report, that confirms exit criteria actually execute and reports
  pass/fail per criterion. Its stated purpose is catching an agent that says "done" when a criterion
  was never exercised. `docs/agent-reports/` contains no verification reports. The pass was designed,
  never run, and the failure it was designed to catch happened anyway in Phases 7 and 8.
- The agent-report structure used in Phases 0–3 — files changed, judgment calls, exit criteria with
  reproduction steps, contract gaps found, deliberately deferred — produced the most useful documents
  in the repository. It decayed as the sprint went on, because it lived in whatever delegation prompt
  was typed that day rather than in a file.

The failures are not judgment failures. The judgment was right the first time. They are **durability**
failures. So the organizing principle here is:

> Encode each practice at the most durable layer that can hold it, and never at a layer that depends
> on someone remembering.

## The four layers

| Layer | Binding? | Holds | Lives in |
|---|---|---|---|
| Hooks | Enforced — cannot be skipped or argued with | Gates that must not be optional | plugin `hooks/` + `hooks/hooks.json` |
| Subagent presets | Applied automatically every run | Standing instructions and tool limits for a task shape | plugin `agents/*.md` |
| Skills | Loaded on demand, free until invoked | Procedures and reference knowledge | plugin `skills/*/SKILL.md`, plus `.claude/skills/*/SKILL.md` for project-specific ones |
| CLAUDE.md and rules | Always in context, advisory | Project facts and standing rules | `.claude/CLAUDE.md`, `~/.claude/rules/` |
| Project config | Read by the layers above | This repo's coordinates: roots, board, deploy verbs, contract rules | `.claude/workflow.json` |

Two rules follow from the table:

**Instructions are not enforcement.** A preset that says "do not delegate your own work onward" is the
same kind of text that already failed twice. Where a constraint actually matters, express it as a tool
restriction or a hook, not as a sentence. The `implementer` preset does not merely discourage onward
delegation — its `tools` allowlist admits only `Agent(Explore)`, so handing off implementation is
mechanically unavailable.

**Prefer a skill to an agent when only the knowledge needs deferring.** A separate agent is warranted
when the *work* needs isolation: parallelism, a different model or effort tier, a restricted toolset,
or a long job that should not block. Wanting a procedure out of the main context is a skill, not an
agent — it costs nothing until invoked and introduces no interface to maintain.

## The sprint cycle

Work runs sprint to sprint, and the cycle closes: a sprint's review is what the next sprint's planning
reads. The boundary steps are not bookkeeping — they are where practices were lost before they were
written into durable layers, so they are part of the workflow rather than around it.

```
plan -> open -> [ delegate -> verify -> land ]* -> close -> review -> plan
```

A sprint's full scope is decided during planning, executed to completion, then the next sprint is
planned from whatever is left plus whatever was learned. This is not a fixed-length or fixed-capacity
iteration in the Scrum sense — there is no established limit on how much belongs in one sprint, and
that is being felt out empirically rather than fixed in advance. Do not assume a sprint should be
small because earlier ones were, or object to a large one on that basis alone.

Scope should stay thematically coherent. Work that does not share the sprint's theme waits for one
that matches it.

Within a sprint, everything scoped into it is completed before anything that was not — with one narrow
exception. Urgent or unblocking work that emerges mid-sprint, a production-breaking bug or a dependency
a planned item turns out to need, may jump the queue. It takes the same delegate/verify/land path, and
`close` logs it retroactively so the board does not quietly become a record of planned work only.
Routine "while I'm in here" additions are not covered by this and still wait for the next planning
cycle, however small they seem.

### Plan

Planning is a working conversation, and the plan doc and the board are written *during* it, not after
some separate approval — the doc and the board are the record, live and reviewable throughout, rather
than a decision held in conversation until a sign-off message. This runs inline in the current
session rather than a forked subagent, because an extended back-and-forth is the point and a fork
can't carry that across turns.

Sprint scope and rationale stay in `docs/planning/sprint-N/sprint-N-plan.md`; live status stays on the
GitHub Project board. Re-invoking planning mid-sprint, when scope changes, reconciles against what's
already there rather than re-proposing from scratch — read the existing plan doc and board state
first, and only change what's actually being revisited.

Each sprint additionally carries a **model and effort tier assignment** for its work. That assignment
no longer needs to be rewritten per sprint: the presets carry the default tiers, and a sprint plan only
records deviations. Effort must be set explicitly or it reverts to High. Preset frontmatter sets it,
which is why the tiering now survives a sprint boundary.

### Open

Opening is narrow on purpose: flip the current-sprint pointer in `docs/planning/README.md`, confirm
the plan doc and the board actually agree, and give the explicit signal that delegation can now target
this sprint. Report paths and "which sprint is current" both resolve from that pointer, so it has to
flip once, deliberately — everything else about a sprint can be built up gradually during planning,
but this one action can't drift in the same way without misfiling work against the wrong sprint.

### Delegate

Delegation goes to one of four presets, chosen by task shape rather than by which service the code
lives in. Splitting by service ownership was tried and is not how work actually divides here: across
31 agent reports it divided by how well-specified a task was and how it had to be verified.

The presets are namespaced by the plugin that ships them, so `subagent_type` must be the
fully-qualified name — a bare `implementer` is rejected.

| Preset (`subagent_type`) | Shape | Model / effort |
|---|---|---|
| `noel-workflow:implementer` | The design is decided; build it and prove it works | Sonnet / medium |
| `noel-workflow:investigator` | The answer is not known yet; diagnose, reproduce, root-cause | Sonnet / high |
| `noel-workflow:verifier` | Independently confirm a claim against the artifact | Sonnet / high |
| `noel-workflow:platform` | Infrastructure, deployment, and live systems | Sonnet / high |

Pass `model: opus` at spawn time to escalate a specific task without changing the preset — this
preserves the escalation rule from the execution plan's tier table ("escalate that specific task
rather than the whole workstream").

Parallelism comes from background subagents and background sessions rather than from agent teams.
Enabling teams turns any *named* subagent into a teammate that reports only that it went idle,
without its output, which would silently break a flow that waits on subagent results.

What still belongs in the delegation prompt is only what is genuinely task-specific: exact file paths
and line numbers, what has already been ruled out, and explicit scope boundaries. Everything that used
to be retyped every time — verification requirements, the report contract, foreground-execution rules,
teardown expectations — is in the preset body.

**Dev box vs. local stack.** Local `docker-compose` is the default for delegated verification — free,
no credentials, and how the large majority of delegated work has always run without incident when
scoped-rebuild discipline (see each preset's "rebuild only what your change touches") is followed. Route
a task to the Hetzner dev box (`infrastructure/dev-box/`, via the `dev-box` skill) only when it genuinely
needs `kind`/Kubernetes at a scale the local ~3.8GB cap can't hold, or when more than one full-stack
rebuild would otherwise need to run concurrently and can't just be serialized instead — one stack alone
already idles at ~68% of that cap, so a second concurrent full rebuild is close to guaranteed to exceed
it. This is always the orchestrator's decision, made when writing the delegation brief, never a
subagent's own judgment call: every preset's Bash access is unrestricted, so nothing stops a subagent
from reaching for the dev box's credentials on its own, and doing so is a billable, credentialed,
real-world action — each preset carries an explicit rule against it. The concurrency cap (one
full-stack rebuild per host at a time) is documented guidance, not mechanically enforced — the existing
resource-scoped-rebuild mitigation plus the `kafka` named volume have already reduced two OOM incidents
to zero data loss, which isn't a strong enough track record of harm yet to justify a lock or hook.

### Verify

Verification is an enforced gate, not a step someone remembers.

A `SubagentStop` hook refuses to let `implementer`, `investigator`, or `platform` finish until it has
filed a report under `docs/agent-reports/` containing the required sections, with at least one block
of real command output under its verification section. Exit code 2 sends the agent back to finish the
work rather than allowing the turn to end.

This is a **discipline gate, not a correctness gate.** It cannot tell whether a fix is right. What it
can guarantee is that no agent declares completion without stating what it changed, showing evidence
it ran something real, and naming what it did not cover — which is precisely the information whose
absence made the Phase 7 and 8 completions unverifiable. A semantic gate that judges whether the
evidence supports the claim is one configuration change away; see **Escalating the gate** below.

The `verifier` preset is the deeper check, invoked for consequential work: it re-reads the stated exit
criteria, runs the suite itself, confirms the claimed behavior actually executes, and reports pass or
fail per criterion. It cannot edit source — its tool allowlist excludes `Edit` — so it can only report,
never quietly fix what it finds.

"Consequential work" is not left to the orchestrating session's in-the-moment judgment call — that
judgment reliably loses to momentum. Sprint 6 shipped six goals, including a concurrency fix
(`RunRegistry`'s deferred-cleanup change, issue #36) in the same subsystem an earlier sprint had
already had an incident in, and every one of them was verified by the orchestrator reading the diff
and report rather than by an independent `verifier` delegation — not because the work didn't warrant
it, but because a thorough self-report is easy to mistake for equivalent evidence in the moment. Two
categories get an actual `verifier` delegation before being marked Done, not just orchestrator
diff review, regardless of how convincing the implementing agent's own report reads:

- **Concurrency or shared-state changes** — anything touching timing, ordering, retries, or state
  shared across requests/threads/consumers. This project's most expensive failures (Phase 7/8, the
  Sprint 4 OOM, Sprint 5's #27/#29) all lived here.
- **Contract changes** — anything touching `docs/openapi/`, `docs/events/`,
  `docs/order-state-machine.md`, or `docs/db-ownership.md`. Sprint 5 already had one contract change
  land without following the coordination protocol, caught only at sprint review.

Results route back through the orchestrator rather than directly between agents. Inter-agent
messaging is available, but passing finished work agent-to-agent bypasses this checkpoint; its
value here is agents challenging each other, not accepting each other's conclusions.

One rule governs every verification, human or agent: **verify against the artifact, not the report.** A
reviewer that reads a summary inherits its claims. The most valuable catch in this project's history —
a flaky test correctly identified as pre-existing rather than a regression — came from stashing the
changes and re-running against a clean baseline instead of believing an assertion. This is the same
principle as Agent Rule 10, applied to agents rather than to scenarios.

### Land

One commit per logically coherent unit of work, not one per delegation. Commit messages read as a
human's: terse, matching the existing history, describing the change rather than restating the diff.
No `Co-Authored-By` trailer.

**Merging and deploying happen only in the session the developer is directly talking to, and only
after the developer explicitly confirms.** A subagent that finishes real work commits, pushes a
branch, and opens a PR if one doesn't exist — then stops and reports. It does not merge that PR, run
`redeploy.sh`, dispatch `build-images.yml`, or run a mutating `kubectl` command; the plugin's
`PreToolUse` hook `block-subagent-merge-deploy.py` — whose generic patterns are extended with this
repo's own deploy verbs from `.claude/workflow.json` — makes this mechanically true rather than a preset
sentence hoping it's followed. This landed after Sprint 8's issue #46, where a `platform` subagent
diagnosed and fixed a live production bug well, then pushed, opened a PR, and merged it to `main`
itself in the same delegation — before the developer had seen the diff. The fix held up, which is not
the same as the process having worked.

Unplanned work is logged to the board too, including retroactively.

**Board status advances at each real handoff, not in a batch afterward.** A subagent that opens a PR
for a tracked item moves it to `Ready to Merge` before its own turn ends — that transition happens
inside the subagent's turn, so only the subagent is in a position to make it promptly. The
orchestrating session moves `Ready to Merge` to `Merged` immediately after actually running
`gh pr merge`, and `Merged` to `Deployed` only once a `/deploy` run's own verification confirms the
work is actually live — not merely because time has passed since the merge. See the `board` skill's
Status lifecycle section for the full state list and `check-drift.py` for a mechanical cross-check
against each item's linked issue.

### Close and review

Closing establishes what happened: the board reconciled against reality, goals confirmed against the
artifact rather than a report, stale documentation corrected.

Reviewing decides what changes because of it, and is deliberately a separate pass. A close feels
finished once the board is green, and a forward-looking step bundled into it gets skipped. Review
sweeps every agent report's `## Deliberately not covered` section into backlog items, routes each
process gap to the layer that should hold it, and refines the workflow itself while the evidence is
fresh.

This step is why the cycle closes rather than merely repeating. Its absence is what let a well-designed
tier table and verification pass lapse silently, and made workflow refinement into a whole sprint's
work instead of a recurring half hour.

## Open decisions

**Worktree isolation for `implementer`.** Adding `isolation: worktree` to the preset would prevent two
concurrent subagents landing conflicting changes in one tree — a failure this project has already hit.
Still not enabled by default: the Agent tool's `isolation: "worktree"` option was tested directly
against this repo in Sprint 9 and fails outright with `error: unknown option 'no-track'` from `git
worktree add` — a flag added in git 2.19. The failure is not the git-version ceiling it first looked
like: manually running `git worktree add`/`remove` with `/opt/homebrew/bin/git` (2.50.1) on this same
machine works fully, including the lifecycle operations (`remove`) that the plain `git` on `PATH`
can't do at all because it resolves to `/usr/local/bin/git` 2.15.0. The shell PATH fix that put
Homebrew's git ahead of `/usr/local/bin` in the developer's own interactive terminal does not reach
the process tree the Agent tool's own worktree creation runs in — that process was started before the
fix and inherits its original environment regardless of what `~/.zshrc`/`~/.zprofile` now say, so a
`.zshrc` edit alone does not resolve this; the Claude Code process itself needs to be restarted (fresh
launch, not just a fresh shell) after the PATH fix for it to take effect. Until that's confirmed to
close the gap, worktree isolation stays off by default and `docker compose` verification needs the
gitignored environment files carried in via a `.worktreeinclude`, a path that remains unproven for a
different reason: no worktree has successfully formed to test it against. Sprint 4 ran genuinely
parallel delegations by having the orchestrating session reason manually about file overlap before
choosing to parallelize or sequence — that worked without a single landed conflict across ~20
delegations, so isolation is not urgently blocking. What Sprint 4 actually broke was different: three
agents each running a full-stack `docker compose up --build -d` at the same time OOM-killed `kafka`,
`inventory-service`, and `scenario-service` on the shared host. Worktree isolation would not have
prevented that — separate worktrees running separate full stacks would have made memory pressure
worse, not better. The immediate mitigation landed in the `implementer` preset instead: rebuild only
the service(s) a change actually touches, not the whole stack, when other delegations may be running
concurrently.

**Resource-scoped rebuilds vs. worktree isolation aren't the same problem.** Worktree isolation is
about two agents editing the same file; the OOM incident was about N agents sharing one host's finite
memory regardless of whether their file edits ever collided. Both are real; only the second one has
actually caused damage so far.

**`permissionMode: acceptEdits` for `implementer`.** Would remove most permission interruptions from
background delegations. Left at the default because it widens what an unattended agent can do without
asking.

**Escalating the gate.** The `SubagentStop` hook currently runs a shell script. Changing its `type` to
`prompt` or `agent` adds a model-judged check of whether the reported evidence actually supports the
claim, at a token cost on every delegation. Worth adding for infrastructure and concurrency work first,
where this project's most expensive failures have occurred.

**Whether the dev box is even the right resource for agent contention.** The dev-box-vs-local policy
above resolves *when* to route delegated work to the dev box, but not whether the dev box — designed
and documented as a single, exclusive resource for one person's solo chaos/load-testing sessions — is
the right tool for parallel-agent resource contention at all. It's still one exclusive box: two
delegations that both want it at once just relocate the contention problem to Hetzner instead of
removing it, and nothing here designs a queuing or reservation mechanism for that case. Tracked as its
own backlog item rather than folded into the policy above, since it needs its own dedicated design pass
if dev-box usage becomes routine rather than occasional.
