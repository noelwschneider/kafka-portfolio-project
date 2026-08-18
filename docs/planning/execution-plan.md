# Order Fulfillment Systems Lab
## Execution Plan (Agent Orchestration Reference)

This document is the operational companion to the **architecture docs** — `project-overview.md`, `portfolio-plan.md`, `backend-design.md`, `frontend-design.md`, `high-level-design.md`, `implementation-phases.md`, and `agent-guidance.md`. Together those define *what* to build and *why*; see [`README.md`](README.md) for what's in each. This document defines *who builds it, in what order, with which Claude model/effort tier, against which contracts, and using which tools*.

References below cite architecture docs by filename, not by section number — the original single-document section numbers (§0–§42) no longer resolve uniquely now that the content is split across 8 files with independently-numbered sections.

An executing agent should be handed **this document plus the specific contract/section files it needs** — not the full set of architecture docs — except for the foundation stage (Phase 0–2), which needs the full architecture docs.

---

# 1. Execution Model

## 1.1 Staging (confirmed)

**Monolith-first, then extract.** Phase 1 builds one Spring Boot application with in-process modules proving the domain logic (order/inventory/payment/fulfillment) and the state machine, with no Kafka. Phase 3 extracts that proven logic into four independently deployable services. This costs some restructuring work in Phase 3 but de-risks domain-logic bugs before distributed-systems bugs are introduced, and produces a genuine "monolith → microservices" narrative for interviews.

## 1.2 Concurrency (confirmed)

**Hybrid: sequential foundation, then parallel services.**

- **Sequential thread** (single agent lineage, one session/context building on the last): Phase 0 (contracts) → Phase 1 (monolith) → Phase 2 (introduce Kafka in-process) → the start of Phase 3 (defining final service boundaries and per-service repo/module layout).
- **Parallel fan-out**: once Phase 3's boundaries and the frozen contracts exist, Inventory Service, Payment+Fulfillment, Frontend, and (later) Platform/Infrastructure become independent parallel workstreams, each reading only the frozen contract files plus its own service's existing code — not the full architecture plan.
- **Re-sequentialize for cross-cutting phases**: Phase 4 (Reliability) touches all services' consumers with the same pattern (idempotency table, retry/DLQ config) — do this as one pass per service but coordinate through a single shared "reliability pattern" reference (§5 below) so the four implementations don't drift. Phase 6 (Outbox), Phase 8 (Kubernetes), Phase 9 (Observability) are naturally cross-cutting too; each gets its own fan-out once its shared pattern is written once.

## 1.3 Why not fully parallel from Phase 0

Contract changes mid-flight (an endpoint shape, an event field) would force rework across every agent already building against them. Freezing contracts before fan-out avoids that. This matches agent-guidance.md's own coordination rule (Agent Coordination Rules section): "freeze initial endpoint names... before parallel work."

---

# 2. Model / Effort Tier Assignments

**Confirmed allocation: Balanced.** Tier assignments below follow the official guidance in `docs/external/claude-effort.md`. Two rows run at **xhigh**: the frozen contract and inventory concurrency correctness, both because a subtle mistake there is expensive/silent and cascades into other work, which the official doc identifies as exactly the case for stepping an Opus task up from its "high" floor. Everything else Opus-tier runs at **High** — official guidance treats High as Opus's minimum for intelligence-sensitive work, and none of the remaining Opus tasks here are broad, multi-file, cascading problems the way Phase 0 and inventory concurrency are. Sonnet tasks run at **Medium** where the work is boilerplate/well-specified/low-ambiguity — the official doc notes Sonnet 5 at Medium is comparable in quality to Sonnet 4.6 at High, so this is a real cost saving, not a quality compromise — and at **High** or **Low** where the task's actual risk profile calls for it.

| Workstream | Phase(s) | Model | Effort | Why |
|---|---|---|---|---|
| Contracts & event catalog | 0 | Opus | **xhigh** | Every downstream agent builds against this; a wrong field/endpoint shape cascades into rework across all four services and the frontend. Produces ~15 mutually-consistent files in one pass (5 OpenAPI specs, event catalog, state machine, DB ownership, 7 ADRs, a diagram) — the "demanding, cascading, multi-file agentic/coding work" the official guidance names as the case for stepping up from Opus's high floor. |
| Modular monolith domain logic | 1 | Sonnet | Medium | Standard Spring Boot CRUD + state machine. Well-specified by the architecture plan; low ambiguity. |
| Kafka introduction (in-process) | 2 | Sonnet | Medium | Mechanical: wire producers/consumers per the already-frozen event envelope. |
| Service extraction (boundary definition) | 3 (start) | Sonnet | High | Requires genuine judgment about what's genuinely shared vs. per-service — closer to a design decision than to per-service CRUD — but bounded by working from proven Phase 1 code, not from scratch, so Opus isn't warranted. |
| Order Service (post-extraction) | 3+ | Sonnet | Medium | REST + persistence + event publish, following the frozen contract. |
| Inventory Service — reservation/concurrency logic specifically | 3+ | Opus | **xhigh** | Optimistic-locking correctness under contention (frontend-design.md's Scenario 7 — Inventory Contention, success condition: "total reserved inventory never exceeds available inventory") is the one place a subtle bug silently oversells inventory and undermines the project's core reliability claim. Concurrency bugs are exactly the "hardest coding problems" category the official guidance points to xhigh for — the extra reasoning depth is cheap insurance against a failure mode that's hard to catch by testing alone. Everything else in this service (CRUD scaffolding, DTOs) can stay Sonnet. |
| Payment + Fulfillment Service | 3+ | Sonnet | Medium | Deterministic simulator logic; low ambiguity. |
| Reliability pattern (idempotency table, retry/backoff, DLQ) — written once | 4 | Opus | High | This pattern gets copied into all four consumers; get it right once at higher scrutiny rather than four times at lower. Narrower scope than Phase 0 (one doc, one pattern) — High is Opus's floor for intelligence-sensitive work and sufficient here; doesn't need xhigh. |
| Reliability pattern applied per-service | 4 | Sonnet | Medium | Mechanical application of the already-designed pattern. |
| Frontend (all pages, SSE client) | 5 | Sonnet | Medium | UI implementation against a frozen REST/SSE contract; no architectural ambiguity. |
| Transactional outbox | 6 | Opus | High | Correctness-sensitive (same category as idempotency) but isolated to one service (Order Service), one well-documented pattern — High is sufficient; escalate to xhigh only if implementation reveals more difficulty than expected. |
| Dockerfiles + Compose | 7 | Sonnet | Low | Mechanical containerization of already-working services — matches the official guidance's "simpler tasks that need the best speed and lowest cost." |
| Kubernetes manifests | 8 | Sonnet | Medium | More moving parts (probes, ConfigMaps, resource limits) than Docker Compose but still config, not design. |
| Observability (structured logs, correlation IDs, Actuator, optional Prometheus/Grafana) | 9 | Sonnet | Medium | Mostly wiring existing libraries; correlation ID propagation needs care but is well-specified. |
| Scaling demo (HPA, load generation) | 10 | Sonnet | Medium | Config + measurement, not new design. |
| README/docs/polish | 11 | Sonnet | Low | Writing task, not engineering judgment. |
| Verification passes (exit-criteria checks per phase) | every phase | Sonnet | High | See §6 below — a dedicated check, not folded into the builder's own self-report. Its entire job is catching the gap between "tests pass" and actual correctness; an under-resourced verifier defeats its own purpose, and the task is bounded/cheap regardless of tier, so there's no real cost argument for Medium here. |

If a Sonnet-tier task turns out harder than expected mid-implementation (e.g., a concurrency bug surfaces in what was assumed to be simple CRUD), escalate that specific task to Opus rather than the whole workstream.

**Operational note — effort must be set explicitly.** Per `docs/external/claude-effort.md`: "Setting effort to 'high' produces exactly the same behavior as omitting the effort parameter entirely." Sonnet 5 defaults to High when the effort parameter isn't set. That means every row in this table marked Medium or Low only gets its intended cost savings if effort is explicitly passed when that agent is spawned — omitting it silently reverts to High regardless of what this table says. Whoever briefs each workstream's agent is responsible for setting effort explicitly, not just picking the model.

---

# 3. Repository Layout & Isolation Strategy

Follow the monorepo layout already specified in high-level-design.md's Repository Strategy section. Additional rules for multi-agent safety:

- **Git worktrees, not branches-in-place**, for the parallel fan-out stage (§1.2). Each parallel workstream (Inventory, Payment+Fulfillment, Frontend, Platform) gets its own worktree checked out from the same commit where Phase 3 boundaries were frozen, so agents never edit the same working directory concurrently.
- **Directory ownership is non-overlapping by construction**: `services/order-service/`, `services/inventory-service/`, `services/payment-service/`, `services/fulfillment-service/`, `frontend/`, `infrastructure/` map 1:1 to workstreams. An agent should only ever write inside its own top-level directory plus (read-only) the frozen contract files under `docs/`.
- **Contract files are the only cross-boundary reads.** Concretely:
  - `docs/openapi/*.yaml` — one file per service's public API
  - `docs/events/event-catalog.md` — event names, envelope, versioning rules
  - `docs/events/schemas/*.json` — JSON Schema per event payload, once payloads stabilize
  - `docs/order-state-machine.md` — states + valid transitions (draft in backend-design.md's Suggested Order States section)
  - `docs/db-ownership.md` — which service owns which tables (draft in backend-design.md's PostgreSQL Data Model section)
  - `docs/scenarios.md` — the 8 demo scenarios, their endpoints, and their success conditions (frontend and scenario-service workstreams need this)
  - `docs/architecture-diagram.md` — the frozen system/flow diagrams
  - `docs/adr/` — architecture decision records; consult before revisiting a decision already made

  (This list previously omitted the last three, which §4's Phase 0 output row and `.claude/CLAUDE.md` both already treat as frozen contracts — an agent following the old list literally would never have read `scenarios.md`. Flagged in `docs/agent-reports/phase-0.md` §4.7.)
- Merge parallel worktrees back to the trunk only after each passes its own build + tests; do not merge partially-working branches to unblock another agent — give that agent the contract file it needs instead.

---

# 4. Phase-by-Phase Execution Table

For each phase: agent role, model/effort (from §2 above), required inputs, produced outputs, and the gate that must pass before the next phase/fan-out begins. Exit criteria below are restated from implementation-phases.md's own per-phase exit criteria — they are the actual definition of "done" for that phase, not a suggestion.

## Phase 0 — Contracts (sequential)
- **Input:** full architecture plan.
- **Output:** `docs/openapi/*.yaml`, `docs/events/event-catalog.md`, `docs/order-state-machine.md`, `docs/db-ownership.md`, initial ADRs (format per high-level-design.md's Architecture Decision Records section).
- **Gate:** every downstream contract file exists and is internally consistent (event names in the catalog match what the OpenAPI specs imply; state machine matches the events that trigger transitions).

## Phase 1 — Modular Monolith (sequential)
- **Input:** Phase 0 contract files (not the full plan).
- **Output:** single Spring Boot app, modules per domain, minimal React UI (create/list/detail order).
- **Gate (from implementation-phases.md):** happy path works, out-of-stock works, payment rejection works, tests protect domain rules.

## Phase 2 — Introduce Kafka in-process (sequential)
- **Input:** Phase 1 codebase, event catalog.
- **Output:** producers/consumers replacing direct method calls, still one deployable app.
- **Gate:** happy path travels through Kafka; REST endpoint returns before fulfillment completes; UI observes async transitions.

## Phase 3 — Extract Services (boundary definition sequential; then fan-out)
- **Boundary definition (sequential):** split the monolith into four Maven modules/repos-in-monorepo per §3 above. This is where the frozen contracts get load-bearing for the first time.
- **Fan-out (parallel, separate worktrees):** Order Service, Inventory Service, Payment+Fulfillment Service each become independently buildable/runnable against the frozen contracts.
- **Gate:** services independently stoppable/restartable; order processing recovers correctly; boundaries are self-explanatory from directory structure alone.

## Phase 4 — Reliability (pattern written sequentially, then fan-out)
- **Pattern design (sequential, Opus):** design the `processed_events` idempotency check, retry/backoff policy, and DLQ routing once, documented in `docs/reliability-pattern.md`.
- **Fan-out (parallel):** each service applies the pattern to its own consumer(s).
- **Gate:** each advertised failure scenario (duplicate, poison message, consumer outage, contention) is backed by an automated integration test, per-service.

## Phase 5 — Frontend (parallel, can start once Phase 3 contracts are stable — does not need to wait for Phase 4)
- **Input:** frozen REST/SSE contracts, event catalog (for the Event Explorer and timeline).
- **Output:** all pages from frontend-design.md's Frontend Pages section, SSE client.
- **Gate:** a reviewer can understand and exercise the system without reading source.
- **Note:** frontend work against Phase 3's contracts can genuinely run concurrently with Phase 4's reliability work on the backend, since neither changes the other's contract surface. Only wire in reliability-specific UI (DLQ inspector, retry counts) once Phase 4 lands.

## Phase 6 — Transactional Outbox (sequential, Order Service only)
- **Gate:** business transaction and its event are durably coupled; documented tradeoff vs. Phase 1–5's simpler publish-after-commit.

## Phase 7 — Containerization (parallel per service, Platform workstream)
- **Gate:** fresh clone runs via documented `docker compose up`.

## Phase 8 — Kubernetes (Platform workstream, after Phase 7)
- **Gate:** full app runs in Kubernetes with probes/ConfigMaps/Secrets/resource limits.

## Phase 9 — Observability (parallel per service, then a shared dashboard step)
- **Gate:** a scenario can be traced across services via correlation ID without guessing.

## Phase 10 — Scaling Demo (Platform workstream)
- **Gate:** demonstrates why consumer groups + k8s scaling matter, with real measurements.

## Phase 11 — Polish (sequential, single writer for consistency of voice)
- **Gate:** README/diagrams/ADRs accurately describe the *actual* implementation (portfolio-plan.md's Resume Bullets rule: don't claim resume bullets that aren't true yet).

---

# 5. Coordination Protocol

Restates and operationalizes agent-guidance.md's Agent Coordination Rules section:

1. A contract file under `docs/` is the only thing a workstream may treat as ground truth about another service.
2. If a workstream discovers the frozen contract is wrong or insufficient mid-implementation, it stops, proposes the change in the relevant `docs/` file with a one-line rationale, and does not proceed on a local workaround.
3. Contract changes after fan-out has begun require a brief broadcast (e.g., a note in `docs/CHANGELOG-contracts.md`) so other in-flight workstreams know to re-check their assumptions before merging.
4. No workstream edits another workstream's top-level directory. If a genuine shared-code need emerges (e.g., a common event-envelope Java class), it goes in a new `services/common/` module owned by the Phase 0/3 foundation lineage, not copy-pasted or reached into.

---

# 6. Verification Passes

Each phase gate (§4) should be checked by a step distinct from the implementing agent's own self-report — a fresh-context Sonnet pass that:
1. Re-reads the phase's stated exit criteria from the architecture plan.
2. Runs the actual test suite / build for that phase's scope.
3. Confirms the specific scenario(s) that phase claims to support actually execute (not just "tests pass" — e.g., for Phase 4, actually trigger the duplicate-event integration test and confirm no duplicate side effect).
4. Reports pass/fail per criterion, not a holistic summary.

This catches the common failure mode where an implementing agent's summary says "done" but a specific criterion (e.g., "total reserved inventory never exceeds available inventory" under real concurrency) was never actually exercised.

---

# 7. Tools & Dependencies (consolidated)

## Languages / runtimes
- Java 21 (LTS)
- Node 22 (LTS)
- TypeScript

## Backend
- Spring Boot 4.x (bumped from the original 3.x pin during Phase 1 — see `docs/agent-reports/phase-1.md` §1a for the migration notes: entity-manager-factory post-processor package move, `TestRestTemplate` → `RestTestClient`)
- Spring Web (REST)
- Spring Data JPA / Hibernate
- Spring for Apache Kafka (`spring-kafka`; under Boot 4's modularized autoconfiguration the Maven coordinate to actually depend on is `spring-boot-starter-kafka`, not the bare `spring-kafka` artifact — same package-split reasoning as the Spring Boot 3→4 line above, see `docs/agent-reports/phase-2.md` §3.2)
- Spring Security (only if/when auth is added — see high-level-design.md's Security Scope section, not in v1)
- Flyway (migrations; under Boot 4's modularized autoconfiguration, `flyway-core` + `flyway-database-postgresql` alone do not activate Spring Boot's Flyway autoconfiguration — the Boot-4-appropriate module is `org.springframework.boot:spring-boot-flyway`, same pattern as the Spring Boot 3→4 and Kafka-starter lines above, see `docs/agent-reports/phase-3-boundary.md` §3)
- Maven (multi-module build)
- Bean Validation (`jakarta.validation`)
- JUnit 5
- Testcontainers (PostgreSQL + Kafka modules)
- Micrometer + Spring Boot Actuator (health/metrics; Prometheus registry when Phase 9 needs it)

## Frontend
- Vite
- React
- TypeScript
- TanStack Query
- native `EventSource` (SSE) — no extra library needed
- React Testing Library
- Playwright (later addition per high-level-design.md's Testing Strategy section)

## Infrastructure
- PostgreSQL
- Apache Kafka — `apache/kafka` Docker image (KRaft mode, no ZooKeeper)
- Docker + Docker Compose
- Kubernetes: `kind` for local dev (recommended over Minikube/Docker Desktop k8s for CI reproducibility — a `kind` cluster can be spun up identically in GitHub Actions)
- Optional: Kafka UI (e.g. `kafbat/kafka-ui` or `provectuslabs/kafka-ui`) for local inspection during development — dev convenience only, not part of the demo product
- Prometheus + Grafana (Phase 9 stretch)

## CI/CD
- GitHub Actions, path-filtered workflows per service (`services/order-service/**` triggers only that service's job, etc.)
- Docker image build/push (Phase 3 of CI per high-level-design.md's CI/CD section) to a registry — **decide at Phase 7**: GitHub Container Registry (`ghcr.io`) is the default recommendation since it needs no separate account and integrates with GitHub Actions auth directly.

## Documentation
- OpenAPI (YAML) for REST contracts
- JSON Schema for event payloads
- Markdown ADRs (format per high-level-design.md's Architecture Decision Records section)

---

# 8. Open Items Deferred to Later in This Planning Session

None currently — tech stack pins (project-overview.md's Pinned Technology Decisions table) resolved the remaining "or" choices. If a new ambiguity surfaces during Phase 0 contract design (e.g., a specific OpenAPI tooling choice, a specific Testcontainers version pin), record the decision in that same table rather than leaving it implicit in code.
