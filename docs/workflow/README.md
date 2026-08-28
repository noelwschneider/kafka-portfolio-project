# Workflow docs

How agentic work on this project is directed. Application design lives in `docs/planning/`; this
directory is about the process, not the product.

- [`user-guide.md`](user-guide.md) — how a sprint runs and what you do at each stage. Start here.
- [`commands.md`](commands.md) — every command, what it does, and when it's useful.
- [`agent-workflow.md`](agent-workflow.md) — the design and the reasoning behind it.

Most of the executable workflow ships in the `noel-workflow` Claude Code plugin rather than in this
repo, so it can be reused by other projects. What lives where:

| Path | What it is |
|---|---|
| plugin `agents/*.md` | Subagent presets — one per recurring task shape, carrying model, effort, tool limits, and standing instructions. Addressed as `noel-workflow:<name>` |
| plugin `skills/agent-report/` | The report contract every delegated agent files, preloaded into all four presets |
| plugin `hooks/` | The verification gate: a start mark and a `SubagentStop` check that refuses completion without a real report, plus the merge/deploy and AI-attribution blocks |
| plugin `skills/*/SKILL.md` | Generic procedures that load only when invoked — `board`, `delegate`, `tier`, and the four sprint skills |
| `.claude/skills/deploy/`, `.claude/skills/dev-box/` | The two procedures that are specific to this project's cluster and Hetzner account, and stay here |
| `.claude/workflow.json` | This project's coordinates: report and planning roots, board owner/number, extra blocked deploy verbs, and the Flyway contract-escalation rule. No GitHub Project field or option ids — those resolve at runtime |
| `.claude/CLAUDE.md` | Project facts, loaded every session |
| `~/.claude/rules/*.md` | Standing rules for documentation and git, loaded into every session on this machine |

## Skills

| Skill | What it does |
|---|---|
| `/sprint-plan` | Review the backlog and propose a candidate slate for the next sprint |
| `/tier` | Pick the preset, model, and effort a task warrants |
| `/delegate` | Compose a delegation brief that carries what the agent cannot get elsewhere |
| `/board` | Add to or update the GitHub Project board |
| `/deploy` | Guided production deploy: build & push, redeploy, verify — or `--restart-only` to skip the build |
| `/sprint-open` | Open a sprint: plan doc, index, board setup, carry practices forward |
| `/sprint-close` | Close a sprint: reconcile the board and correct the record |
| `/sprint-review` | Review a sprint: capture backlog items, route gaps, refine the workflow |

`agent-report` is not user-invocable; it is preloaded into the four presets as their report contract.

## Delegating work

Name the preset when you delegate. Give it the task-specific context — exact paths, what's already
been ruled out, explicit scope boundaries — and nothing else; the standing instructions are in the
preset.

| Preset | Use it when |
|---|---|
| `implementer` | The design is decided and the job is to build it and prove it works |
| `investigator` | The cause is unknown; reproduce and root-cause before fixing |
| `verifier` | A claim needs independent confirmation against the artifact |
| `platform` | The work touches infrastructure, deployment, or a live system |

Escalate an individual hard task with `model: opus` at spawn time rather than changing the preset.

## When the gate blocks an agent

The `SubagentStop` gate refuses completion until the agent files a report under
`docs/agent-reports/sprint-N/` with all four required headings and real command output under
`## How this was verified`. A blocked agent is told exactly what is missing and continues working.

The gate fails open: if it cannot find the repository, the report directory, or the report itself, it
allows the stop rather than wedging the session. It checks discipline, not correctness — it cannot
tell whether a fix is right, only that the agent showed its work.
