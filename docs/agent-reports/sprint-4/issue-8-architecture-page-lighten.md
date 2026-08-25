# Issue #8 — lighten ArchitecturePage, link out instead of duplicating

## What changed

- `frontend/src/pages/ArchitecturePage.tsx` — removed the `SYSTEM_OVERVIEW_DIAGRAM` mermaid
  flowchart constant (64 lines) and the `<h2>System overview</h2>` diagram render. Replaced it
  with a short paragraph plus a linked-out list (`docs/architecture-diagram.md`, ADR-002, ADR-004,
  `docs/adr/`) pointing at the repo's own docs for full detail, using GitHub `blob/main` URLs
  (`REPO_BASE` const) since the frontend is served as a static nginx build with no `docs/` folder
  available at runtime (checked `frontend/vite.config.ts` and `frontend/nginx.conf` — no doc-serving
  route exists). The `HAPPY_PATH_DIAGRAM` sequence diagram and its render were left untouched, per
  the ticket. The existing "Repository documentation" list at the bottom of the page was converted
  from plain `<code>` text into the same `REPO_BASE` GitHub links, since it already served the
  "link out for detail" purpose the ticket asks for and duplicating link patterns on the same page
  would have been inconsistent. Updated the top-of-file comment to state the page's current
  approach (diagram-plus-links) instead of narrating the old transcription-and-keep-in-sync
  approach that caused the drift, per the documentation rule against narrating document history —
  the fact that Phases 7-10/ADR-009 caused a prior drift is kept as a stated reason `## why this
  ticket exists`, not a revision log.

No other files were touched — `App.tsx` nav/routing, other pages, and doc source files under
`docs/` are all unchanged.

## How this was verified

TypeScript build (includes `tsc -b`) and lint:

```
$ cd frontend && npm run build
> tsc -b && vite build
...
✓ built in 428ms
```

```
$ npx oxlint src/pages/ArchitecturePage.tsx; echo "EXIT: $?"
EXIT: 0
```

Ran the actual production build through `vite preview` and hit it with `curl` to confirm the page
serves and the new content made it into the shipped bundle:

```
$ npm run preview -- --port 4321 &
$ curl -s -o /tmp/preview.html -w "HTTP %{http_code}\n" http://localhost:4321/
HTTP 200
$ grep -c "github.com/noelwschneider/kafka-portfolio-project/blob/main" dist/assets/index-*.js
1
$ grep -o "sequenceDiagram" dist/assets/index-*.js | head -1
sequenceDiagram
$ grep -rl "React / TypeScript console" dist/assets/*.js
(no output — old system-overview diagram text confirmed gone from the shipped bundle)
```

Stopped the preview server afterward (`pkill -f "vite preview"`) — nothing was left running that
wasn't already running before this session (`docker compose ps` showed no containers up at the
start, and none were started for this task since the change is static frontend content with no
backend dependency).

## Judgment calls

- **GitHub `blob/main` links, not relative in-app links.** The frontend is a static SPA served by
  nginx (`frontend/nginx.conf`) with no route or proxy for `docs/*.md`. A relative link like
  `/docs/architecture-diagram.md` would 404 both in the dev server and in the deployed container.
  Absolute GitHub links work identically whether the page is viewed on the deployed demo or read
  directly on GitHub, which fits a portfolio piece meant to be read in both places. `target="_blank"
  rel="noreferrer"` keeps the SPA state intact when a reader clicks through.
- **Converted the existing "Repository documentation" list to real links too**, even though the
  ticket's exit criteria only required linking `docs/architecture-diagram.md` and the ADRs cited
  next to the removed diagram. That list already existed for exactly the "link out instead of
  duplicating" purpose this ticket is about, and it referenced the same target files — leaving it
  as inert `<code>` text next to a newly-linked equivalent list above it would have been an
  inconsistency introduced by this change rather than one already present. This stayed inside the
  single file the ticket scoped and did not touch nav/routing.
- **ADR-002 and ADR-004 citations moved into the new linked list** (with one-line "why" text) rather
  than dropped, since they were annotating specific facts (no service-to-service calls; one schema
  per service) that are still stated in the replacement paragraph, not facts that only made sense
  next to the diagram.
- **Kept the top-of-file comment**, rewritten to state present tense intent (diagram-plus-links,
  why) rather than delete it — the documentation rule against narrating history still permits
  stating a past incident as a fact about the system (the drift really happened, per the ticket's
  own framing), so I kept one line naming it as the reason this page structure exists, and removed
  the part that instructed future editors to "keep the diagram in sync," which is now false since
  there's no duplicated diagram to keep in sync.

## Deliberately not covered

- Did not run a full `docker compose up --build` for the whole stack. This change touches only a
  static frontend page with no backend calls, no props, no state — `vite build` + `tsc -b` +
  `vite preview` against the real production bundle is a faithful reproduction of what the
  containerized nginx build would serve, and starting the full multi-service stack (5 Spring Boot
  services + Kafka + Postgres) to view one static page would not exercise anything this build/
  preview check didn't already cover.
- Did not click-verify the live GitHub URLs resolve (e.g. that `ADR-002-separate-demo-and-business-apis.md`
  is the exact current filename) beyond confirming the filenames against `docs/adr/` on disk in this
  checkout; did not make an actual network request to github.com to confirm the paths 200 there,
  since the files exist at those exact repo-relative paths in this working tree and `main` is what's
  currently pushed.
- Issue #10 (sitewide copy pass) is explicitly out of scope and untouched — only `ArchitecturePage.tsx`
  changed.
- No automated test exists for this page (no test render is included in this ticket's exit criteria,
  and no existing test file for `ArchitecturePage.tsx` was found) — verification was build + lint +
  bundle-content inspection, not a component test.
