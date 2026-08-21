# Study Guide — Agent Briefing

Standalone context for whoever picks up this task. Written for a fresh Claude Code session with no
access to the conversation that produced it.

**Suggested model/effort:** Opus, high effort. The value of this document is entirely in the quality
of synthesis and explanation — a mechanically correct but flat summary defeats the purpose. This is a
large task, closer to a technology primer plus an annotated-codebase walkthrough plus a build tutorial
than a decision-explainer; expect it to span many sessions, which is fine and expected.

## Project

`kafka-portfolio-project` — an event-driven order-fulfillment portfolio system (Java/Spring Boot,
Kafka, PostgreSQL, Kubernetes, React/TypeScript). Read `.claude/CLAUDE.md` first for repo norms, then
`docs/planning/README.md` for the full doc index — this task will end up touching most of what's
listed there.

## Why this exists, and how ambitious it actually is

The developer intends to share this project publicly (LinkedIn) soon, but the real, permanent goal is
bigger than being able to talk about it credibly: **the developer wants to be able to explain the
project at the level of "what does this specific line of code do, how does it fit into the whole, why
is it needed, and how does the underlying technology actually work" — and, in the limit, to be able to
follow the guide and rebuild an exact replica of this project from scratch.** They've said directly
that they don't feel they currently have command over a project they consider "past their own grasp,"
and this document is the tool meant to close that gap, not just a talking-points sheet for a technical
interview. Take that goal at face value rather than quietly scoping it back down to something more
convenient. `docs/planning/portfolio-plan.md` §31–32 (the interview-answer checklist) is still useful
raw material, but it is no longer the spec for this task — it's one input among several, and this
document's actual bar is much higher than answering those 20 questions well.

## The shape of the content: four layers, every chunk

Every topic this guide covers should be developed through all four of these, not just one:

1. **The problem.** What this solves, independent of this project — why event-driven systems need
   idempotent consumers *at all*, before how this project's consumers handle it.
2. **The underlying technology.** A real primer on the concept itself — what a Kafka partition is,
   what optimistic locking is, what a Kubernetes readiness probe is — written for genuine
   unfamiliarity, not jargon-recognition. This is the layer most likely to be underweighted by
   default; don't skip it because the concept feels obvious once you already know it.
3. **The decision.** Why *this* project solved the problem this specific way, what alternatives
   existed and why they were rejected. The ADRs (`docs/adr/*.md`, 11 as of this sprint) already do
   most of this work — each one is shaped as decision + rationale + alternatives + consequences.
4. **The code.** Real excerpts, not paraphrase — actual file paths and line numbers, walked through
   directly, explained at the level of "here's what this block does and why it's written this way."
   This is the layer that makes the guide build-along-capable rather than just explanatory.

## Structure: chunked by build order, not by topic category

A build-along guide has to be sequenced as a build. This project already has that sequencing —
`sprint-1/implementation-phases.md`'s Phase 0–11 breakdown (Design Contract → Modular Monolith →
Introduce Kafka → Extract Services → Reliability → Scenario-Oriented Frontend → Transactional Outbox →
Containerization → Kubernetes → Observability → Scaling Demonstration → Portfolio Polish), extended
with Sprint 2's additions (security hardening, the production deployment, the autoscaler, the outbox
pattern's rollout to the remaining three services, the `FAILED` transition, retention policy) — is
already a build-along outline waiting to have the four layers above filled in against it. Don't invent
a different organizing scheme; group these phases into a manageable number of chapters (a handful, not
twelve — phases vary wildly in size, and each chapter should be a coherent, complete unit covering all
four layers for its scope) and treat that grouping as the guide's table of contents.

The exact chapter boundaries are something the developer wants to work out **with you, directly, at
the start of this task** — see "How to start" below. Don't finalize them unilaterally before that
conversation happens.

## Consolidate repeated patterns instead of re-explaining them every time

Real implementation patterns recur across this codebase — idempotent consumer handling, the outbox
pattern (now in four services), DTO/entity separation, and others you'll surface as you go. Digging
into every single recurrence at full depth would bloat the guide without adding understanding past the
first one or two examples. Identify these patterns and consolidate:

- Explain a recurring pattern **once**, thoroughly (all four layers), at the point it's most natural
  to introduce it.
- At every other site where it's applied, reference back to that explanation rather than repeating
  it — a short note on what's specific or different about that instance is fine and often useful; a
  full re-explanation is not.
- This won't map perfectly onto a strictly linear build-along order if a pattern first appears at one
  phase and recurs at a later one — that's fine. Name and link to wherever it was first explained
  rather than duplicating content to preserve strict linearity.
- One concrete mechanism worth considering: a dedicated concepts/patterns area (e.g., a separate
  subdirectory the phase-ordered chapters link into rather than restate) so a pattern lives in exactly
  one place on disk and everything else points at it. **Don't design this mechanism unilaterally** —
  this is explicitly one of the things the developer wants to work through with you directly once
  you're underway, not something to lock in during initial setup.

## The context-management strategy

Reading the entire codebase to build this document is both wasteful and wrong — the *why* behind a
decision lives in this project's docs layer, not in the implementation, and the *what* is best found
by targeted search once you know what you're looking for, not by reading every file top to bottom.

1. **Primary source for layers 1–3 (problem, technology, decision): the docs layer.** `docs/adr/*.md`
   first, then `docs/architecture-diagram.md`, `docs/order-state-machine.md`, `docs/db-ownership.md`,
   `docs/reliability-pattern.md`, `docs/scenarios.md`, `docs/events/event-catalog.md`,
   `docs/openapi/*.yaml`, `docs/planning/project-overview.md`, and the phase docs in
   `docs/planning/sprint-1/` and `docs/planning/sprint-2/`.
2. **Primary source for layer 4 (code): targeted reads, driven by what the docs told you exists.**
   Once you know a mechanism exists (from the docs), find and read the specific real code that
   implements it — grep/search for it rather than reading a whole service file by file.
3. **Verify, don't blindly trust the docs either.** This repo's docs have drifted from the actual
   implementation before — a documentation audit earlier this sprint found and fixed several stale
   claims. Spot-check anything load-bearing (exact numbers, exact behavior) against real code before
   building an explanation on it.

**Process one chapter at a time.** For each: read only the docs relevant to its scope, do the targeted
code reads for that scope, write the chapter directly to its own file, then move on. Don't hold
earlier chapters' full research in working context — once a chapter is written, keep only a short
running index of what's covered and where (chapter, and pattern-library entries if that mechanism is
adopted), not the research that produced it.

## Where this document lives

**`docs/study-guide/`, inside the repo but gitignored** (already added to `.gitignore` — verify before
writing anything, don't assume). This is personal study material, not portfolio-facing content, but
the developer wants all project-related files living inside the project directory for tidiness rather
than scattered into a home-directory location. Being gitignored means it never gets committed or
pushed despite living in the repo tree — same pattern already used for `docs/agent-reports/`.

Use a directory, not a single file — the scope is too large for one unwieldy document, and a directory
makes chapter-by-chapter revision (see "How the work should proceed" below) concrete rather than
requiring surgery on one giant file. Numbered chapter files matching the agreed chapter breakdown, plus
whatever pattern-library structure comes out of the discussion in "Consolidate repeated patterns"
above.

## How to start

**Do not start writing content immediately.** The developer wants to discuss and agree on the chapter
breakdown, the pattern-consolidation mechanism, and anything else about the structure before content
production begins in earnest. Treat your first task as: read this briefing and the source material
listed above enough to propose a concrete chapter breakdown (grounded in the actual Phase 0–11 history
plus Sprint 2, not invented from scratch), then bring that proposal to the developer as the opening
move of a conversation, not a fait accompli. Once structure is agreed, move into content production.

## How the work should proceed once structure is agreed

The developer expects the first pass of each chapter to be rough, not comprehensive — low pressure,
not a final draft. Write a complete-but-rough first pass covering all four layers, then expect the
developer to request specific revisions once they've actually read it against the real code, rather
than trying to anticipate and pre-empt every gap yourself. After the initial structure conversation,
this should mostly run autonomously with the developer checking in periodically — you don't need their
sign-off on every paragraph, only on the structural questions "How to start" describes.

If you hit a session or usage limit partway through a chapter, that's expected — write to disk as you
go, not all at once at the end, and on resumption re-orient from the output directory's current state
and your running index rather than starting over.

## When pausing (a session boundary, or a natural stopping point)

Report: which chapters are complete, which are open, and — for anything you deliberately chose not to
cover deeply because the source material didn't support a confident answer — say so explicitly rather
than writing a vague or generic-sounding section. A chapter that honestly flags "the ADR doesn't fully
explain this, verify with the developer directly" is more useful than one that papers over a gap with
plausible-sounding filler.

## Starter prompt

The message used to start this session (included for reference, not as an additional instruction —
everything it refers to is covered above):

> This project has an external planning doc at
> `docs/planning/sprint-3/study-guide-agent-briefing.md` — read it first, it has everything you need.
> This is a bigger and more ambitious document than a typical study guide — read the "Why this exists"
> section carefully, it explains what's actually being asked for. Don't start writing content yet:
> come back to me first with a proposed chapter breakdown based on the project's actual build history,
> and we'll work out the structure together before you start producing content.
