# Sprint 7 Plan

- **Input:** the Tier 1 workflow/ops backlog plus two Tier 2 retrospective/audit items, filtered
  against a single question — what closes real, demonstrated process gaps rather than adding
  application features.
- **Theme:** workflow and process hardening. Primarily non-code — the one exception is the Tier 1
  `ConsumerErrorHandlerFactory` bug, carried in regardless of theme per standing policy of always
  addressing time-critical or Tier 1 bugs. Not frontend polish (Sprint 6) and not backend
  correctness work (Sprint 5) — this sprint is about how the project itself is run: how delegated
  agents use available infrastructure, how production deploys actually happen, and whether the
  project's own history and documentation are still legible after six sprints of accumulation.

## Goals

1. **[#41](https://github.com/noelwschneider/kafka-portfolio-project/issues/41)
   `ConsumerErrorHandlerFactory`: constraint violations retried past MAX_RETRIES budget** — surfaced
   during Sprint 6 review's independent verification of #36. A unique-constraint violation
   (`23505`) inside a `@Transactional` Kafka listener method got retried well past the configured
   `MAX_RETRIES=3` budget in `services/common`'s `ConsumerErrorHandlerFactory`, even though
   `NonTransientDataAccessException` is listed there as non-retryable — possibly because a
   commit-time flush failure surfaces as a wrapped exception type the classifier doesn't unwrap.
   This is shared infrastructure, not specific to #36's fix: it could affect any Kafka listener
   across any service that hits a DB constraint violation, meaning the documented retry budget may
   not actually hold anywhere this failure class occurs.
2. **[#42](https://github.com/noelwschneider/kafka-portfolio-project/issues/42) Design a `/deploy`
   command for guided production deploys** — today's deploy path is manual and split across two
   disconnected places (`gh workflow run build-images.yml` and
   `infrastructure/kubernetes/production/redeploy.sh`), and no agent session has ever exercised the
   full build → push → redeploy → verify pipeline end-to-end against the real production box
   (confirmed during Sprint 6 — #37's fix could only be verified via a stubbed `kubectl`). Design a
   guided flow with explicit checkpoints before irreversible steps, not full unattended auto-deploy,
   given this project's own history of a full outage from a bad simultaneous-restart pattern
   (ADR-011).
3. **[#38](https://github.com/noelwschneider/kafka-portfolio-project/issues/38) Parallel-agent
   resource contention: dev box vs. local stack policy** — none of the four subagent presets
   (`implementer`, `investigator`, `verifier`, `platform`) reference the existing Hetzner dev box
   (`infrastructure/dev-box/`) as an option for resource-heavy delegated work. Sprint 6's five
   parallel agents against the local docker-compose stack produced two OOM incidents (Kafka killed
   by concurrent full-stack `docker build` runs), the same class of contention Sprint 4 hit. Decide
   when a delegated agent should use the dev box vs. the local stack, whether concurrent
   `docker-compose --build` runs against one host need a cap, and how a background agent would know
   to reach for the dev box's manual `dev-up.sh`/rsync/ssh/`dev-down.sh` lifecycle — then write that
   decision into the presets and/or `docs/workflow/agent-workflow.md` so it actually changes agent
   behavior instead of staying a backlog note.
4. **[#39](https://github.com/noelwschneider/kafka-portfolio-project/issues/39) Bug pattern review
   across recent sprints** — a retrospective pass over the bugs caught in Sprint 4's issue #25
   investigation and its four follow-ups, Sprint 5's #27/#28/#29, and Sprint 6's #36, looking for
   shared root-cause shapes, subsystems, or failure classes. Distinct from fixing any individual bug;
   the output is a set of patterns that can inform a more targeted future bug hunt instead of an
   unfocused search.
5. **[#40](https://github.com/noelwschneider/kafka-portfolio-project/issues/40) Documentation
   staleness and consolidation review** — audit `docs/` for staleness across `sprint-1` through
   `sprint-6`, the four cross-sprint files, and the frozen contracts: dead cross-references,
   superseded content that should be trimmed rather than left to accumulate, and whether the current
   per-sprint directory structure still fits at this scale. Audit and recommendation only in this
   pass — see Developer involvement below.

## Sequencing

**#41 has no dependency on anything else. #42 and #38 are related (both about how agents and infra
interact) but don't block each other. #39 and #40 are independent audits.**

```
#41 (MAX_RETRIES bug) — independent

#42 (/deploy command design) ── related, not blocking ── #38 (dev-box policy)

#39 (bug pattern review) — independent
#40 (doc staleness review) — independent
```

All five goals can run in parallel; none gates another.

## Explicitly not in scope

**Bug hunt follow-ups** (`HttpMediaTypeNotSupportedException`, Kafka consumer rebalance
mid-transaction, `DemoResetService` concurrent reset race), the **#36 regression test** and
**PoisonMessageScenario DLQ verification**, and **Inventory: release reservations on FULFILLED
(Option B)** — all require actual reproduction and likely code fixes, which conflicts with this
sprint's non-code framing. Left for a dedicated reliability sprint, per Sprint 6's own precedent of
deferring these rather than mixing them into an unrelated theme. #39's findings may sharpen the scope
of that future sprint.

**Workflow live audit** — deliberately saved for a future code-based sprint, where narrating the
delegation/verification process against real work in progress is possible.

**Orders page items** (#21 filtering, #33 pagination, scenario-column idea, `OrderDetailPage` date
formatting), the **frontend test harness** (#34), the **second Hetzner node**, and the
**maintainability audit** — off-theme or still correctly Shelved; nothing has changed to revisit them.

## Developer involvement

Not every goal needs the same level of check-in:

- **#41 (bug fix) and #39 (bug pattern review)** — no developer involvement expected. Loop the
  developer in only if a genuine judgment call comes up mid-task.
- **#42 (`/deploy` command) and #38 (dev-box policy)** — no deep technical involvement needed, but
  both are the kind of infrastructure-policy work where a judgment call is plausible (e.g. how
  aggressive a dev-box-usage rule should be, what an irreversible-step checkpoint in `/deploy` should
  actually gate on). Loop the developer in before deciding, not after.
- **#40 (documentation review)** — the audit itself needs no developer involvement, but the developer
  signs off on the agent's reorganization recommendations before any restructuring actually happens.
  Two-phase: produce findings and a proposed plan first, pause for approval, then execute.

## Dependencies

No dependency on any other sprint's work. Within the sprint, see the sequencing diagram above — all
five goals are independent and can run in parallel.

## Planning docs this sprint needs

No new backend/frontend/high-level design docs or execution-plan.md. #42 produces its own design
artifact (the `/deploy` skill/workflow definition itself). #38's decision gets written directly into
`.claude/agents/*.md` and/or `docs/workflow/agent-workflow.md` rather than a separate planning doc.
#40 produces a findings-and-recommendations document as its first-phase deliverable, reviewed by the
developer before any second-phase execution.

## Closing state

All five goals shipped and landed in six commits (`9aa8001`..`ab4f55c`).

- **#41** — root cause was not what the task brief suspected (a commit-time exception-wrapping gap in
  `ConsumerErrorHandlerFactory` itself). `services/scenario-service`'s `EventProjectionConsumer` was
  never wired to the shared classifier at all — with no `*KafkaReliabilityConfig` bean in context,
  Spring Boot's Kafka auto-configuration fell back to Spring Kafka's own bare default (zero backoff, no
  DLQ) for every failure in that consumer, not just constraint violations. Fixed with a new
  `ScenarioKafkaReliabilityConfig` following every other service's existing pattern, plus a new
  `scenario.dlq` topic added under the coordination protocol. Independently verified by the `verifier`
  preset against the live stack, per the contract-change rule in `docs/workflow/agent-workflow.md`.
- **#42** — a `/deploy` skill designed to wrap the previously-disconnected `build-images.yml` dispatch
  and `redeploy.sh` into one four-stage guided flow with explicit checkpoints. Consolidated with the
  pre-existing `/redeploy` skill into a single command (`--restart-only` covers the no-new-build case)
  rather than kept as two files, per developer direction after review surfaced the overlap. Four open
  design forks resolved by the developer; tag pinning (`ghcr/kustomization.yaml`'s mutable `:latest`)
  split out to its own backlog item as a real production config change outside a design task's scope.
- **#38** — investigation found the actual exposure was broader than the task's own framing: not just
  "presets don't mention the dev box" but that all four presets' unrestricted `Bash` access means
  nothing mechanically stops a subagent from reaching the dev box's credentials on its own initiative.
  Measured (not assumed) that the local stack idles at ~68% of the local memory cap, giving a concrete
  basis for the routing threshold. Developer resolved all five flagged judgment calls: local by default,
  documentation-only concurrency cap (not mechanically enforced), the "ask before anything risky" rule
  generalized from `platform` to all four presets, a new `dev-box` skill created, and whether the dev
  box is even the right resource for agent contention split out to its own backlog item rather than
  decided here.
- **#39** — four cross-sprint failure patterns identified by mechanism, not surface similarity: ephemeral
  Kafka-coordinate identity keys that don't survive a broker reset; async-completion races between
  independently-progressing consumers; the shared retry classifier's repository-call-time assumption
  (directly feeding #39's own sibling task, #41); and an asymmetric compensation gap. A cross-cutting
  finding — every defect lived inside a branch designed to be a quiet no-op — gives a concrete, cheap
  search heuristic for a future targeted bug hunt.
- **#40** — frozen contracts, ADRs, and the per-sprint directory structure all checked out clean. Three
  real findings actioned: a genuine dead link in `project-overview.md` fixed; `docs/study-guide/` (83
  files) and `docs/external/claude-effort.md` found committed to the repo by accident (a `.gitignore`
  pattern collapse aimed at fixing `docs/agent-reports/` dropped their ignore rules too) and untracked,
  restoring what their own text always claimed; the `docs/_old/` "preserved" wording in the planning
  index corrected to state it's local-only, since it's correctly gitignored and never actually shipped.

Three items surfaced during execution were deliberately not resolved in this sprint and instead filed
as their own backlog items, consistent with the sprint's own scope boundaries: whether the dev box is
the right resource for parallel-agent contention at all (not just when to route to it), pinning an
immutable per-commit-SHA tag in `ghcr/kustomization.yaml`, and (raised independently by the developer,
unrelated to any single goal) a recurring project-backlog review and a way to handle work that needs
partial or continuous developer involvement mid-task.
