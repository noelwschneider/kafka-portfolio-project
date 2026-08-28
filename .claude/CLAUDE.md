# Order Fulfillment Systems Lab

An interactive, event-driven order-fulfillment portfolio system (Java/Spring Boot, Kafka, PostgreSQL, Kubernetes, React/TypeScript). It is not a real storefront — the product is the distributed-systems sandbox (normal processing + deliberately reproducible failure scenarios), not the fake catalog. See `docs/planning/project-overview.md` for the full purpose/scope statement.

## Read this first, every session

`docs/planning/README.md` is the index of all planning docs and states the reading order. Read it before starting any non-trivial task in this repo — don't assume you already know which doc covers what.

- `docs/planning/` — the design/planning docs, organized by sprint (`docs/planning/README.md` is the index and states which sprint is current). Four cross-sprint files live at the top level (project overview, portfolio goals, agent guidance, the README/index itself); everything else — including each sprint's own backend/frontend/high-level design and execution plan — lives under `docs/planning/sprint-N/`. **Treat sprint-1's docs as read-only, frozen historical record of the original MVP build.** If one is actually wrong (not just underspecified), flag it in your output rather than editing it directly. Later sprints' planning docs are living documents for that sprint's duration.
- `docs/_old/` — the original single-file draft, kept for history only. Not authoritative; don't cite it.
- `docs/openapi/`, `docs/events/`, `docs/adr/`, `docs/order-state-machine.md`, `docs/db-ownership.md`, `docs/scenarios.md`, `docs/architecture-diagram.md` — the **frozen contracts**. They are the single source of truth for cross-service integration — prefer them over re-deriving shapes from `docs/planning/sprint-1/backend-design.md` prose.

## Hard rules

Follow all 20 Agent Rules in `docs/planning/engineering-rules.md` in full — always in effect. That file is the single authoritative list; don't rely on a restatement here going stale (an earlier version of this section silently dropped 4 of the 20 rules after one edit — that's why this section is now a pointer, not a copy).

The handful most likely to cause real damage if missed in a session that skips rereading the full list:

- Never expose JPA entities directly from controllers; keep DTOs separate from persistence entities.
- Keep demo/fault-injection APIs isolated under `/demo`, never mixed into `/api`.
- Scenario behavior must be real (real requests, real events, real persistence) — never a frontend-simulated animation.
- Do not claim stronger delivery/consistency guarantees than the implementation actually provides — never write "exactly-once" unless it's actually true end-to-end.
- Single monorepo, one git repo — no per-service repos or submodules (see also Project-wide decisions below).

## Project-wide decisions (don't re-litigate these; see `docs/planning/project-overview.md` §0 for the source table)

- **Single monorepo**, one git repo, no per-service repos or submodules. `services/*`, `frontend/`, `infrastructure/` are plain subdirectories.
- **Pinned stack**: Java 21 (LTS), Maven (multi-module), Flyway, `apache/kafka` Docker image (KRaft), Vite + React + TypeScript + TanStack Query + native `EventSource`, Node 22 (LTS), GitHub Actions with per-service path filters. Don't substitute alternatives without updating the pinned-tech table and stating why.
- **Build sequence**: modular monolith first (Phase 1), Kafka introduced in-process (Phase 2), then extracted into 4 independent services (Phase 3). Don't jump straight to separate services before Phase 3.
- **Cross-reference convention**: cite other docs by filename + section title, not by number — section numbers are not unique across the split docs.

## Coordination protocol for contract changes

`docs/openapi/`, `docs/events/`, `docs/order-state-machine.md`, and `docs/db-ownership.md` are the integration boundary between workstreams once Phase 0 completes. If you're working on one service and discover a contract file is wrong or insufficient:

1. Stop — don't work around it locally.
2. Propose the change in the relevant `docs/` file with a one-line rationale.
3. Update affected implementations and tests.
4. Don't let it happen silently — leave a note (e.g. in `docs/CHANGELOG-contracts.md` if it exists) so other in-flight work knows to re-check.

## Orchestration reference

`docs/workflow/agent-workflow.md` is the operating manual for delegated work: how tasks are
delegated, verified, and landed, and why the process is shaped the way it is. Read it before
spawning or briefing any subagent.

**The workflow itself lives in the `noel-workflow` plugin, not in this repo.** The four presets, the
eight generic skills, and the four hooks are installed from that plugin; this repo keeps only what is
specific to it — `.claude/skills/deploy/`, `.claude/skills/dev-box/`, and `.claude/workflow.json`,
which supplies the project's coordinates (report and planning roots, board owner/number, this
project's extra blocked deploy verbs, and the Flyway contract-escalation rule) to the plugin's logic.
`.claude/workflow.json` holds **no** GitHub Project field or option ids; those resolve at runtime.
The standing git and documentation rules live in `~/.claude/rules/`.

Delegate to one of the four presets — chosen by task shape, not by which service the code lives in.
They are namespaced, so `subagent_type` must be the fully-qualified name: `noel-workflow:implementer`,
`noel-workflow:investigator`, `noel-workflow:verifier`, `noel-workflow:platform`. A bare name is
rejected outright. Each preset carries its own model and effort tier, so tiering no longer depends on
a per-sprint document being kept current. Escalate an individual hard task by passing `model: opus` at
spawn time rather than editing the preset. The delegation prompt should carry only what is
task-specific: exact file paths and line numbers, what has already been ruled out, and explicit scope
boundaries.

Sprint plans under `docs/planning/sprint-N/` own scope and rationale, and record tier deviations
only. `docs/planning/sprint-1/execution-plan.md` remains the phase-by-phase record of the original
MVP build; later sprints do not have one.

Two failure modes are now handled structurally rather than by reminder, and briefing prompts do not
need to restate them:

- **A subagent ending its turn on unfinished background work** (observed in Phase 7). A
  `SubagentStop` hook refuses completion until the agent files a report containing real command
  output, so a build that never finished cannot be reported as a build that passed.
- **A subagent delegating its own assigned work onward** (observed in Phase 8). The preset tool
  allowlists admit only `Agent(Explore)`, a read-only search helper, so handing off implementation
  is mechanically unavailable rather than merely discouraged.

A third failure mode — the project board reading as more done than reality, because nobody advanced
its Status past the point where they stopped looking — is handled the same way in spirit but not
mechanically enforceable the way the two above are: no tool-call event corresponds to "the board is
now stale." Each preset's "Keep the board current" section makes advancing its own item to
`Ready to Merge` part of finishing the task, not an afterthought, and the `board` skill's
`check-drift.py` gives a repeatable way to catch the cases that still slip through — see the `board`
skill's Status lifecycle section for the full contract.

## Project-specific git facts

The general git rules — no AI attribution, commit shape, branch/PR/CI/merge, confirm the base before
merging, verify reachability after, subagents never merge or deploy — are in `~/.claude/rules/git.md`
and apply everywhere. What is specific to this repo:

- `main`'s branch protection requires the `required-checks` job in `.github/workflows/ci.yml` to pass
  before a PR can merge.
- The deploy verbs a subagent may not run here are `redeploy.sh` and
  `gh workflow run build-images.yml`, declared in `.claude/workflow.json` and enforced by the
  plugin's `block-subagent-merge-deploy.py` hook.
