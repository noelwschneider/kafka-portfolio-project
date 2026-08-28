# Project Handoff

A snapshot of where the project stands right now, for resuming after time away. This doc states
current facts; it does not narrate how it got here. Refreshed at the end of every sprint by
`/sprint-close` — if it looks stale, check the file's last commit date against the current sprint.

## What this project is

An interactive, event-driven order-fulfillment sandbox demonstrating production-style distributed
systems engineering (Java/Spring Boot, Kafka, PostgreSQL, Kubernetes, React/TypeScript) — see
[`project-overview.md`](project-overview.md) for the full purpose and scope statement.

## Current deploy state

Live at **https://fulfillment-demo.noelschneider.com**. Deployed on a k3s cluster on a Hetzner VPS
(see `infrastructure/`), fronted with TLS. The most recent fully deployed sprint is Sprint 8
(frontend visual/UX upgrade): a new site theme, a shared frontend styling contract, a graphical
service/topic flow indicator on the scenario-run timeline, and an Orders-page batch (pagination,
filtering/lookup, per-scenario customer names) — eight of nine planned goals shipped; see
[`sprint-8/sprint-8-plan.md`](sprint-8/sprint-8-plan.md)'s Closing state for the full account,
including the one goal (#55, refactoring existing components onto the styling contract) that did not
ship and was returned to Backlog.

## Current sprint and its status

**Sprint 9 — workflow portability and resumability** (in progress). Theme: making the project's own
agentic workflow — subagent presets, skills, verification hooks, standing rules — usable outside this
repo, plus this handoff doc so returning to the project after a gap doesn't mean reconstructing state
from several scattered docs and the live board. Two goals:

1. Extract a reusable global agentic workflow system (#79, with sub-issues #80 audit/mechanism
   decision, #83 harden concurrent-agent Docker/git hazards, #81 extract/parameterize/validate).
2. Write this handoff document and wire it into `/sprint-close` (#82).

See [`sprint-9/sprint-9-plan.md`](sprint-9/sprint-9-plan.md) for the full plan, sequencing, and
developer-involvement checkpoints.

## Notable recent incidents worth remembering

- **PR #65 stale-base-branch merge (Sprint 8).** Merging #65 landed on a stale intermediate branch
  instead of `main` because its base had never been retargeted after an earlier branch closure, and
  deleting that branch during cleanup briefly orphaned the merge commit. Caught by directly checking
  for the feature's files on `origin/main` rather than trusting `gh pr merge`'s reported success.
  This is why `~/.claude/rules/git.md` now requires confirming a PR's base before merging and verifying
  merged content is actually reachable from the target afterward, rather than trusting reported merge
  success.
- **Sprint 7 CI failure went unnoticed until caught manually,** because a push to `main` had run CI
  asynchronously with nobody watching. This is why `~/.claude/rules/git.md` requires branch protection
  and an explicit `gh pr checks --watch` step rather than a glance at the PR page.
- **Sprint 8 issue #46:** a `platform` subagent diagnosed and merged a production fix in one
  delegation before the developer saw the diff. The fix held, but that was luck covering a process
  gap. This is why merges and deploys are now mechanically blocked for subagents
  (the `noel-workflow` plugin's `block-subagent-merge-deploy.py` hook) rather than left to convention.

## Backlog highlights

The live board is the source of truth: **https://github.com/users/noelwschneider/projects/7**.
Nothing on it is currently time-sensitive or blocking outside of Sprint 9's own in-flight items
(#79/#80/#81/#82/#83, tracked in the sprint plan above). No other backlog item carries a deadline or
external dependency.

## Reading order for re-orienting

Start at [`README.md`](README.md) — it is the actual index of all planning docs and states which
sprint is current. This document only answers "where do things stand right now"; it is not a second
table of contents.
