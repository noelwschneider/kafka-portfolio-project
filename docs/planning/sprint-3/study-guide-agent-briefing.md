# Study Guide — Agent Briefing

Standalone context for whoever picks up this task. Written for a fresh Claude Code session with no
access to the conversation that produced it.

**Suggested model/effort:** Opus, high effort. The value of this document is entirely in the quality
of synthesis and explanation — a mechanically correct but flat summary defeats the purpose. This is a
long task; expect it to span multiple sessions (see "Session length" below), which is fine.

## Project

`kafka-portfolio-project` — an event-driven order-fulfillment portfolio system (Java/Spring Boot,
Kafka, PostgreSQL, Kubernetes, React/TypeScript). Read `.claude/CLAUDE.md` first for repo norms, then
`docs/planning/README.md` for the full doc index — this task will end up touching most of what's
listed there.

## Why this exists

The developer intends to share this project publicly (LinkedIn) soon, and — separately, permanently —
wants to be able to speak fluently about every major design decision in it during a technical
interview. A portfolio project that can't be defended in conversation converts poorly regardless of
how good the underlying engineering is. `docs/planning/pre-sprint-planning.md` (Tier 2, item 6) framed
this as "arguably the second-most valuable thing on this whole list after the deployment spike itself"
— the deployment makes the project visible; this makes the developer able to actually talk about it.

## The seed material already exists

`docs/planning/portfolio-plan.md` §31–32 already contains the raw checklist this task builds from: a
category-by-category list of concepts to be able to explain (Java/Spring Boot, PostgreSQL, Kafka,
Distributed Systems, Kubernetes, React/TypeScript, Testing), five specific Kafka questions, and 20
numbered interview questions the finished project should let the developer answer. Read that section
in full before doing anything else — it's the closest thing to a spec this task has, and the
document's structure should almost certainly mirror its categories rather than inventing a new
organizing scheme.

## Goal

Produce a study guide that lets the developer explain — in their own eventual words, not by reading
from the document verbatim in an interview — why each major decision in this project was made, what
alternatives existed, and what the tradeoffs were. The test of success is not "does this document
exist," it's "after reading it, could the developer answer portfolio-plan.md's 20 questions
convincingly, unprompted, in a live conversation."

## The context-management strategy (read this carefully — it's the hard part of this task)

Reading the entire codebase to build this document is both wasteful and wrong: it would blow through
context fast, and raw code doesn't explain itself — the *why* behind a decision lives in this
project's docs layer, not in the implementation. Use a **docs-first, code-second** strategy:

1. **Primary source: the docs layer.** `docs/adr/*.md` (11 ADRs as of this sprint) are the highest-
   value source in this repo for this task specifically — each one is already shaped as
   decision + rationale + alternatives considered + consequences, which is exactly the shape of a
   good interview answer. Alongside them: `docs/architecture-diagram.md`, `docs/order-state-machine.md`,
   `docs/db-ownership.md`, `docs/reliability-pattern.md`, `docs/scenarios.md`,
   `docs/events/event-catalog.md`, `docs/openapi/*.yaml` (for concrete contract shapes to cite), and
   `docs/planning/project-overview.md`. Build each section's explanation from these first.
2. **Secondary source: targeted code reads, not exhaustive ones.** For each topic, once you know
   *what* to look for from the docs, find 1-3 short, concrete, illustrative pieces of real code —
   the actual `@Version` field and retry logic for optimistic locking, the actual outbox dispatcher's
   poll loop, the actual SSE emitter's error handling. An interview answer that can point at specific
   real code ("here's the actual check") is stronger than a generic description. Don't read whole
   services top to bottom; grep/search for the specific mechanism the docs already told you exists.
3. **Verify, don't blindly trust the docs either.** This repo's docs have drifted from the actual
   implementation before — a documentation audit earlier this sprint found and fixed several stale
   claims (a README section claiming a feature didn't exist when it did, an ADR correction). Spot-
   check a claim against real code before building an explanation on it if anything seems off or
   especially load-bearing (e.g., exact partition counts, exact retry/timeout numbers).

**Process the document one category at a time**, matching portfolio-plan.md §31's own breakdown.
For each category: read only the docs relevant to that category's topics, do the targeted code
spot-checks for that category, write that section's content directly into the output file, then move
on. Don't hold every category's full research in working context simultaneously — once a section is
written to disk, keep only a short running outline of what's already covered (to avoid duplication or
contradiction across sections), not the full research that produced it.

## Session length

This is explicitly the largest single item on this project's backlog. If you hit a session or usage
limit partway through, that is expected, not a failure — **write each section to the output file as
you finish it**, not all at once at the end, so partial progress is never lost. On resumption (of this
same task, whether by you continuing or a fresh session picking it back up), re-orient by reading the
output file's current state and the short running outline, not by re-deriving everything from
scratch.

## Structure (start here, don't spend time deciding otherwise)

Mirror `portfolio-plan.md` §31's categories as top-level sections: Java/Spring Boot, PostgreSQL,
Kafka, Distributed Systems, Kubernetes, React/TypeScript, Testing. Add two more:

- **Project narrative** — the elevator pitch, why decisions were made in roughly the order they were,
  what's genuinely still a known gap versus finished (the "portfolio complete" checklist in
  `portfolio-plan.md` §39 and this project's ADRs' own "Consequences"/"Alternatives" sections are good
  source material — several ADRs explicitly discuss what was deferred and why).
- **The 20 questions** (`portfolio-plan.md` §32) — answered directly and specifically, each one citing
  back to the relevant category section rather than repeating the explanation in full.

Within each category, structure content as: concept → why it matters here specifically (not a generic
definition) → the actual decision/implementation → what alternative existed and why it was rejected
(ADRs are gold for this) → a specific example from real code where one strengthens the answer.

## Where this document lives

**Do not put this under `docs/` or anywhere else tracked by git.** This is personal interview prep,
not portfolio-facing content — nobody reading the repo should encounter it, and it doesn't need to
read well to a stranger, only to be useful to the developer. Write it to
`~/Documents/kafka-portfolio-study-guide.md` (matching the existing pattern of other personal
reference docs for this project, e.g. `~/Documents/local-vs-cloud-dev-infra.md`), not into the repo.

## When done (or when pausing for a session boundary)

Report: which sections are complete, which are still open, the file's current location and length,
and — for anything you deliberately chose not to cover deeply because the source material didn't
support a confident answer — say so explicitly rather than writing a vague or generic-sounding
section. A section that honestly says "the ADR doesn't fully explain this, verify with the developer
directly" is more useful than one that papers over a gap with plausible-sounding filler.

## Starter prompt

The message used to start this session (included for reference, not as an additional instruction —
everything it refers to is covered above):

> This project has an external planning doc at
> `docs/planning/sprint-3/study-guide-agent-briefing.md` — read it first, it has everything you need
> (why, the source-material strategy, the structure, where the output goes). Build the interview
> study guide it describes. This is a large task — work through it section by section, write
> progress to disk as you go, and don't worry about finishing everything in one sitting.
