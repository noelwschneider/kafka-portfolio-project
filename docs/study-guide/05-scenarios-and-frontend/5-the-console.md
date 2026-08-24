# 5.5 — The console

[← Observing the system](4-observing-the-system.md) · [Chapter 5 ↑](README.md)

Three pages become seven, and the frontend stops being a client and starts being the product surface.

---

## Not a storefront

`project-overview.md` sets the frame:

> The frontend should therefore be primarily oriented around: demonstrating normal order processing;
> deliberately exercising failure and edge cases; exposing what is happening inside the distributed
> system; making the architecture understandable to recruiters and engineers quickly.

Four goals, none of which is "sell something." The fake catalog exists only to make orders realistic.

| Page | Purpose |
|---|---|
| Overview | The system at a glance; entry point |
| Orders | List, create, and inspect orders with live status |
| Scenarios | The eight scenarios, with descriptions |
| Scenario Run | The live interleaved timeline — the centerpiece |
| Event Explorer | Every event published, with topic/partition/offset |
| System Health | Per-service health and consumer states |
| Architecture | Rendered diagrams of the system |

---

## React Router, and why it arrived now

```
// Phase 5: seven pages replace the earlier state-based `view` switch in this file (list/create/
// detail only). A `useState` view switch does not scale to seven top-level pages plus nested
// detail routes (order detail, scenario-run detail) that should be independently deep-linkable
// (e.g. sharing a link straight to a scenario run) and back-button-navigable. React Router is a
// small, well-understood addition for exactly this — not adopted for its own sake, and nothing
// else in the app needed lifted global state that would argue for a heavier state library.
```

Three things worth taking from that comment.

**The trigger is named, not assumed.** Not "React apps use a router" but *deep-linkable and
back-button-navigable* — two concrete capabilities that a `useState` switch cannot provide and that
this application specifically needs. Sharing a URL that opens straight to a scenario run is a real
requirement for something meant to be shown to people.

**The scope is bounded.** *"Nothing else in the app needed lifted global state that would argue for a
heavier state library."* Adding a router did not open the door to Redux or Zustand — server state
belongs to TanStack Query ([Chapter 2](../02-domain/6-the-first-frontend.md)) and local state to
`useState`, and neither of those changed.

**It was added when it was needed**, not up front. Three pages did not need a router; seven pages plus
two nested detail routes do.

### The pages did not change

```tsx
function OrdersListRoute() {
  const navigate = useNavigate();
  return (
    <OrdersListPage
      onSelectOrder={(orderId) => navigate(`/orders/${orderId}`)}
      onCreateOrder={() => navigate('/orders/new')}
    />
  );
}
```

`OrdersListPage` still takes `onSelectOrder` and `onCreateOrder` callbacks and still knows nothing
about URLs. Adding routing meant writing thin wrapper components — the pages themselves were
untouched.

That is the payoff for a decision made in [Chapter 2](../02-domain/6-the-first-frontend.md): a
component that **reports what happened** rather than **deciding what it means** survives a change in
what it means. The same principle as keeping business logic out of controllers, on the other side of
the wire.

### One query-client default worth noticing

```tsx
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 0, networkMode: 'always' } },
});
```

`retry: 0` overrides TanStack Query's default of three attempts with backoff. For a console whose job
is to **show you what the backend is doing**, silently retrying a failed request is exactly wrong — a
service being down is information the page should display immediately, not smooth over.

`networkMode: 'always'` disables the offline-detection short-circuit, for the same reason: against
`localhost` or a cluster-internal address, the browser's online/offline heuristic is not a useful
signal.

**Defaults tuned to what the application is for**, rather than accepted because they are the defaults.

---

## The Architecture page

`docs/architecture-diagram.md` contains Mermaid diagrams. The Architecture page renders them in the
browser, so the diagram a reviewer sees is the diagram in the repository rather than a screenshot that
drifts.

`MermaidDiagram.tsx` is fifty lines that solve two real problems.

### Lazy loading

```tsx
// Loaded lazily (dynamic import) so the ~600KB mermaid bundle only loads when the Architecture page
// is actually visited, not as part of the main bundle every page pays for.
const mod = await import('mermaid');
```

Mermaid is larger than the rest of the application combined. A dynamic `import()` makes Vite emit it
as a separate chunk fetched on demand — so six of the seven pages never download it.

**Code splitting at the point of use**, triggered by a genuine cost rather than applied everywhere by
reflex.

### Serializing renders

```tsx
// mermaid.initialize()/mermaid.render() share global module state inside the mermaid package.
// When multiple MermaidDiagram instances mount at once (as happens here — the Architecture page
// renders several diagrams in one pass) their render() calls overlap, and one of them silently
// hangs forever (no resolve, no reject) instead of producing an SVG or an error.
let mermaidInitialized = false;
let renderQueue: Promise<unknown> = Promise.resolve();

function queueMermaidRender(id: string, source: string): Promise<{ svg: string }> {
  const task = renderQueue.then(async () => { /* initialize once, then render */ });
  // …
}
```

A third-party library with **global module state** that is not safe to call concurrently. Several
components mount together, several `render()` calls overlap, and one promise **never settles** — no
resolve, no reject, no error. A diagram that simply never appears.

The fix is a **promise chain as a queue**: each render appends to `renderQueue`, so calls serialize
across every instance regardless of when they mount. Plus module-level initialization exactly once.

Two things generalize:

**A promise that never settles is worse than one that rejects.** There is nothing to catch, nothing to
log, and no error boundary fires. The component just sits there.

**This is the same hazard as `SseEmitter#send`** from [section 2](2-server-sent-events.md) — a
resource that is not safe to use concurrently, being used concurrently — and the same shape of fix,
serializing at the right granularity. One in Java on the server, one in TypeScript in the browser.
Concurrency bugs do not care what language you are writing in.

Also worth noting: `securityLevel: 'strict'` when initializing. Mermaid renders arbitrary text into
SVG, and strict mode disables inline scripts and click handlers in diagram source.

---

## Chapter 5 in one paragraph

The fifth service exists because Phase 0 refused to put a `forcePaymentFailure` flag on the order API,
and it orchestrates the whole demonstration through public APIs and quarantined `/demo` controls
without owning a single row of business data. SSE replaces polling because the timeline is the
product and polling quantizes exactly the ordering it exists to show. An event projection in its own
consumer group observes all eight topics without the observed services knowing, and declines to
display anything it cannot honestly observe. And the console turns all of it into seven pages a
reviewer can use without reading a line of source — which was the exit criterion.

---

[← Observing the system](4-observing-the-system.md) · [Chapter 5 ↑](README.md) · [Chapter 6 — The transactional outbox →](../06-outbox/README.md)
