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

## The task lifecycle

### Plan

Sprint scope and rationale stay in `docs/planning/sprint-N/sprint-N-plan.md`; live status stays on the
GitHub Project board. Each sprint additionally carries a **model and effort tier assignment** for its
work. That assignment no longer needs to be rewritten per sprint: the presets carry the default tiers,
and a sprint plan only records deviations.

Effort must be set explicitly or it reverts to High. Preset frontmatter sets it, which is why the
tiering now survives a sprint boundary.

### Delegate

Delegation goes to one of four presets, chosen by task shape rather than by which service the code
lives in. The service-ownership split in `agent-guidance.md`'s **Recommended AI Agent Work Breakdown**
was never how work actually divided — the record shows it dividing by how well-specified a task was
and how it had to be verified.

| Preset | Shape | Model / effort |
|---|---|---|
| `implementer` | The design is decided; build it and prove it works | Sonnet / medium |
| `investigator` | The answer is not known yet; diagnose, reproduce, root-cause | Sonnet / high |
| `verifier` | Independently confirm a claim against the artifact | Sonnet / high |
| `platform` | Infrastructure, deployment, and live systems | Sonnet / high |

Pass `model: opus` at spawn time to escalate a specific task without changing the preset — this
preserves the escalation rule from the execution plan's tier table ("escalate that specific task
rather than the whole workstream").

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

## Deliberately not adopted

Recording these so they are not revisited without new reason.

**Agent teams.** Experimental, disabled by default, and materially more expensive per task. The
disqualifying detail is that enabling them turns any *named* subagent into a teammate, and teammates
report only that they went idle — without their output. An orchestration flow that waits on subagent
results would break quietly. The parallelism on offer is already available through background
subagents and background sessions.

**A per-sprint tier table.** Superseded rather than rejected. The tiers now live in preset frontmatter,
which is exactly the durability property the sprint-1 table lacked. A sprint plan records deviations
only.

**Presets split by service ownership.** The evidence is in this repository: six service-owning agents
were specified in `agent-guidance.md` and the 31 reports in `docs/agent-reports/` show work dividing
along a different axis entirely.

**A mesh of agents messaging each other directly.** Inter-agent messaging is real and available, but
routing results between agents bypasses the verification checkpoint that has caught real defects here.
Its strongest use is agents *challenging* each other — competing hypotheses, adversarial review — not
agents accepting each other's finished work.

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
