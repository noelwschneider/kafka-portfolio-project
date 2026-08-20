# Sprint 2 Plan

- **Input:** [`pre-sprint-planning.md`](pre-sprint-planning.md) — the full post-MVP backlog, tiered and
  assessed for benefit/cost.
- **Theme:** production readiness. Everything in scope makes the deployed app more presentable, more
  reliable, or more scalable. Work about the developer's own knowledge or workflow (Study Guide,
  Agentic Workflow Refinement) is out of scope for this sprint and queued first for Sprint 3.

## Goals

1. **Security & Repo Hygiene Pass** — dependency/secret scanning, `/demo` endpoint isolation check,
   LICENSE file, badge/link sanity check.
2. **Correctness & Reliability Cleanup** ("open gaps") — transactional outbox in
   Inventory/Payment/Fulfillment Services, the state machine's unimplemented `FAILED` transition, the
   SSE-under-concurrency defect in Order Service, and a retention policy for
   `processed_events`/`deferred_transitions`. Four small, independent, already-diagnosed fixes.
3. **VPS / Remote Development Workflow** — a Hetzner (or equivalent) remote dev environment with
   enough headroom to run this project's Kubernetes workload past the laptop's proven ceiling.
   Executed in a separate agent session; see
   [`vps-agent-briefing.md`](vps-agent-briefing.md).
4. **Deployment Spike** — get the application running somewhere a link can be shared. Involves
   real cost and platform decisions the user wants to be part of directly; executed in a separate
   agent session, see [`deployment-agent-briefing.md`](deployment-agent-briefing.md).
5. **README Demo Walkthrough** — a short recording or annotated GIFs near the top of the README,
   showing the app actually working.
6. **Autoscaler (HorizontalPodAutoscaler)** — formalizes the manual-scaling story from Phase 10 into
   an actual autoscaler.
7. **Bug Hunt** — a time-boxed pass (half a day to a day) focused on concurrency and partial-failure
   paths, the two categories that have produced real bugs so far (per ADR-009 and the SSE defect).

## Dependencies

```
security ──► deploy ──► readme
open gaps
vps ──► autoscaler
    └─► bug hunt
```

- `security` and `open gaps` have no dependencies and can start immediately.
- `deploy` waits on `security` clearing (no known secrets or vulnerable dependencies before the repo
  gets more public exposure). `readme` waits on `deploy` — a real deployment makes for a better
  recording than localhost.
- `vps` has no dependency on the other roots, but `autoscaler` and `bug hunt` both need the headroom
  it provides: autoscaler because scaling up is exactly what hit a hardware ceiling in Phase 10, bug
  hunt because the concurrency bugs found so far got worse with more replicas, and higher replica
  counts aren't reachable locally yet.
- `autoscaler` and `bug hunt` are not sequenced relative to each other by dependency, but Bug Hunt's
  exploratory scope can reach into the same Kubernetes manifests Autoscaler is actively changing —
  scope Bug Hunt away from infra/K8s files while Autoscaler is in flight, or loosely sequence them.

CI/CD is explicitly out of scope for this sprint — no goal here needs it, and it gets a more natural
trigger once auto-deploy (post-`deploy`) becomes something actually wanted.

## Definition of done

All seven goals delivered, or explicitly re-scoped mid-sprint with a documented reason. `deploy` is
the most likely candidate for that: it's a spike by design, and if it runs long the right move is
landing a decision plus a follow-up item, not letting it absorb the sprint.

## Planning docs this sprint needs

- **`execution-plan.md`** (this folder) — the operational plan: model/effort tier per goal, the
  agent/worktree assignment for each parallel track, and verification steps. Formalizes the
  dependency chart above into an actual schedule.
- **[`vps-agent-briefing.md`](vps-agent-briefing.md)** — standalone context for the separate agent
  session handling goal 3, since that session won't have this sprint's conversation history.
- **[`deployment-agent-briefing.md`](deployment-agent-briefing.md)** — standalone context for the
  separate agent session handling goal 4. Presents platform/cost tradeoffs for the user to decide
  rather than proceeding unilaterally, since this goal carries recurring cost and long-term platform
  implications.
- **New ADRs, as they come up** — not written speculatively now. Likely candidates: a deployment ADR
  once `deploy` lands on a target, and possibly one for the `FAILED`-transition/retention-policy
  design within `open gaps`. Follow the existing `docs/adr/` numbering.
- **A deployment reference doc** (likely `docs/deployment.md`, alongside `docs/architecture-diagram.md`
  and the other frozen contracts) — how to actually deploy this thing, once `deploy` decides how.

No Sprint-2 equivalent of `sprint-1/backend-design.md`, `frontend-design.md`, or
`high-level-design.md` is needed — none of these seven goals redesign the system.
