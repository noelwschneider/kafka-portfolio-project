# Deployment Readiness — Code Changes W2–W5

Covers Sprint 2 goal 4's W2 (frontend production build), W3 (SPA fallback), W4 (idle auto-reset),
and W5 (configurable CORS) from `docs/agent-reports/sprint-2/deployment-code-changes-briefing.md`.
W1, the architecture-specific part of W6, and W8 are out of scope for this pass (on hold pending the
platform decision); W7 was already done separately.

## W2 — Frontend production build against relative base URLs

**What changed:**

- `frontend/Dockerfile` now declares five `ARG`s (`VITE_ORDER_SERVICE_URL`,
  `VITE_INVENTORY_SERVICE_URL`, `VITE_PAYMENT_SERVICE_URL`, `VITE_FULFILLMENT_SERVICE_URL`,
  `VITE_SCENARIO_SERVICE_URL`), defaulted to the existing `http://localhost:808X` values, exported as
  `ENV` before `npm run build`. Vite's `loadEnv` gives `process.env` values highest priority over any
  `.env` file, so this works without touching `.env.production` or `vite.config.ts`.
- A plain `docker build` (no `--build-arg`) — what `README.md`'s kind instructions and the local
  workflow already do — is unchanged: same defaults baked in as before.
- A production build passes each ARG as a relative prefix, e.g.
  `docker build --build-arg VITE_ORDER_SERVICE_URL=/svc/order ...` for all five, per the W1 path
  table. `apiFetch` and both `EventSource` calls in `src/api/client.ts` concatenate
  `${baseUrl}${path}` unchanged either way — no other frontend code needed to change.
- `frontend/.env.example` now documents both shapes (local default active, production example
  commented out) instead of only the local one.
- `frontend/Dockerfile`'s header comment, which previously claimed `localhost:808X` "stays correct"
  unconditionally (true for Compose, false for a real deployed build — a rule-14 violation left as-is
  would have been misleading), now explains both build paths and why each is correct for its context.

**How verified (not just claimed):**

- Built the image with no build args, extracted the built JS from the container, and grepped it:
  confirmed `localhost:8081`–`localhost:8085` are all present in the bundle, matching today's local
  Compose/kind behavior exactly.
- Rebuilt the image with all five `--build-arg`s set to `/svc/order`, `/svc/inventory`,
  `/svc/payment`, `/svc/fulfillment`, `/svc/scenario`, extracted the bundle again: confirmed zero
  `localhost:808X` occurrences and all five `/svc/*` paths present instead.
- Both test images were built and inspected with real `docker build` / `docker cp` / `grep` — not
  inferred from source reading — then removed afterward (`docker rmi`).

## W3 — SPA fallback for the frontend container

**What changed:**

- Added `frontend/nginx.conf`, replacing the stock `nginx:alpine` default server block via
  `COPY nginx.conf /etc/nginx/conf.d/default.conf` in the Dockerfile (stage 2). It adds
  `try_files $uri $uri/ /index.html;` for the SPA fallback, `gzip` for text/JS/CSS/SVG, a 1-year
  immutable cache header for `/assets/` (Vite content-hashes those filenames, so this is safe), and
  `Cache-Control: no-cache` on `index.html` itself so a redeploy is always picked up.

**How verified (not just claimed):**

- Built the production-arg image from W2, ran it with `docker run -d -p 18080:80 ...`, and issued
  real `curl` requests against the running container:
  - `GET /orders/abc-123` → `200`, body is the real `index.html` (`<title>Order Fulfillment Systems
    Lab</title>` present) — a deep link now works instead of nginx's stock 404.
  - `GET /scenario-runs/xyz` → `200`, same fallback.
  - `GET /` → `200` as expected.
  - `GET /assets/does-not-exist.js` → `404` — confirms the fallback didn't silently swallow real
    404s for missing assets.
  - Checked response headers: the served hashed JS asset carries
    `Cache-Control: public, immutable, max-age=31536000`; `index.html` carries
    `Cache-Control: no-cache`.
- Container removed after verification (`docker rm -f`).

## W4 — Idle auto-reset in Scenario Service

**What changed:**

- `services/scenario-service/src/main/java/com/orderfulfillment/scenario/config/IdleResetProperties.java`
  (new): `@ConfigurationProperties(prefix = "orderfulfillment.idle-reset")` record with `enabled`,
  `idlePeriodMs`, `checkIntervalMs` — same ms-suffixed-long convention as the existing
  `ScenarioProperties`, rather than `java.time.Duration`, to keep `@Scheduled`'s
  `fixedDelayString` binding simple (plain property placeholder, no SpEL bean lookup).
- `services/scenario-service/src/main/java/com/orderfulfillment/scenario/admin/IdleResetScheduler.java`
  (new): `@Component`, gated by `@ConditionalOnProperty(prefix = "orderfulfillment.idle-reset", name =
  "enabled", havingValue = "true")` so the bean doesn't even exist unless explicitly turned on.
  `@Scheduled(fixedDelayString = "${orderfulfillment.idle-reset.check-interval-ms:60000}")` runs
  `checkIdleAndReset()`, which:
  1. Returns immediately if `RunRegistry.anyRunning()` or
     `ScenarioRunRepository.existsByStatus(RUNNING)` — the same two checks `DemoResetService.reset()`
     itself makes.
  2. Computes idle time from the most recent `scenario_runs` row's `startedAt`/`completedAt`
     (whichever is later), falling back to the scheduler bean's own construction time if no run has
     ever happened, so a freshly booted instance doesn't fire immediately.
  3. Calls `demoResetService.reset()` unchanged — no reimplementation of restore/resume/clear logic.
  4. Catches `ConflictException` (the 409 path) as a silent no-op, per the requirement — not logged
     as an error, since it's an expected benign race with a real run starting between the guard check
     and the call.
  - Class Javadoc documents the single-replica assumption on `RunRegistry` explicitly, per the
    briefing's instruction to leave a comment rather than redesign around it.
- `ScenarioServiceApplication.java`: added `@EnableScheduling` (harmless when no `@Scheduled` bean
  exists, i.e. every non-production profile today).
- `application.yml`: new `orderfulfillment.idle-reset` block, `enabled: false` by default,
  `idle-period-ms: 900000` (15 minutes, the sprint-2 decision), `check-interval-ms: 60000`.
- `application-production.yml` (new): the only profile that sets `orderfulfillment.idle-reset.enabled:
  true`. Its header comment flags that wiring `SPRING_PROFILES_ACTIVE=production` into the actual
  Deployment manifest is still pending, deferred to the W1/W6/W8 deployment work.

**Test added:** `services/scenario-service/src/test/java/com/orderfulfillment/scenario/IdleResetSchedulerIntegrationTest.java`.
Runs against a real Spring context (Testcontainers Postgres + Kafka, WireMock stand-ins for the four
downstream services — same pattern as the existing `DemoResetIntegrationTest`), with a short
idle-period/check-interval injected via `@DynamicPropertySource` so the test doesn't wait 15 real
minutes. It simulates "a scenario is RUNNING" directly through `RunRegistry.tryStart()` (the same
in-memory guard the scheduler itself consults) rather than driving a full scenario end-to-end, then:

1. Asserts no reset call reaches the stubbed Inventory/Payment endpoints while the fake run is
   claimed, even though real wall-clock time well past the idle period elapses.
2. Calls `RunRegistry.finish()`, then asserts (via Awaitility) that the paused consumer is resumed
   and the payment override is cleared shortly after.

**How verified (not just claimed):** ran the real Maven/Testcontainers suite, not just compiled it.

- `mvn -pl services/scenario-service test -Dtest=IdleResetSchedulerIntegrationTest,DemoResetIntegrationTest`
  → both green (3 tests, 0 failures).
- Ran the full scenario-service suite (`mvn -pl services/scenario-service test`): 14 of 15 test
  classes green including the new one; `InventoryContentionScenarioIntegrationTest` timed out. Verified
  this is a **pre-existing flake unrelated to this change** by `git stash`-ing all of this session's
  changes and re-running that one test class against the untouched codebase — it failed identically
  (same `ConditionTimeout` at the same line, ~20–30s). Not something this work introduced or should
  block on.
- One race was found and fixed during development of the test itself: an early scheduler tick could
  fire during Spring context startup (before the test method's `RunRegistry.tryStart()` call and
  before WireMock stubs existed), landing a stray partial request. Fixed by claiming the registry slot
  *before* any stub is registered and by clearing WireMock's request journal
  (`resetRequests()`) right before the "no effect while RUNNING" assertion window, plus giving the
  idle period (1200ms) enough margin over typical context-startup time. Documented in the test's
  comments so a future reader doesn't reintroduce the race.

## W5 — Configurable CORS origins

**What changed:**

- `services/common/src/main/java/com/orderfulfillment/common/WebConfig.java`: now takes
  `allowedOriginPatterns` via constructor injection,
  `@Value("${app.cors.allowed-origin-patterns:http://localhost:*}")`, split on `,` and passed as
  varargs to `CorsRegistration#allowedOriginPatterns`. Default unchanged from before.
- All five services' `application.yml` (`order`, `inventory`, `payment`, `fulfillment`, `scenario`)
  gained an `app.cors.allowed-origin-patterns` property,
  `${APP_CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:*}` — one env var overrides every service
  uniformly. Each service's existing `management.endpoints.web.cors.allowed-origin-patterns` (the
  actuator-specific CORS block, needed because actuator endpoints bypass `WebConfig`'s regular
  `WebMvcConfigurer` mapping) now reads `"${app.cors.allowed-origin-patterns}"` instead of repeating
  the literal — one property drives both, per the briefing.
- No `*` default anywhere, and nothing here combines a wildcard with credentials (`WebConfig` never
  set `allowCredentials(true)` before or after this change).

**How verified:** `mvn compile` across the whole reactor succeeds (`common` module change propagates
cleanly to all five services). Ran `order-service`'s full test suite (27 tests) against the new
property-driven `WebConfig` and actuator CORS config — all green, confirming the app context starts
correctly with the new `@Value`-injected constructor and the cross-referenced YAML property resolves
(Spring resolves `${app.cors.allowed-origin-patterns}` against the value already defined earlier in
the same file without issue).

## Things that need a human decision or follow-up

- **`SPRING_PROFILES_ACTIVE=production` is not yet wired into any Kubernetes manifest.** W4's
  `application-production.yml` exists and is correct, but nothing sets that env var yet — that's
  explicitly part of the still-pending W1/W6/W8 deployment work
  (`infrastructure/kubernetes/production/` already exists for W7's Postgres-secret fix; this would be
  an addition to `08-scenario-service.yaml`'s ConfigMap or a production-overlay patch, not something
  this task should do since it depends on the platform decision).
- **`InventoryContentionScenarioIntegrationTest` is flaky independent of this work** — confirmed via
  `git stash` reproduction against the unmodified codebase. Worth investigating separately (likely a
  timing assumption under contention that's sensitive to machine load), but out of scope here since it
  predates and is unrelated to W2–W5.
- No other blockers found. Local Compose/kind flows were not run end-to-end as live clusters in this
  session (would require standing up Postgres/Kafka/kind), but the specific things this work touches
  were verified directly: the default Docker build still bakes in `localhost:808X` (byte-inspected),
  and the full order-service and (all-but-one) scenario-service test suites pass against the changed
  `application.yml` files.
