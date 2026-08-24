---
name: agent-report
description: The report contract every delegated agent files when its work is done. Defines the required sections, where the file goes, and what counts as evidence. Preloaded into the implementer, investigator, verifier, and platform presets.
user-invocable: false
---

# Agent report contract

Before your turn ends, write a report to `docs/agent-reports/sprint-N/<short-slug>.md`, where
`sprint-N` matches the current sprint from `docs/planning/README.md`. Use a new file unless you were
explicitly told to extend an existing one.

A `SubagentStop` gate checks this file and will refuse to let you finish without it. The gate checks
for the four headings below, spelled exactly as written, and for at least one fenced code block under
`## How this was verified`.

## Required sections

### `## What changed`

Files created or modified, each with a one-line statement of what changed in it. Name real paths. If
you changed nothing, say so explicitly and explain why.

### `## How this was verified`

The evidence, not a claim about the evidence. Include the actual commands you ran and their real
output in fenced code blocks — test runs, `curl` responses, `kubectl` status, container logs. Prose
alone does not satisfy this section and does not satisfy the gate.

Verification must be against a real running system: `docker compose`, `kind`, or the live box as
appropriate. Reasoning that something should work is not verification. If you could not verify
something, put it under `## Deliberately not covered` rather than implying it passed.

### `## Judgment calls`

Decisions you made that the delegation prompt did not settle for you, and why you decided as you did.
Include alternatives you rejected. This is the section a reviewer reads to find where you might have
gone wrong.

### `## Deliberately not covered`

Honest gaps: what you did not test, did not fix, or ran out of scope for, and why. Naming a gap here
is a successful outcome. Silently leaving one is the failure this section exists to prevent.

## Optional sections, when they apply

- `## Contract gaps found` — anything wrong or insufficient in `docs/openapi/`, `docs/events/`,
  `docs/order-state-machine.md`, or `docs/db-ownership.md`. Follow the coordination protocol in
  `.claude/CLAUDE.md`: propose the change in the doc, don't work around it locally, and note it in
  `docs/CHANGELOG-contracts.md`.
- `## Reproducing this from a clean clone` — for work that stands up infrastructure or changes how
  the project is built or run.
