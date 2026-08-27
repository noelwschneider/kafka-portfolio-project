# Architecture page streamline

## What changed

- `frontend/src/pages/ArchitecturePage.tsx` — trimmed to three things per the developer's request:
  diagram, technology table, repository links.
  - Removed the top-of-page bullet list linking `docs/architecture-diagram.md`, ADR-002, ADR-004,
    and `docs/adr/` individually (redundant with the existing "Repository documentation" section).
    Trimmed the intro paragraph's trailing clause that referenced the removed list.
  - Removed the standalone "Service responsibilities" table (service → owns → port) and folded the
    "owns" column into the happy-path Mermaid diagram's `participant` labels, e.g.
    `participant ORD as Order Service<br/>(orders, status history)`. Port numbers were dropped
    entirely (local-dev/docker-compose detail, not architectural; already documented in
    `infrastructure/`/`docker-compose.yml`).
  - Removed the standalone "Event flow" table (topic → published by → carries). The happy-path
    diagram already annotates each publish step with its topic name
    (e.g. `publish OrderCreated (orders.events, key=orderId)`), which covers the same
    topic→publisher→event-type information for the happy path. The DLQ row didn't fit the
    happy-path diagram and was dropped — it remains documented in `docs/reliability-pattern.md`,
    still linked under "Repository documentation."
  - Removed the "Why Kafka," "Why Kubernetes," and "Reliability notes" prose sections in full.
    Replaced them with a new "Technology" section: a single table (Concern → Technology) sourced
    from `docs/planning/project-overview.md`'s "Pinned Technology Decisions" table plus the
    project's actual per-service ownership (Java 21/Spring Boot, Maven multi-module, Kafka KRaft,
    PostgreSQL + Flyway, Kubernetes plain YAML, Vite/React/TypeScript, TanStack Query + native
    EventSource, GitHub Actions).
  - Left "Repository documentation" untouched — verified it still links only `docs/adr/` (the
    directory), never an individual ADR file.

## How this was verified

`npx tsc -b --noEmit` and `npx oxlint` from `frontend/`, both clean:

```
$ npx tsc -b --noEmit
(no output, exit 0)

$ npx oxlint
(no output, exit 0)
```

Rendered the actual page with a real browser (Playwright, chromium) against `vite` dev server on
port 5183 (no backend needed — `/architecture` is a static route with no data fetching):

```
$ node shot.mjs
SVG count: 1
Fallback (error) count: 0
H2 sections: ["System overview","Happy path — order reaches FULFILLED","Technology","Repository documentation"]
Console/page errors: []
```

`SVG count: 1` and `Fallback (error) count: 0` confirm the Mermaid diagram rendered successfully
after the participant-label changes (no `.mermaid-fallback` error state, which is what
`MermaidDiagram.tsx` renders on a parse/render failure). The `H2 sections` list confirms the page's
top-level structure matches the target shape exactly: intro, diagram, technology table, repository
links — nothing extra left behind. A full-page screenshot was also visually inspected: the extended
participant labels (`Order Service<br/>(orders, status history)`, etc.) render on two lines without
breaking the diagram's layout or overlapping adjacent lifelines, and the new technology table
renders in the same plain-table style as the rest of the app.

The throwaway Playwright script (`shot.mjs`) and its screenshot were written to the scratchpad
directory outside the repo and deleted after use; the dev server (`vite --port 5183`) was killed
afterward and confirmed down via a follow-up `curl` connection-refused.

## Judgment calls

- **Technology table row set**: used `docs/planning/project-overview.md`'s "Pinned Technology
  Decisions" table as the authoritative source rather than inventing rows, but added a couple of
  rows it doesn't cover (backend framework = Spring Boot, database = PostgreSQL, orchestration =
  Kubernetes) since those are core to the stack and were previously stated elsewhere on the page
  (service responsibilities table, "Why Kubernetes" section) — omitting them would have left the
  table conspicuously incomplete against what the page used to convey.
- **Port numbers**: per the task's explicit instruction, dropped entirely rather than finding a new
  home for them on this page — they're a docker-compose/local-dev detail, not architecture.
- **DLQ row**: left out of the diagram (failure path, doesn't belong in a happy-path sequence
  diagram) and not reintroduced elsewhere on this page, per the task's explicit call — still
  covered by `docs/reliability-pattern.md`, linked under "Repository documentation."
- **Intro paragraph rewrite**: shortened to two sentences that stand alone without the removed list,
  keeping the "five services / Kafka-only / one schema each" facts since they're not restated
  anywhere else on the trimmed page.
- Table markup for "Technology" reuses the exact same plain `<table><thead>...` structure the page
  already used for the two removed tables, per the task's suggestion, rather than introducing new
  table styling.

## Deliberately not covered

- No CSS/styling changes — the technology table renders identically to the removed tables' look,
  which is what the task asked for ("simple table... not pixel-perfect table styling," a live human
  design review to follow).
- Did not touch `docs/architecture-diagram.md`, the ADRs, or `docs/reliability-pattern.md` — the
  task's premise is that this content still exists there and is only removed from this one page's
  duplication.
- Did not verify the page against a full `docker compose` stack (backend services, SSE, etc.) since
  the Architecture page has no data dependency on the backend — confirmed by inspecting `App.tsx`'s
  route wiring and the page component itself (static content, no `fetch`/`useQuery`/`EventSource`
  calls). A `vite` dev server alone was sufficient and is the narrower, correct verification surface
  for this change.
- Did not run the full frontend test suite (no test file targets `ArchitecturePage.tsx` and none was
  added — the page has no interactive/stateful logic to unit-test beyond what the Playwright render
  check above already covers).
