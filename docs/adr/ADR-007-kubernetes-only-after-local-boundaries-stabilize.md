# ADR-007: Use Kubernetes only after local service boundaries stabilize

- **Status:** Accepted — **not yet implemented.** Containerization is Phase 7, Kubernetes is Phase 8.
- **Date:** 2026-08-17 (Phase 0)

## Context

Kubernetes is one of the technologies this project exists to demonstrate
(`docs/planning/portfolio-plan.md`'s Primary Portfolio Goals), so it will be built. The question this
decision settles is *when*, and it is a sequencing question with a real trap on the other side.

The trap: writing manifests early means every subsequent boundary change touches them. Phases 1–3
deliberately restructure the system twice — a modular monolith becomes Kafka-connected modules, which
then become four independently deployable services
(`docs/planning/execution-plan.md` §1.1). Deployments, Services, ConfigMaps, and probes written against
the monolith would be rewritten twice before describing anything real, and debugging domain logic
through a pod restart loop is far slower than debugging it in an IDE.

Both source docs say so directly: `docs/planning/implementation-phases.md`'s Phase 0 ends with "Do not
begin with Kubernetes", `docs/planning/high-level-design.md`'s Kubernetes Design section opens with "run
application components in Kubernetes only after they work locally", and
`docs/planning/agent-guidance.md` rule 19 forbids making Kubernetes a prerequisite for early local
development.

## Decision

Kubernetes lands in Phase 8, after service boundaries are stable (Phase 3), reliability behavior exists
(Phase 4), and containers exist (Phase 7).

- **Phases 1–6:** services run from the IDE against PostgreSQL and Kafka in Docker Compose. Kubernetes
  is not installed, not required, and not referenced by any run instruction.
- **Phase 7:** a Dockerfile per service plus a Compose stack that runs the whole system, so a fresh
  clone works from documented commands.
- **Phase 8:** Deployments, Services, ConfigMaps, Secrets, readiness/liveness probes, and resource
  requests/limits — targeting local `kind` (`docs/planning/execution-plan.md` §7, chosen over Minikube
  for reproducibility in GitHub Actions).
- **Deferred past Phase 8:** HorizontalPodAutoscaler (Phase 10, with the scaling demonstration),
  PodDisruptionBudget, NetworkPolicy, and service mesh — the last being an explicit non-goal.
- **The local path stays supported permanently.** Kubernetes never becomes the only way to run this
  project.

Readiness and liveness are treated as genuinely different questions when they are written, per
`docs/planning/high-level-design.md`'s Health and Kubernetes Probes section: a broker that is
temporarily unreachable should fail readiness, not liveness, because restarting a healthy pod does not
fix a dependency — and being able to explain that distinction is part of what the project is for.

## Alternatives considered

**Kubernetes from Phase 1, developing against a local cluster throughout.** Highest fidelity, and it
front-loads deployment problems instead of discovering them late. Rejected because it multiplies the
cost of the two planned restructurings, slows every domain-logic iteration to a build-and-deploy cycle,
and contradicts rule 19. The deployment problems it front-loads are also the ones this project is least
likely to get wrong; the domain and concurrency problems are the risky ones.

**Skip Kubernetes; ship Docker Compose only.** Perfectly adequate to run the system, and honest — a
five-service demo does not need an orchestrator, a point
`docs/planning/portfolio-plan.md` insists must be stated rather than hidden. Rejected because
Kubernetes is an explicit portfolio goal and because parts of the demonstration genuinely need it:
multiple consumer replicas in one consumer group, pod restarts as a way to trigger consumer recovery,
and HPA behavior under Scenario 8's load.

**Managed cloud cluster (EKS/GKE) instead of local `kind`.** More impressive on paper, and it would
force real ingress and image-registry work. Rejected for v1 on cost and reproducibility: a `kind`
cluster is free, starts in a minute, can be recreated identically inside CI, and demonstrates the same
Kubernetes objects. `docs/planning/high-level-design.md` leaves a modest cloud cluster open as a later
option, and nothing here forecloses it.

**Helm charts instead of plain manifests.** Templating would remove per-service duplication. Rejected
for Phase 8 as premature: plain YAML is what a reviewer can read without knowing Helm, and five nearly
identical Deployments are not yet a duplication problem worth a templating layer.

## Consequences and tradeoffs

**Accepted costs.**

- Deployment problems surface late, and some are only visible in a cluster: readiness gating during
  rolling updates, resource limits triggering OOM kills, and SSE connections dropping when a pod is
  replaced (ADR-003 flags the last one).
- Two supported ways to run the system from Phase 7 onward — Compose and Kubernetes — which means two
  sets of configuration and startup documentation to keep accurate
  (`docs/planning/agent-guidance.md` rule 14).
- Manifests must be written against a system that already exists, so Phase 8 cannot start early even if
  the platform workstream is otherwise idle.
- Nothing before Phase 8 proves the services are container-friendly. Configuration must come from
  environment variables and nothing may depend on local filesystem state, or Phase 7 turns into a
  refactor — worth watching for from Phase 1, even though it is not verified until later.

**What it buys.**

- Phases 1–6 iterate at IDE speed, with a debugger attached, which is where the project's actual
  correctness risk lives (`docs/planning/execution-plan.md` §2 puts inventory concurrency at its highest
  scrutiny tier).
- Manifests are written once, against final boundaries.
- Kubernetes gets introduced for reasons that can be defended in an interview — replicas, restart
  behavior, scaling — rather than as a checkbox, which is the framing
  `docs/planning/portfolio-plan.md` requires.
