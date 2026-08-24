# Deployment Readiness — Code Changes Briefing

Standalone context for the agent doing Sprint 2's code work. Written for a session with no access to
the conversation that produced it.

This is the output of the Sprint 2 deployment spike
(`docs/planning/sprint-2/deployment-agent-briefing.md`). The platform decision is made; what remains
before the application can be deployed is a set of repo changes, described below. Read
`.claude/CLAUDE.md` first for repo norms — in particular rule 9 (`/demo` isolation), rule 14 (both
supported ways to run the system must stay accurate), and rule 18 (don't overstate guarantees).

Companion documents, if more background is needed:

- `deployment-platform-options.md` — platform comparison, pricing, and the settled decisions.
- `dev-vs-demo-host-separation.md` — why the demo runs on its own server.

## Sequencing

These changes are wanted **before** the deployment itself happens. The order of operations for the
sprint is: security pass → these code changes → provision and deploy. The deployment work is a
separate task and is not described here beyond what constrains the code.

Nothing here requires the server to exist. All of it can be written and verified against the existing
local `kind` flow.

## Decisions these changes are built on

- **Platform:** one Hetzner CX33 (4 vCPU / 8 GB, ~€9/month) running **k3s**, applying the existing
  `infrastructure/kubernetes/` manifests. Not a managed Kubernetes service, not a PaaS.
- **Always on**, reached through a **custom domain** with HTTPS.
- **Deployment is manual** (`kubectl apply`) for now. No CI/CD deployment pipeline in this sprint.
- **The demo box is a separate server from the Sprint 2 dev VPS.** The dev box exists to run chaos,
  crash loops and multi-replica load tests; the demo box exists to be up and boring for a stranger
  clicking a link. They are not the same machine and should not be conflated when writing docs or
  scripts.
- **`/demo` stays publicly reachable**, because the scenarios *are* the product. The protection comes
  from routing (below), not from authentication.
- **The local `kind` and Docker Compose flows must keep working exactly as they do today.** Everything
  below is additive or environment-driven; nothing may make a public URL a prerequisite for local
  development.

## The finding that drives most of this: the browser's real surface is small

The frontend talks to five services on five origins, baked in at build time
(`frontend/src/api/client.ts:6`). Enumerating every call the browser actually makes:

| Service | Paths the browser calls |
| --- | --- |
| Order | `/api/orders` (GET, POST), `/api/orders/{id}`, `/api/orders/stream` (SSE), `/actuator/health` |
| Inventory | `/api/inventory`, `/actuator/health` |
| Payment | `/actuator/health` only |
| Fulfillment | `/actuator/health` only |
| Scenario | `/demo/scenarios`, `/demo/scenarios/{name}` (POST), `/demo/scenario-runs`, `/demo/scenario-runs/{id}`, `/demo/scenario-runs/{id}/stream` (SSE), `/demo/reset`, `/demo/events`, `/actuator/health` |

Two consequences worth stating plainly, because they shape the rest of this document:

1. **The two endpoints that can wedge the demo are never called by the browser.**
   `POST /demo/consumers/{name}/pause` (Inventory and Fulfillment) and `PUT /demo/payment-behavior`
   (Payment) are invoked *server-side* by Scenario Service over cluster-internal DNS
   (`services/scenario-service/src/main/resources/application.yml:33`). Both leave the system
   wedged indefinitely with no auto-recovery if called directly. Since nothing in the browser needs
   them, **the ingress simply must not route them** — which removes the risk structurally, with no
   application code change and no loss of demo functionality.
2. **If every request goes through one origin, CORS stops applying at all.** Same-origin requests
   never consult CORS headers, which turns the hardcoded-localhost problem from a blocker into
   optional cleanup.

## W1 — Ingress with per-service path prefixes and an allowlist

**Why:** there is no Ingress manifest today; every Service is `NodePort` (30081–30085, 30173) and
`infrastructure/kind-config.yaml` maps those to host ports so the frontend's baked-in
`localhost:808X` URLs work unchanged. That is a good local design and should stay. It is not a
public-internet design.

**What:** add a production ingress that puts everything behind one hostname:

| Public path | Backend | Notes |
| --- | --- | --- |
| `/` | frontend | SPA |
| `/svc/order/api/orders` | order-service | prefix match also covers `/{id}` and `/stream` |
| `/svc/order/actuator/health` | order-service | |
| `/svc/inventory/api/inventory` | inventory-service | |
| `/svc/inventory/actuator/health` | inventory-service | |
| `/svc/payment/actuator/health` | payment-service | |
| `/svc/fulfillment/actuator/health` | fulfillment-service | |
| `/svc/scenario/demo` | scenario-service | covers scenarios, runs, reset, events — its whole `/demo` surface is the demo |
| `/svc/scenario/actuator/health` | scenario-service | |

Anything not listed is unrouted and returns 404 from the ingress. That deliberately excludes
`/demo/consumers/*`, `/demo/payment-behavior`, `/actuator/metrics`, and `/actuator/prometheus`
(the last two are exposed by `management.endpoints.web.exposure.include` and should not be public).

**Implementation notes:**

- k3s ships Traefik. The straightforward approach is one Ingress with these paths plus a
  **StripPrefix** middleware removing `/svc/{name}`, so services keep serving their existing paths and
  the probe paths in the Deployments are unaffected. Verify the Traefik CRD `apiVersion` that ships
  with the installed k3s version rather than assuming.
- If the middleware proves awkward, the fallback is an env-driven
  `server.servlet.context-path` per service (empty by default, `/svc/{name}` in production). It needs
  no Traefik CRDs but *does* move the actuator probe paths, so the Deployment probe paths would need
  to move with it. Pick one, don't half-do both.
- **Keep the base manifests as they are.** Put the production-only pieces in a separate location
  (e.g. `infrastructure/kubernetes/production/`) so `kubectl apply -f infrastructure/kubernetes/`
  against local `kind` keeps behaving identically. Plain YAML preferred over Helm, consistent with
  ADR-007's reasoning.
- **Public NodePorts must not remain reachable**, or they bypass the allowlist entirely. Two
  independent defenses, and both should exist: the production overlay sets the six Services to
  `ClusterIP`, **and** the box's firewall blocks 30000–32767. The firewall half belongs to the VPS
  task — flag it there, don't assume it.

**Acceptance:** with the production overlay applied to a local `kind` cluster, the allowlisted paths
work through the ingress, `/svc/inventory/demo/consumers/{name}/pause` returns 404, and applying the
base manifests alone still produces today's NodePort behaviour.

## W2 — Frontend production build against relative base URLs

**Why:** `frontend/src/api/client.ts:6-10` reads `VITE_*_SERVICE_URL`, defaulting to
`http://localhost:808X`, and Vite bakes these in at build time. A public deployment needs different
values, and `frontend/Dockerfile` currently accepts none.

**What:**

- Set the five base URLs to the relative prefixes from W1 (`/svc/order`, `/svc/inventory`,
  `/svc/payment`, `/svc/fulfillment`, `/svc/scenario`) for the production build — via
  `.env.production` or Docker build args, whichever is cleaner. `apiFetch` concatenates
  `${baseUrl}${path}`, so relative prefixes work unchanged, as do both `EventSource` URLs.
- Update `frontend/.env.example` and, importantly, **the comment block at the top of
  `frontend/Dockerfile`**, which currently explains that `localhost:808X` "stays correct" because
  Compose publishes ports to the host. That explanation is true for Compose and false for the
  deployed build; leaving it would violate rule 14.

**Acceptance:** the local Compose and `kind` flows still serve a frontend that talks to
`localhost:808X`; a production-built image talks to same-origin `/svc/*` paths.

## W3 — SPA fallback for the frontend container

**Why:** `frontend/src/App.tsx:62` uses `BrowserRouter`, and `frontend/Dockerfile` serves `dist/` from
a stock `nginx:alpine` with no configuration. There is no `try_files` fallback anywhere in the repo,
and no nginx ConfigMap in `infrastructure/kubernetes/09-frontend.yaml`. Any deep link or page refresh
on a client-side route (`/orders/{id}`, `/scenario-runs/{id}`) returns nginx's 404.

This is invisible locally because the Vite dev server does its own fallback. It becomes visible the
moment a recruiter refreshes the page or opens a link someone sent them — which is precisely the
scenario the deployment exists for.

**What:** add an nginx config to the frontend image with
`try_files $uri $uri/ /index.html;`. Consider `gzip` and cache headers for hashed assets while there.

**Acceptance:** `docker run` the built image, request a deep route directly, get the SPA rather than a
404.

## W4 — Idle auto-reset in Scenario Service

**Why:** the wedge endpoints are unreachable after W1, but the system can still end up in a stuck
state legitimately: `consumer-outage` pauses the Inventory listener and resumes it after 4 s
(`consumer-outage-pause-ms`), and `payment-failure` sets the Payment override and clears it at the
end. If Scenario Service dies mid-run, or a run fails between those two steps, the paused consumer or
the `REJECT` override persists with nothing to clear it. The next visitor sees a broken demo.

**What:** a scheduled job in `scenario-service` that, when no run is `RUNNING` and there has been no
scenario activity for a configurable idle period (15 minutes is a sensible default), invokes the
existing reset path. `DemoResetService.reset()` already does exactly the right work — restores seed
inventory, resumes every paused consumer, clears the payment override — and already refuses to run
while a scenario is in progress
(`services/scenario-service/src/main/java/com/orderfulfillment/scenario/admin/DemoResetService.java:68`).
Reuse it; do not reimplement it.

Requirements:

- Config-driven and **off by default**, enabled in the production configuration only. A reset firing
  during local development would be actively confusing.
- Check `RunRegistry.anyRunning()` and the repository's `RUNNING` status before acting, and let the
  existing `ConflictException` path be a no-op rather than an error log.
- Do not delete scenario run history. `DemoResetService`'s class comment documents why history is
  retained deliberately; that decision stands.
- Note in passing: `RunRegistry` is per-JVM in-memory, so this reasoning assumes Scenario Service runs
  a single replica, which the manifests do today. If that ever changes, the guard needs revisiting —
  worth a comment, not a redesign now.

**Acceptance:** an integration test that pauses a consumer, advances past the idle threshold, and
asserts the consumer is resumed and the payment override cleared, with no effect while a run is
`RUNNING`.

## W5 — Make CORS origins configurable (recommended, not blocking)

**Why:** `services/common/src/main/java/com/orderfulfillment/common/WebConfig.java:14` hardcodes
`allowedOriginPatterns("http://localhost:*")`, and each service repeats the same value for the
actuator endpoints (`management.endpoints.web.cors.allowed-origin-patterns`, five `application.yml`
files). After W1 and W2 every request is same-origin, so CORS is never consulted in production and
this does not block the deployment. It is still a latent foot-gun, the security pass will flag it, and
it blocks any future setup where the frontend is served from a different host.

**What:** drive both from one property (e.g. `app.cors.allowed-origin-patterns`) defaulting to
`http://localhost:*`, overridable by environment variable. Do **not** use `*` in the production
value, and do not combine a wildcard with credentials.

## W6 — Container images the box can actually run

**Why:** there is no `.github/workflows/` directory in the repo, so nothing builds or publishes
images today; the local flow builds them into the Docker daemon and `kind load`s them. Two specifics
will otherwise bite during deployment:

- **Architecture.** Images built on the M1 laptop are `arm64`. The CX33 is `x86_64`. Images must be
  built for `linux/amd64` (`docker buildx --platform linux/amd64`) or built on the box itself.
- **k3s uses containerd, not Docker.** A `docker build` on the box does not make an image visible to
  k3s. Either import it (`docker save … | k3s ctr images import -`) or pull it from a registry.

**What:** document and script one path. The recommendation is **GHCR public packages** — free for
public repos, no extra account, pulled by k3s with no credentials, and it makes the eventual CI step
a two-line change. Manual `docker push` from the laptop is fine for now; CI/CD is explicitly deferred.

Keep this to a documented command sequence or a small script — a full release pipeline is out of
scope for this sprint.

## W7 — Stop shipping the Postgres password in the repo

**Why:** `infrastructure/kubernetes/01-secrets.yaml` contains `orderfulfillment/orderfulfillment` in
`stringData`, with a comment noting these dev credentials aren't sensitive. That was true for a local
cluster. On a public box the reasoning changes, even though Postgres itself stays `ClusterIP` and is
not internet-reachable.

**What:** the production overlay should take a generated password supplied at apply time rather than
the committed value. This overlaps the security task — coordinate rather than doing it twice, and
leave the committed local-dev Secret alone so the local flows keep working unchanged.

## W8 — Docs to update alongside the code

Rule 14 requires the run instructions to stay accurate, and there will now be a third way to run this
(local Compose, local `kind`, deployed). Update as part of the same work:

- `README.md` — how to reach the deployed demo, and one honest line that it is a shared public
  sandbox: anyone can run scenarios, and state may be reset while you're looking at it.
- Whatever documents the `kind` flow, so the production overlay's existence and purpose is stated.
- `docs/architecture-diagram.md` only if the routing model changes something a reader would
  otherwise get wrong.

Do **not** edit anything under `docs/planning/sprint-1/` — it is frozen. If one of those documents is
actually wrong, flag it in your output instead.

## Explicitly not in scope

- **Provisioning the server, SSH, firewall, k3s install, TLS certificates, DNS.** That is the
  deployment task, and the firewall/NodePort dependency in W1 is the only thing that must be
  communicated across the boundary.
- **CI/CD deployment.** Deferred by decision.
- **Authentication on `/demo`.** Rejected deliberately: protecting the scenarios from recruiters
  defeats the purpose of the demo. `docs/planning/sprint-1/high-level-design.md` §21 and ADR-002 both
  anticipated a "simple demo configuration" for exactly this, and W1's routing allowlist is the
  version of that which costs nothing in demo value.
- **Rate limiting.** Worth doing at the Cloudflare edge at deploy time, not in application code.
- **Prometheus and Grafana.** They exist in `docker-compose.yml` only, not in the Kubernetes
  manifests. Leave that as-is; a public Grafana is not part of this deployment.
- **The HorizontalPodAutoscaler.** Separate Sprint 2 goal, on the dev box.

## Verification checklist for whoever deploys

Not work items for this task — the list the deployment session should run through, recorded here so
it isn't rediscovered:

- Both SSE streams (`/api/orders/stream`, `/demo/scenario-runs/{id}/stream`) survive the ingress:
  events arrive promptly (no proxy buffering) and connections outlive the default idle timeouts.
- A deep link opened cold returns the SPA (W3).
- `/svc/inventory/demo/consumers/…`, `/svc/payment/demo/payment-behavior`, and
  `/svc/order/actuator/prometheus` all 404 from outside.
- NodePorts are not reachable from the public internet.
- All eight scenarios run green end-to-end on the deployed instance, not just locally.
- Disk usage baseline recorded. Scenario runs, timeline entries and the event projection are retained
  on purpose and never pruned; on an 80 GB disk that is a slow burn worth knowing the rate of.

## Open questions for the user

- **Domain name and registrar.** Needed before TLS and before the frontend production build's base
  URLs are final if a hostname ever gets baked in (with relative `/svc/*` paths it does not, which is
  another reason to prefer them).
- **Idle auto-reset threshold.** 15 minutes is the suggested default; it is a one-line config change
  either way.
