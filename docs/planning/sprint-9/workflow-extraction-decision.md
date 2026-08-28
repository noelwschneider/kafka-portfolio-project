# Workflow extraction: component audit and mechanism decision

Decision record for [#80](https://github.com/noelwschneider/kafka-portfolio-project/issues/80), the
gate on [#81](https://github.com/noelwschneider/kafka-portfolio-project/issues/81). It settles three
things: how the workflow travels, what each component becomes when it does, and whether the
`SubagentStop` gate keeps its structural-only shape.

Every mechanism claim below was established by running Claude Code 2.1.241 against throwaway probe
plugins, not by reading documentation. The evidence is in
`docs/agent-reports/sprint-9/issue-80-workflow-extraction-audit.md`.

---

## Decision 1 — Mechanism: a Claude Code plugin, distributed from a git repo used as a marketplace

**Recommendation: package the workflow as a plugin, not a hand-copied `~/.claude/` directory tree.**
Two components cannot travel in a plugin and take the one supported path instead:

| Layer | Vehicle |
|---|---|
| 4 agent presets, 10 skills, 4 hooks + their registrations | plugin (`agents/`, `skills/`, `hooks/hooks.json`) |
| `rules/git.md`, `rules/documentation.md` | `~/.claude/rules/` — plugins have no `rules` component type |
| Per-project identity (board coordinates, doc roots, deploy verbs) | `.claude/workflow.json` in each consuming repo |

Distribute the plugin from its own git repo carrying a `.claude-plugin/marketplace.json`, installed
with `claude plugin marketplace add <repo>` then `claude plugin install`. During development, the same
directory dropped at `~/.claude/skills/<name>/` auto-loads as `<name>@skills-dir` with no install step
at all, which makes the edit/reload loop cheap.

### What decided it

Both mechanisms work. The plugin wins on four things the directory tree structurally cannot offer:

- **Validation before a session, not during one.** `claude plugin validate <path> --strict` checks
  every skill, agent, and manifest and exits non-zero on unrecognized fields or missing metadata. A
  loose `~/.claude/agents/foo.md` with broken frontmatter fails at spawn time, inside whatever task
  was depending on it. This is CI-able; the directory tree has no equivalent.
- **Per-project scoping.** Plugin enablement is per-project via `enabledPlugins`. A global
  `~/.claude/agents/implementer.md` is loaded into *every* project on the machine, including its
  "the Hetzner dev box is not your call" and "never run `redeploy.sh`" sections, which are noise
  in a repo that has neither.
- **Namespacing prevents silent shadowing.** Plugin agents are addressed as `plugin:agent`. Under the
  directory tree, a global `implementer` and a project-local `.claude/agents/implementer.md` collide
  on a bare name with no warning about which won.
- **Versioning and reversibility.** `claude plugin update`, `uninstall`, `disable`, and `claude
  plugin tag` (which validates that `plugin.json` and the marketplace entry agree) exist. Rolling
  back a hand-copied tree means remembering what was copied.

`claude plugin details <name>` additionally prints a component inventory and projected token cost,
always-on and per-component — directly useful for keeping the always-on budget honest as the preset
bodies grow.

### What it costs, and the one hazard that must not be missed

**Plugin agents are namespaced, and hook matchers are full-match regexes against the namespaced
name.** Measured directly: `agent_type` arrives at the hook as `wf-probe:probe-impl`. A matcher of
`probe-impl` did **not** fire; `.*probe-impl` and the exact `wf-probe:probe-impl` both did. Bare
`subagent_type: probe-impl` is rejected outright — `Agent type 'probe-impl' not found`.

So today's registration in `.claude/settings.json`:

```json
"SubagentStop": [{ "matcher": "implementer|investigator|verifier|platform", ... }]
```

**silently stops matching** the moment the presets move into a plugin. No error, no warning — the
`SubagentStop` gate and the `SubagentStart` mark simply never fire again, and delegated agents go back
to being able to end their turns without a report. This is the single highest-risk step in #81 and it
must be verified by observing the gate actually block, not by observing that files exist.

The consequences to carry through #81:

- Hook matchers become `<plugin>:implementer|<plugin>:investigator|<plugin>:verifier|<plugin>:platform`.
- Every place that names a `subagent_type` — the `tier` and `delegate` skills, `docs/workflow/
  agent-workflow.md`, `.claude/CLAUDE.md` — must use the namespaced form.
- Slash-invoked skills are unaffected: `/probe-cmd` and `/wf-probe:probe-cmd` both resolve, so
  `/sprint-plan`, `/deploy`, and the rest keep their current ergonomics.
- Agent-preloaded skills are unaffected: `skills: [agent-report]` in a plugin agent's frontmatter
  resolves to the plugin's own bundled skill using the bare name.

Everything else the hooks depend on survives the move intact:

- `exit 2` from a plugin `SubagentStop` hook blocks the stop and feeds stderr back to the agent,
  identically to today — observed blocking an agent, which then complied and was allowed through on
  the retry.
- `${CLAUDE_PLUGIN_ROOT}` resolves to the plugin directory, and `${CLAUDE_PROJECT_DIR}` still resolves
  to the **consuming** project. This is load-bearing: `require-agent-report.py` must find the
  consuming repo's `docs/agent-reports/`, not the plugin's.

### Parameterization approach

Plugins have no variable-substitution facility, so project identity lives in the consuming repo and
the plugin reads it: a small `.claude/workflow.json`, resolved relative to `${CLAUDE_PROJECT_DIR}` (or
the hook payload's `cwd`, which is what the report gate already correctly prefers so that worktree-
isolated agents check their own checkout).

Roughly:

```jsonc
{
  "reportRoot": "docs/agent-reports",     // sprint-N subdirs optional
  "planningRoot": "docs/planning",
  "board": { "owner": "...", "projectNumber": 7 },
  "blockedSubagentCommands": ["redeploy\\.sh", "build-images\\.yml"],
  "contractEscalations": [ /* glob -> contract docs -> required report heading */ ]
}
```

Scripts read the file. Skills instruct the agent to read it. **Field and option ids are never stored**
— the `board` skill already documents `gh project field-list` as the authority whenever its baked
table drifts, so the exported version resolves ids at runtime from `owner` + `projectNumber` and the
whole class of stale-id bugs disappears with the table.

A project that ships no `.claude/workflow.json` gets sensible defaults and inert optional features
(no contract escalations, no board transitions), rather than an error.

---

## Decision 2 — Per-component disposition

**(a)** generic, exports as-is · **(b)** generic once parameterized · **(c)** project-specific, stays here

### Agent presets

| Component | Class | What's baked in / why it stays |
|---|---|---|
| `agents/implementer.md` | **b** | Stack sentence (Java 21/Spring Boot/Kafka/Postgres/K8s/React); `docs/planning/engineering-rules.md`; frozen-contract paths (`docs/openapi/`, `docs/events/`, `order-state-machine.md`, `db-ownership.md`); the Hetzner dev-box section; board ids `PVT_kwHOB38DIc4BhEqT` / `PVTSSF_lAHOB38DIc4BhEqTzhgB0vE` / option `bfcc30c4` / `projects/7` / `noelwschneider`; `redeploy.sh` and `build-images.yml`; the Sprint 4 OOM anecdote naming `kafka`/`inventory-service`/`scenario-service`. **Parameterize:** drop the stack sentence (the consuming project's own `CLAUDE.md` states it — the preset restating it is duplication that goes stale); generalize contract paths to "whatever your `CLAUDE.md` names as frozen contracts"; board block delegates to the `board` skill instead of inlining ids; dev-box section generalizes to "elevated/remote compute is the orchestrator's call, not yours," with specifics supplied by config or omitted. Keep the rebuild-scoping principle, drop the service names from the example. |
| `agents/investigator.md` | **b** | Same set minus the contract paths and rebuild advice. Same treatment. The reproduce-before-you-explain and separate-found-from-changed sections are fully generic and are the most valuable part of the file. |
| `agents/verifier.md` | **b** | Lightest project coupling of the four: dev-box section, board URL, `redeploy.sh`/`build-images.yml`, `docs/workflow/agent-workflow.md`. Verify-against-the-artifact, per-criterion reporting, and the cannot-edit-source constraint are entirely generic. |
| `agents/platform.md` | **b** | `ADR-010`/`ADR-011` by filename; dev-box; board ids; deploy verbs. **Parameterize:** "read the ADRs your project's `CLAUDE.md` points to before touching production" replaces the two filenames. Cost-is-part-of-correctness and least-invasive-intervention are generic. |

All four also carry the identical ~15-line "Keep the board current" block. Extracting it to the
`board` skill removes four copies that must currently be edited in lockstep.

### Skills

| Component | Class | What's baked in / why it stays |
|---|---|---|
| `skills/delegate/SKILL.md` | **a** | No project tokens at all. The include/leave-out split is pure workflow doctrine. Exports unchanged. |
| `skills/tier/SKILL.md` | **b** (trivial) | Generic apart from one cross-reference to `docs/external/claude-effort.md`. Inline the one fact it needs or drop the pointer. Preset names and effort defaults travel with the plugin. |
| `skills/agent-report/SKILL.md` | **b** | `docs/agent-reports/sprint-N/` path and the sprint-numbered convention; the `## Contract gaps found` section names this project's contract files. **Parameterize:** report root and sprint-subdir convention from config; make the contract-gaps section conditional on the project declaring contracts. The four required headings are the contract itself and stay fixed. |
| `skills/board/SKILL.md` | **b** — the hard case | `noelwschneider` ×8, project number 7, `PVT_kwHOB38DIc4BhEqT`, Status field id + 7 option ids, Sprint field id + 4 option ids, repo name. **Parameterize:** config supplies `owner` + `projectNumber`; ids resolve at runtime via `gh project field-list`. Drop the "no Priority field, removed in Sprint 8" paragraph — project history, not mechanism. Genuinely generic and worth keeping: the who-owns-which-transition table, the stale-id warning, the `--limit 30` silent-truncation warning, the draft-title-overwrite warning, and the "is this worth a card?" test. |
| `skills/board/check-drift.py` | **b** | Three module constants (`OWNER`, `REPO`, `PROJECT_NUMBER`) plus the shipped/unshipped status sets. All become config reads. Logic is otherwise generic. |
| `skills/sprint-plan/SKILL.md` | **b** | `docs/planning/sprint-N/` layout ×6, `gh project item-list 7 --owner noelwschneider`, `docs/planning/sprint-2/pre-sprint-planning.md`, `engineering-rules.md`. Planning root and board coordinates from config. Selection criteria and write-as-you-decide discipline are generic. |
| `skills/sprint-open/SKILL.md` | **b** | `docs/planning/README.md` as the current-sprint pointer, sprint dir layout, `docs/workflow/agent-workflow.md`. Same treatment. |
| `skills/sprint-close/SKILL.md` | **b** | Board command with owner/number, `check-drift.py` path, planning root. The merged-is-not-deployed rule is generic and worth keeping verbatim. |
| `skills/sprint-review/SKILL.md` | **b** (light) | `docs/CHANGELOG-contracts.md`, `.claude/CLAUDE.md`, sprint dir. The gap→layer routing table is the most reusable table in the entire system and needs no change. |
| `skills/deploy/SKILL.md` | **c** | Six named images, `orderfulfillment` namespace, `kafka-demo-box` context, `fulfillment-demo.noelschneider.com`, `redeploy.sh`, `maxSurge: 0`, ADR-010/011, GHCR paths. Every command is specific to this cluster. The four-stage shape is worth imitating; the file is not worth exporting. |
| `skills/dev-box/SKILL.md` | **c** | Hetzner account, `~/.config/hcloud-dev-box/token`, `kafka-dev-box` ssh alias, `infrastructure/dev-box/*.sh`, cpx32 pricing, this repo's rsync path. Nothing survives generalization. |

### Hooks

| Component | Class | What's baked in / why it stays |
|---|---|---|
| `hooks/mark-agent-start.py` | **a** | No project identity. Writes `.claude/.agent-marks/<agent_id>` under the payload `cwd`. Exports unchanged — but the consuming repo needs `.claude/.agent-marks/` in `.gitignore` (this repo has it; a new one won't). |
| `hooks/block-ai-attribution.py` | **a** | No project identity. Encodes a preference not every project shares, so enablement is per-project — but the code itself needs no change. |
| `hooks/block-subagent-merge-deploy.py` | **b** | `gh pr merge` and mutating `kubectl` are generic; `redeploy.sh` and `build-images.yml` are this project's. **Parameterize:** generic patterns built in, project-specific ones appended from `blockedSubagentCommands`. Message text references `.claude/rules/git.md` and `docs/workflow/agent-workflow.md`. |
| `hooks/require-agent-report.py` | **b** | Report dir `docs/agent-reports`; and the entire Flyway escalation branch — `**/db/migration/*.sql`, `docs/db-ownership.md`, `docs/CHANGELOG-contracts.md`, `## Frozen contract impact`. **Parameterize:** report root from config; generalize the escalation to a config-declared list of *(file glob → contract docs → required heading)* rules. The mechanism ("touching this class of file forces an explicit answer in the report") is genuinely reusable; this project's DB semantics are not. A project declaring none gets an inert branch. Core logic — start marks, newest-report-since, heading check, fenced-block check, fail-open — is generic. |
| `.claude/settings.json` registrations | **b** — highest risk | Becomes `hooks/hooks.json` with `${CLAUDE_PLUGIN_ROOT}` paths. **The `SubagentStart`/`SubagentStop` matchers must be rewritten to the namespaced agent names or both gates silently stop firing.** The `PreToolUse` `"Bash"` matcher is unchanged. |

### Rules

| Component | Class | What's baked in / why it stays |
|---|---|---|
| `rules/documentation.md` | **a** | No project tokens. Exports unchanged to `~/.claude/rules/`. Its one project-shaped reference — `## Deliberately not covered` — is part of the exported report contract. |
| `rules/git.md` | **b** | Generic: no-AI-attribution, commit shape, branch/PR/CI/merge, confirm-the-base-before-merging, verify-reachability-after-merging, subagents-never-merge. Project-specific: `.github/workflows/ci.yml`'s `required-checks` job, `redeploy.sh`, `build-images.yml`, the hook path, and Sprint 7/8 incident references. **Parameterize:** keep every rule; replace named artifacts with generic phrasing. Keep incidents as rationale — they explain *why* the rule is shaped as it is, and a rule whose reason is stripped gets argued with. |

### Not in the audit list, named for completeness

`.claude/CLAUDE.md` is **(c)** — it is this project's identity by definition. Its "Orchestration
reference" section will need updating in #81 to name the namespaced presets.
`.claude/settings.local.json` is **(c)**, machine-local permission grants, and should not travel.

---

## Decision 3 — The `SubagentStop` gate stays structural-only

**`require-agent-report.py` remains a `type: command` hook checking structure: the four required
headings present, and at least one fenced code block under `## How this was verified`.** The exported
default does not add a semantic `prompt`-type layer. A prompt-type variant ships alongside it,
registered but disabled, so a project that wants it can enable it without re-deriving it.

### Why

**A prompt hook reasons over the transcript; this gate's job is to read a file on disk.** This is the
decisive point and it was measured, not assumed. A `type: prompt` `SubagentStop` hook instructed to
block unless the subagent had created `done.txt` **allowed the stop with the file absent**. The same
hook, given a condition visible in the transcript ("the final message must contain XYZZY"), blocked
correctly. The report gate's actual check — does `docs/agent-reports/sprint-N/<slug>.md` contain four
exact headings and a fenced code block — is a filesystem fact. The command hook reads the file. The
prompt hook infers from what the agent *said* it wrote, which is precisely the claim the gate exists
to stop trusting.

Reinforcing, in order of weight:

- **The gate is a discipline gate, not a correctness gate** — as its own docstring says. "Did you say
  what you changed, show real output, name your gaps" is a structural property, and structural
  properties should be checked structurally.
- **Failure modes are asymmetric.** The command hook fails *open* on anything unexpected, so a broken
  gate never wedges a session. A prompt hook fails *nondeterministically* — it can refuse a good
  report or pass a bad one, differently on identical input. A gate that sometimes blocks correct work
  is worse than one that reliably blocks a narrow, well-defined omission, because agents learn to
  route around unreliable gates.
- **Substance already has an owner.** The `verifier` preset checks the artifact rather than the
  report. Semantic judgment in the stop hook duplicates that from a strictly worse vantage point: the
  hook sees only the report file, which is the exact artifact `verifier` exists not to trust.
- **Cost lands on the highest-volume path.** Every delegation pays it, and pays again on each retry
  after a block, in a workflow whose entire premise is high delegation volume.
- **The ecosystem's revealed preference agrees.** Across every plugin in Anthropic's official
  marketplace, all 17 hook definitions are `type: command`; none is `type: prompt`, despite the
  authoring docs labelling prompt hooks "Recommended."

**What this concedes.** The structural gate cannot tell real command output from fabricated or
copy-pasted output. Neither can a prompt hook reading the same text — the residual risk is not
addressed by either option, which is why it does not favour the semantic layer. It is addressed by
`verifier` re-running things independently.

---

## Sequenced hazards for #81

1. Rewrite the hook matchers to the namespaced agent names, and **prove the gate still blocks** by
   observing a real block, not by confirming files exist.
2. Update every `subagent_type` reference in skills and workflow docs to the namespaced form.
3. Resolve board field/option ids at runtime; do not port the id table.
4. Add `.claude/.agent-marks/` to the consuming project's `.gitignore` — the marks are written into
   the consuming repo, and an ungitignored one commits agent-run droppings.
5. Confirm `${CLAUDE_PROJECT_DIR}` / payload `cwd` resolution still points at the consuming repo from
   inside a plugin hook, including under `git worktree` isolation (interacts with #83).
