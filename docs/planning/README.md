# Order Fulfillment Systems Lab — Planning Docs

This directory holds the pre-implementation planning docs for the project, split from a single original draft (preserved at [`_old/order-fulfillment-systems-lab-action-plan.md`](_old/order-fulfillment-systems-lab-action-plan.md)) into 8 focused files. **Any agent picking up this project should read this index first**, then the files relevant to its assigned task — not the full set, except for Phase 0–2 foundation work (see `execution-plan.md`).

## Reading order

| # | File | What's in it | Read when |
|---|---|---|---|
| 1 | [`project-overview.md`](project-overview.md) | Purpose, product definition, pinned technology decisions, scope do's/don'ts, non-goals | Always first — sets the frame everything else assumes |
| 2 | [`portfolio-plan.md`](portfolio-plan.md) | Why this project exists (portfolio goals), recruiter/engineer presentation, interview knowledge checklist, "portfolio complete" checklist, resume bullets | Before writing any user-facing copy or judging "is this done" |
| 3 | [`backend-design.md`](backend-design.md) | The four backend services, event-driven lifecycle, event envelope, Kafka topic strategy, reliability patterns (idempotency/retry/DLQ/outbox), PostgreSQL data model, order state machine, seed data, REST API design | Primary reference for Phase 0 (contracts) and any backend service work |
| 4 | [`frontend-design.md`](frontend-design.md) | Frontend product direction, page-by-page spec, SSE strategy, the 8 required demo scenarios, frontend UX principle | Primary reference for Phase 5 (frontend) work |
| 5 | [`high-level-design.md`](high-level-design.md) | Kubernetes design, health probes, Docker/local dev, observability, testing strategy, API error model, security scope, repo strategy, ADR format, CI/CD | Primary reference for Phase 7–10 (platform/infra) work |
| 6 | [`implementation-phases.md`](implementation-phases.md) | Phase 0–11 definitions and exit criteria (the technical roadmap) | Check before/after every phase — this is the actual "what does done mean" reference |
| 7 | [`agent-guidance.md`](agent-guidance.md) | Agent rules (do/don't), per-agent ownership breakdown (Agent A–F), coordination rules for contract changes | Read by every agent before starting any task |
| 8 | [`execution-plan.md`](execution-plan.md) | **Operational plan**: execution model (staging + concurrency), Claude model/effort tier per workstream, repo/worktree isolation strategy, phase-by-phase agent+input+output+gate table, coordination protocol, verification-pass process, consolidated tools/dependencies list | The one every executing agent actually works from day-to-day |

`execution-plan.md` is the operational entry point once Phase 0 is underway — it tells an agent which of the other 7 files it actually needs for its specific task, so most agents after the foundation stage should never need to read this whole set.

## Cross-reference note

Files reference each other by filename + section title (e.g. "backend-design.md's PostgreSQL Data Model section"), not by number. The original single-document numbering (§0–§42) does not carry over cleanly across the split — several files independently restart at §1, so a bare "§9" is now ambiguous. If you add new cross-references, cite the filename.

## Known intentionally-dropped content

During the split, three narrative/stretch sections from the original draft were deliberately not carried forward (per-project decision to define stretch goals only once MVP is reached): the Stretch Goals list, the "First-Rendition"/Version 0.2 milestone framing, and the closing README-opening copy. If you want any of these restored, they're intact in `_old/`.

The order-state-machine and seed-data content *was* restored (now in `backend-design.md`) after being dropped unintentionally during the initial split — flagging here in case that history is useful.
