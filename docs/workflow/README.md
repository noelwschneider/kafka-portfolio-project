# Workflow docs

How agentic work on this project is directed. Application design lives in `docs/planning/`; this
directory is about the process, not the product.

- [`user-guide.md`](user-guide.md) — how a sprint runs and what you do at each stage. Start here.
- [`commands.md`](commands.md) — every command, what it does, and when it's useful.
- [`agent-workflow.md`](agent-workflow.md) — the design and the reasoning behind it.

The executable pieces live in `.claude/` and are tracked in version control:

| Path | What it is |
|---|---|
| `.claude/agents/*.md` | Subagent presets — one per recurring task shape, carrying model, effort, tool limits, and standing instructions |
| `.claude/skills/agent-report/` | The report contract every delegated agent files, preloaded into all four presets |
| `.claude/hooks/` | The verification gate: a start mark and a `SubagentStop` check that refuses completion without a real report |
| `.claude/skills/*/SKILL.md` | Procedures that load only when invoked |
| `.claude/rules/*.md` | Standing rules for documentation and git, loaded every session |
| `.claude/settings.json` | Registers the hooks |

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
