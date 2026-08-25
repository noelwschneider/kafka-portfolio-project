# Agent Workflow

How agentic work on this project is planned, delegated, verified, and landed.

This document owns the intent and reasoning. The executable pieces live in `.claude/` — subagent
presets in `.claude/agents/`, skills in `.claude/skills/`, the verification gate in `.claude/hooks/`
— and are tracked in version control so they cannot silently lapse.

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
| Hooks | Enforced — cannot be skipped or argued with | Gates that must not be optional | `.claude/hooks/` + `.claude/settings.json` |
| Subagent presets | Applied automatically every run | Standing instructions and tool limits for a task shape | `.claude/agents/*.md` |
| Skills | Loaded on demand, free until invoked | Procedures and reference knowledge | `.claude/skills/*/SKILL.md` |
| CLAUDE.md and rules | Always in context, advisory | Project facts and standing rules | `.claude/CLAUDE.md` |

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

| Preset | Shape | Model / effort |
|---|---|---|
| `implementer` | The design is decided; build it and prove it works | Sonnet / medium |
| `investigator` | The answer is not known yet; diagnose, reproduce, root-cause | Sonnet / high |
| `verifier` | Independently confirm a claim against the artifact | Sonnet / high |
| `platform` | Infrastructure, deployment, and live systems | Sonnet / high |

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

Unplanned work is logged to the board too, including retroactively.

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
It is not enabled by default because a worktree is a fresh checkout: `docker compose` verification
needs the gitignored environment files carried in via a `.worktreeinclude`, and that path is unproven
here. Worth doing before the next round of genuinely parallel implementation work.

**`permissionMode: acceptEdits` for `implementer`.** Would remove most permission interruptions from
background delegations. Left at the default because it widens what an unattended agent can do without
asking.

**Escalating the gate.** The `SubagentStop` hook currently runs a shell script. Changing its `type` to
`prompt` or `agent` adds a model-judged check of whether the reported evidence actually supports the
claim, at a token cost on every delegation. Worth adding for infrastructure and concurrency work first,
where this project's most expensive failures have occurred.
