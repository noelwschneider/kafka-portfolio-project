# Order Fulfillment Systems Lab — Study Guide

A build-along explanation of this project: what every significant piece of it does, why it exists,
how the underlying technology actually works, and how you would build the whole thing again from an
empty directory.

This is personal study material. It lives in the repo for tidiness but is gitignored
(`.gitignore:6`) and never gets committed or pushed.

---

## How to read this

Start at [`00-orientation.md`](00-orientation.md), then go in order. The chapters are sequenced as a
build: each one assumes everything before it exists and nothing after it does.

If you only want one topic, the chapter list below and the two reference directories
(`patterns/`, `technology/`) are addressable directly — but chapters lean on earlier chapters, so a
cold jump into Chapter 6 will send you backwards.

---

## Chapters

| # | Chapter | Covers | Build history |
|---|---|---|---|
| 0 | [Orientation](00-orientation.md) | The system on one page, vocabulary, how the guide works, build-along prerequisites | — |
| 1 | [The design contract](01-design-contract/README.md) | Service boundaries, the event envelope, topics and keys, the order state machine, database ownership, the `/api` vs `/demo` split, OpenAPI | Phase 0 |
| 2 | [The domain, built synchronously](02-domain/README.md) | Spring Boot layering, JPA, Flyway, validation, error handling, transactions, the four business domains, the first React UI | Phase 1 |
| 3 | [Kafka, and the split into services](03-kafka-and-services/README.md) | Kafka fundamentals, Spring Kafka wiring, the workflow re-expressed as events, correlation IDs, Maven multi-module extraction, per-service schemas | Phases 2–3 |
| 4 | [Reliability: duplicates, failures, ordering](04-reliability/README.md) | Idempotent consumers, retry and DLQ, retryable vs non-retryable, optimistic locking, cross-topic ordering and deferred transitions, consumer pause/resume, retention | Phase 4 |
| 5 | [The scenario engine and the live frontend](05-scenarios-and-frontend/README.md) | Scenario Service, the eight scenarios, SSE, the React console, event projection, consumer lag | Phase 5 |
| 6 | [The dual-write problem and the transactional outbox](06-outbox/README.md) | Why a DB write and a Kafka publish cannot be one transaction, the outbox table, the dispatcher, what it does and does not buy | Phase 6 + Sprint 2 |
| 7 | [Containers and Kubernetes](07-containers-and-kubernetes/README.md) | Dockerfiles, Compose, the Kubernetes object model, probes, resources, secrets, running on `kind` | Phases 7–8 |
| 8 | [Observability and scaling](08-observability-and-scaling/README.md) | Actuator, Micrometer/Prometheus, structured logging, correlation IDs across services, Grafana, consumer groups vs replicas, partitions as the parallelism ceiling, the HPA | Phases 9–10 |
| 9 | [Production: the public demo](09-production/README.md) | k3s on a Hetzner box, kustomize overlays, GHCR and cross-arch image builds, ingress and allowlisting, tuning for 2 vCPU, sequential rollouts, idle reset | Sprint 2 |
| 10 | [Retrospective: what we got wrong](10-retrospective/README.md) | The bugs and gaps that were actually found late, how they were found, and what each one taught | Sprint 2 – 3 |

Plus **[`glossary.md`](glossary.md)** — every term the chapters use without re-explaining, in one
alphabetical list. Chapters deliberately do not link to it on every use.

---

## Reference directories

Two kinds of content are pulled out of the chapters so they live in exactly one place.

### `patterns/`

Implementation patterns **specific to this codebase** that recur across it. Explained once, in full,
at the point the guide first needs them; every later occurrence links here instead of re-explaining.

- [DTO / entity separation](patterns/dto-entity-separation.md)
- [Correlation ID propagation](patterns/correlation-id-propagation.md)
- [The idempotent consumer](patterns/idempotent-consumer.md)
- [The transactional outbox](patterns/transactional-outbox.md)

### `technology/`

General technology concepts, independent of this project — nested by technology
(`technology/kafka/`, `technology/spring/`, and so on). See
**[`technology/README.md`](technology/README.md)** for the full index. A concept gets its own page
when it needs more depth than a chapter's flow can carry, or when two or more chapters need it.

The dividing line between the two directories: `patterns/` is what you would explain *only* about
this codebase; `technology/` is what you would explain the same way on any project.

### Chapter files

Every chapter is a directory containing a `README.md` — the chapter introduction, its section index,
and its *Build it yourself* checklist — plus numbered section files for the body. Links to a chapter
always point at its `README.md`, so cross-references stay valid however a chapter is later split or
merged.

Chapters stay readable on their own: each explains enough to follow it linearly and links out for the
deeper treatment through a callout box at the point the concept first matters. You should never *have*
to leave a chapter mid-paragraph to understand it.

> **Primer — [Some concept](technology/)**
> One line naming what the page covers, so you can tell at a glance whether you need it.

---

## What each chapter contains

Every topic is developed through four layers:

1. **The problem** — what this solves, independent of this project.
2. **The technology** — how the underlying mechanism actually works, written for genuine
   unfamiliarity rather than jargon-recognition.
3. **The decision** — why this project solved it this way, and what was rejected.
4. **The code** — real file paths, real excerpts, walked through line by line.

Each chapter ends with a **Build it yourself** section: an explicit, ordered list of what to create
and change to reach that chapter's state. It is instructions, not an exercise — nothing is withheld
to test you.

---

## Conventions

**Two kinds of callout, and they mean different things.**

> **Not yet.** Something the system genuinely does not handle at this point in the build. Chapter 2
> has no Kafka; Chapter 3 has no idempotency, so a redelivered record double-reserves stock. This is
> build order working as intended, and the callout names the chapter that closes it.

> **We got this wrong.** Something the real project shipped in a broken state and fixed later. The
> build-along always builds the corrected version — the callout points at
> [Chapter 10](10-retrospective/README.md) for what actually happened.

**The build-along is correct by construction.** Where the real build order and the correct design
disagree, the guide teaches the correct design and records the real order in Chapter 10. Known bugs
are never written into the build steps on purpose.

**Code excerpts are real.** Every path is a real path, and excerpts are quoted from the working tree
rather than paraphrased. Where a file has changed since the phase under discussion, the chapter says
so.

**Cite by filename and section title**, not by section number — the project's own docs restart
numbering per file (`docs/planning/README.md`, "Cross-reference note").

---

## If you are resuming this work

A fresh session picking this up should:

1. Read `docs/planning/sprint-3/study-guide-agent-briefing.md` — the standing brief for this task.
2. Read this file, then skim the **Conventions** section above and one finished chapter
   (`03-kafka-and-services/`) to calibrate depth, voice, and the callout styles.
3. Check the status table below against what is actually on disk — the table is the running index.
4. Continue from the first chapter marked *not started*.

Established decisions, so they do not get re-litigated:

- **Correct by construction.** The build-along always builds the corrected version of anything the
  real project got wrong. Real mistakes are recorded in callouts and told as a story in Chapter 10 —
  never written into the build steps.
- **Two callout styles**, meaning different things: *Not yet* (build order, expected) and *We got this
  wrong* (a real shipped bug).
- **Technology primers live in `technology/`**, project-specific recurring patterns in `patterns/`,
  and chapters link out through a `> **Primer — [...]**` or `> **Pattern — [...]**` callout at the
  point the concept first matters. Chapters stay readable straight through.
- **Every chapter is a directory** with a `README.md` (intro, section index, *Build it yourself*) plus
  numbered section files. Cross-chapter links always point at `NN-name/README.md`.
- **Verify against code, not docs.** Several stale claims have already been found this way; they are
  flagged inline as *Open question* callouts rather than repeated.
- Section length follows logical breaks, not a target. Short sections are fine.

---

## Status

**Rough first pass complete across all ten chapters.** Everything below is written and internally
linked; none of it has been read against the real code by the developer yet, which is the next step.

| Chapter | Status |
|---|---|
| 0 — Orientation | complete (rough) |
| glossary | complete (rough) |
| 1 — The design contract | complete (rough) |
| 2 — The domain | complete (rough) |
| 3 — Kafka and the split | complete (rough) |
| `technology/` | 21 primers written |
| 4 — Reliability | complete (rough) |
| 5 — Scenarios and frontend | complete (rough) |
| 6 — Transactional outbox | complete (rough) |
| 7 — Containers and Kubernetes | complete (rough) |
| 8 — Observability and scaling | complete (rough) |
| 9 — Production | complete (rough) |
| 10 — Retrospective | complete (rough) |

Gaps where the source material genuinely does not support a confident answer are flagged inline as
open questions rather than filled with plausible-sounding text.
