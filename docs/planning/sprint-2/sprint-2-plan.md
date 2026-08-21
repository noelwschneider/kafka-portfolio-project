# Sprint 2 Plan

- **Input:** [`pre-sprint-planning.md`](pre-sprint-planning.md) — the full post-MVP backlog, tiered and
  assessed for benefit/cost.
- **Theme:** production readiness. Everything in scope makes the deployed app more presentable, more
  reliable, or more scalable. Work about the developer's own knowledge or workflow (Study Guide,
  Agentic Workflow Refinement) is out of scope for this sprint and queued first for Sprint 3.

## Goals

1. **Security & Repo Hygiene Pass** — dependency/secret scanning, `/demo` endpoint isolation check,
   LICENSE file, badge/link sanity check. Also picks up the committed Postgres password in
   `infrastructure/kubernetes/01-secrets.yaml` (W7 of `deployment-code-changes-briefing.md`) — the
   deployment code changes need this solved and flagged it here to avoid doing it twice.
2. **Correctness & Reliability Cleanup** ("open gaps") — transactional outbox in
   Inventory/Payment/Fulfillment Services, the state machine's unimplemented `FAILED` transition, the
   SSE-under-concurrency defect in Order Service, and a retention policy for
   `processed_events`/`deferred_transitions`. Four small, independent, already-diagnosed fixes.
3. **VPS / Remote Development Workflow** — a Hetzner CPX32, provisioned per session and deleted when
   not in use (hourly billing, ~€1–4/month realistic), with enough headroom to run this project's
   Kubernetes workload past the laptop's proven ceiling. Executed in a separate agent session; see
   [`vps-agent-briefing.md`](vps-agent-briefing.md).
4. **Deployment Spike** — get the application running somewhere a link can be shared. The platform
   and sizing decisions are made (see "Deployment decision" below). Of the code-changes work
   (`docs/agent-reports/sprint-2/deployment-code-changes-briefing.md`, W1–W8, plus the newer T1–T4
   tuning items), **W2–W5 and W7 are already done**; **W1, W6, W8, and T1–T4 remain**, all now
   unblocked since the platform question is settled. **Actual provisioning and deploy** continues in
   the same deployment agent session that made the platform decision.
5. **README Demo Walkthrough** — a short recording or annotated GIFs near the top of the README,
   showing the app actually working.
6. **Autoscaler (HorizontalPodAutoscaler)** — formalizes the manual-scaling story from Phase 10 into
   an actual autoscaler.
7. **Bug Hunt** — a time-boxed pass (half a day to a day) focused on concurrency and partial-failure
   paths, the two categories that have produced real bugs so far (per ADR-009 and the SSE defect).

## Deployment decision

The deployment agent session settled the platform question, then revised the box sizes against what
Hetzner actually had in stock (full reasoning in `docs/agent-reports/sprint-2/deployment-platform-options.md`,
`deployment-platform-revision.md`, and `dev-vs-demo-host-separation.md`):

- **One Hetzner CX23 (2 vCPU / 4GB, ~€5.99/month) running k3s**, applying the existing
  `infrastructure/kubernetes/` manifests unchanged — not managed Kubernetes, not a PaaS. The
  originally-planned CX33 isn't currently orderable (a Hetzner capacity constraint on cost-optimized
  plans, not a project decision); CX23 is cheaper anyway and the project's own Phase 10 measurements
  show this exact 8-pod baseline stack already runs inside a smaller memory cap (3.825GiB) than CX23
  provides. CPU, not memory, is the real constraint at this size — see the new tuning work below.
- **Always on**, not spin-up-on-demand — Hetzner bills a server until it's deleted regardless of
  power state, so "on demand" would mean a cold multi-minute boot for a visitor, not real savings.
- **A separate server from goal 3's dev VPS** (now a CPX32, provisioned per session — see goal 3).
  Realistic combined cost: ~€7–10/month.
- **Manual `kubectl apply` for now** — no CI/CD deployment pipeline this sprint. Building `amd64`
  images via GitHub Actions and publishing to GHCR (needed either way, since the laptop is arm64 and
  the box is x86) doesn't reopen that decision — it's image *building*, not deployment automation.
- **New, required tuning work (T1–T4), driven by the CX23's 2 vCPU limit**: replace Kafka's readiness
  probe (`kafka-broker-api-versions.sh`, which starts its own JVM per check — the same probe Phase 10
  already identified as what flaps the broker under CPU contention) with a TCP socket check; cap JVM
  heaps explicitly rather than relying on defaults; relax probe timings for a slower cold start;
  re-check whether the high-volume scenario's timeouts (tuned on 8-core local hardware) still hold on
  2 vCPUs. All four are blocking, not optional — undiagnosed, they'd reproduce the same probe-flapping
  problem Phase 10 found, this time on the public demo.

Both items previously open for the user are now settled: **domain** is a subdomain of
`noelschneider.com` (DNS/TLS already on Cloudflare), and the **idle auto-reset threshold** (W4) is 15
minutes.

## Dependencies

```
security ──► deploy code changes (W1–W8) ──► provision + deploy ──► readme
open gaps
vps ──► autoscaler
    └─► bug hunt
```

- `security` and `open gaps` have no dependencies and can start immediately.
- The deployment session's own recommended sequencing is **security → code changes → provision and
  deploy**: the code changes (W1–W8) need nothing from a live server and can be written and verified
  against the existing local `kind` flow, but should follow security so nothing is hardened on top of
  an unaudited repo. `readme` waits on the deployed instance existing — a real deployment makes for a
  better recording than localhost.
- **One firewall requirement crosses the goal-3/goal-4 boundary and is easy to misfile**: the
  deployment code changes (W1) require the *production demo box*'s firewall to block the NodePort
  range (30000–32767) so it can't bypass the new ingress allowlist. That's part of goal 4's
  provisioning step, not goal 3's dev VPS — the deployment report calls it "the VPS task" generically
  and it's worth being explicit here so it doesn't land on the wrong box.
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
- **A deployment ADR is now ready to write** — the platform decision has landed (see "Deployment
  decision" above), and `deployment-platform-options.md` §7–8 already has the reasoning and rejected
  alternatives in ADR shape. Write it as part of the code-changes work rather than speculatively.
  Possibly one more ADR for the `FAILED`-transition/retention-policy design within `open gaps`. Follow
  the existing `docs/adr/` numbering.
- **A deployment reference doc** (likely `docs/deployment.md`, alongside `docs/architecture-diagram.md`
  and the other frozen contracts) — how to actually deploy this thing, written once `provision + deploy`
  completes. `deployment-code-changes-briefing.md`'s W8 already lists which existing docs need updating
  alongside it.
- **Deployment agent reports already exist**, under `docs/agent-reports/sprint-2/`:
  `deployment-platform-options.md` (original platform comparison and pricing), `deployment-platform-revision.md`
  (the CX23/CPX32 sizing revision and the new T1–T4 tuning work), `dev-vs-demo-host-separation.md`
  (why goal 3 and goal 4 use separate servers), `deployment-code-changes-briefing.md` (W1–W8, the
  standalone briefing for the code-changes work — T1–T4 belong with the same code agent per the
  revision doc), `deployment-code-changes-w2-w5.md` (W2–W5 already complete), and
  `security-hygiene-pass.md` (W7 already complete — the security pass picked it up, so it does not
  need doing again as part of the remaining W1/W6/W8/T1–T4 work).

No Sprint-2 equivalent of `sprint-1/backend-design.md`, `frontend-design.md`, or
`high-level-design.md` is needed — none of these seven goals redesign the system.
