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
