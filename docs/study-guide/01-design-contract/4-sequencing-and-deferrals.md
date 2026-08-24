# 1.4 — Sequencing, and what Phase 0 refused to decide

[← State and API contracts](3-state-and-api-contracts.md) · [Chapter 1 ↑](README.md) · [Chapter 2 →](../02-domain/README.md)

Phase 0's last two outputs are not artifacts. They are a build order, and a list of things
deliberately left undecided.

---

## Part A — Why Kubernetes waits until Phase 8

`docs/planning/sprint-1/implementation-phases.md`'s Phase 0 section ends with a five-word paragraph:

> Do not begin with Kubernetes.

This is ADR-007, and it is the decision most likely to feel like an anticlimax and most likely to
save you a week.

### The problem: infrastructure written against a moving target

Kubernetes is one of the technologies this project exists to demonstrate, so it is definitely getting
built. The question is *when*, and it is a sequencing question with a real trap on the far side.

The trap is this. The build plan **deliberately restructures the system twice**: a modular monolith
(Phase 1) becomes Kafka-connected modules in one process (Phase 2), which become four independently
deployable services (Phase 3). Deployments, Services, ConfigMaps, and probes written against the
monolith would be rewritten twice before they described anything real.

There is a second cost, which is the one you feel daily. Debugging domain logic through a pod restart
loop is dramatically slower than debugging it with a debugger attached in an IDE. Every iteration
becomes build → image → push → rollout → read logs. And Phases 1–6 are precisely where this project's
actual correctness risk lives — inventory concurrency, idempotency, transition ordering. Those are
the problems worth iterating fast on.

### The decision

Kubernetes lands in **Phase 8**, after boundaries are stable (Phase 3), reliability behavior exists
(Phase 4), and containers exist (Phase 7).

- **Phases 1–6:** services run from the IDE against PostgreSQL and Kafka in Docker Compose.
  Kubernetes is not installed, not required, and not referenced by any run instruction.
- **Phase 7:** a Dockerfile per service plus a Compose stack running the whole system.
- **Phase 8:** Deployments, Services, ConfigMaps, Secrets, probes, resource requests and limits,
  against local `kind`.
- **Deferred past Phase 8:** HorizontalPodAutoscaler, PodDisruptionBudget, NetworkPolicy, service
  mesh (an explicit non-goal).
- **The local path stays supported permanently.** Kubernetes never becomes the only way to run this
  project.

### Rejected alternatives

- **Kubernetes from Phase 1.** Highest fidelity; front-loads deployment problems. Rejected because it
  multiplies the cost of both planned restructurings — and with a good second argument: *"the
  deployment problems it front-loads are also the ones this project is least likely to get wrong; the
  domain and concurrency problems are the risky ones."* Sequence your work against where the risk
  actually is.
- **Skip Kubernetes; ship Compose only.** Perfectly adequate to run the system, and honest — a
  five-service demo does not need an orchestrator. Rejected because Kubernetes is an explicit
  portfolio goal *and* because parts of the demonstration genuinely need it: multiple replicas in one
  consumer group, pod restarts as a way to trigger consumer recovery, HPA behavior under load.
- **A managed cloud cluster (EKS/GKE) instead of `kind`.** More impressive on paper. Rejected on cost
  and reproducibility — `kind` is free, starts in a minute, and can be recreated identically in CI.
- **Helm instead of plain manifests.** Templating would remove duplication. Rejected as premature:
  *"plain YAML is what a reviewer can read without knowing Helm, and five nearly identical
  Deployments are not yet a duplication problem worth a templating layer."*

### The costs, recorded

ADR-007 is unusually good about naming what deferring actually costs:

- **Deployment problems surface late**, and some are only visible in a cluster: readiness gating
  during rolling updates, resource limits triggering OOM kills, and SSE connections dropping when a
  pod is replaced. All three of those happened. Two of them are in
  [Chapter 9](../09-production/README.md).
- **Two supported ways to run the system** from Phase 7 onward, which means two sets of configuration
  and startup documentation to keep accurate.
- **Nothing before Phase 8 proves the services are container-friendly.** Configuration must come from
  environment variables and nothing may depend on local filesystem state, *"or Phase 7 turns into a
  refactor — worth watching for from Phase 1, even though it is not verified until later."*

That last one is the practical takeaway from this ADR, and it is worth acting on from your very first
service: **write configuration as environment variables with sensible defaults from day one**, even
while you are running everything from an IDE. It costs nothing then and it is what makes Phase 7 a
packaging exercise instead of a rewrite. [Chapter 2](../02-domain/README.md) does this from the start.

### The related sequencing decision

The same reasoning produces the build order itself, pinned in
`docs/planning/project-overview.md`:

> **Build sequence**: modular monolith first (Phase 1), Kafka introduced in-process (Phase 2), then
> extracted into 4 independent services (Phase 3). Don't jump straight to separate services before
> Phase 3.

Prove the business workflow while it is still one process and one debugger away. *Then* make it
asynchronous. *Then* make it distributed. Each step changes exactly one thing about the system, so
when it breaks you know which change broke it.

This is the single most transferable idea in the project. Distributing a system you have not yet got
working is how you end up debugging your domain logic and your infrastructure simultaneously, unable
to tell which is lying to you.

> **Where the guide diverges from history.** Phases 2 and 3 were not actually separate steps — the
> real commit is `1f2bc50 introduce kafka and split up monorepo`, doing both at once.
> [Chapter 3](../03-kafka-and-services/README.md) follows the commit rather than the plan, and says so.

---

## Part B — What Phase 0 deliberately did not decide

An underrated half of contract-first work is knowing where to stop. Deciding everything up front is
just as much a failure as deciding nothing — it is guessing, with the guesses written down in an
authoritative-looking file.

Phase 0 left several things open, each for a stated reason.

**JSON Schema files for event payloads.** The event catalog says so directly:

> JSON Schema files for each payload (`docs/events/schemas/*.json`) are deliberately **not** part of
> Phase 0 [...] which places them at Phase 2, once payloads have been exercised by real producers and
> consumers.

A machine-readable schema for a payload that no producer has ever produced is a guess wearing
formalwear. The prose description is enough to build against; the formal schema is worth writing once
reality has had a chance to disagree with it.

**Everything about scaling.** Partition counts, replica counts, resource limits, autoscaler
thresholds. None of these can be chosen without measurements, and there was nothing to measure. They
are settled in [Chapter 8](../08-observability-and-scaling/README.md) against real numbers.

**The `FAILED` state's entry condition.** `FAILED` appears in the frozen state list with no
transitions into it. The state machine document formalizes transition 9 and flags exactly what it
did:

> This is the one state in this document whose entry condition Phase 0 supplied rather than
> formalized.

An admission that one row of the table is a Phase 0 invention rather than a Phase 0 transcription.
That row went unimplemented until Sprint 2 — which the ADR that shipped alongside it says out loud
rather than quietly closing.

**Deployment, entirely.** There is no Phase 0 decision about where this runs. It was made in Sprint 2
(ADR-010), when there was a working system and an actual reason to deploy it.

---

## Part C — The ADR as an artifact

Eleven ADRs, in `docs/adr/`, one file each, all the same shape:

```
# ADR-00N: <decision, stated as an imperative>

- **Status:** Accepted. <where it was implemented>
- **Date:** <when>

## Context          — the forces. What makes this a real question.
## Decision         — what was chosen, concretely enough to check code against.
## Alternatives considered
## Consequences and tradeoffs
    **Accepted costs.** / **What it buys.**
```

Four properties of this format are doing the work.

**The title is the decision, not the topic.** "Use Kafka for asynchronous order lifecycle events,"
not "Messaging." You can read the eleven filenames and know what the system is.

**Status records where it was implemented, and when that changed.** ADR-006's status line is four
sentences long because the decision shipped in stages and Sprint 2 changed its scope. A status that
says only "Accepted" tells you nothing about whether the code matches.

**Alternatives are argued in good faith.** This is the property that separates a useful ADR from a
justification. ADR-001 says synchronous REST is *"genuinely the right answer for a system of this
size."* ADR-004 says a shared schema has *"the strongest integrity guarantees."* ADR-007 says Compose
alone would be *"perfectly adequate to run the system, and honest."*

An ADR whose rejected options are all obviously bad has recorded nothing. The whole value is in the
close calls — and in an interview, "here's the strongest argument for the thing I didn't do" is a far
better answer than a list of reasons your choice was inevitable.

**Costs are stated as costs.** Not "considerations," not "future work." ADR-002 calls its own demo
override *"a demo-only wart."* ADR-001 says *"every read-your-writes expectation is gone."* You
cannot defend a design you have only ever described the upside of.

### When to write one

Write an ADR when a decision has a **real alternative** and a **cost you will forget**. Not for
choices with one obvious answer, and not for things the code already says clearly. Eleven records for
a project this size is about right — enough to cover every structural choice, few enough that reading
all of them is an afternoon rather than a project.

---

## Chapter 1 in one paragraph

Phase 0 produced no running code and decided seven things: where the service boundaries are and why
nothing crosses them synchronously (ADR-001); who owns which tables and why nobody reads anyone
else's (ADR-004); the envelope, topics, and keys every event uses; the nine order states and the nine
transitions between them; the REST surface of five services; the failure scenarios worth
demonstrating; and the order the whole thing gets built in (ADR-007). It also wrote down what it
deliberately did not decide. Everything after this chapter is downstream of those documents, and the
two places where the code has since drifted from them — `demo.events` in
[section 2](2-the-event-contract.md), `outbox_events` ownership in
[section 1](1-boundaries-and-ownership.md) — are drift in the *documents*, found by checking them
against the code rather than the other way round.

---

[← State and API contracts](3-state-and-api-contracts.md) · [Chapter 1 ↑](README.md) · [Chapter 2 — The domain, built synchronously →](../02-domain/README.md)
