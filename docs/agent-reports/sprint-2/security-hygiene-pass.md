# Security & Repo Hygiene Pass

Sprint 2 goal 1. Covers dependency scanning, secret scanning, `/demo` isolation re-verification,
the LICENSE file, a README/link sanity check, and W7 of
`deployment-code-changes-briefing.md` (the committed Postgres password). Run against the repo as it
stood on 2026-08-20, tooling actually executed rather than inspected by eye.

## 1. Dependency vulnerability scan

**Java (Maven).** `mvn org.owasp:dependency-check-maven:13.0.0:check` was attempted first, per the
task's preference. It fails outright:

```
[ERROR] UpdateException: Error updating the NVD Data
[ERROR]   caused by NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key
[ERROR] NoDataException: No documents exist
```

The NVD API now requires a key to fetch the vulnerability feed at any usable speed, and none is
configured in this environment. No key was added (out of scope to provision one silently), so this
is a documented gap rather than a clean pass — flagging it explicitly: **if this project wants
real CVE-level scanning, an NVD API key (free, self-service at nvd.nist.gov) needs to be added as
a secret to whatever CI eventually runs this,** not baked into the repo.

Fell back to the task's stated alternative, `mvn versions:display-dependency-updates` and
`versions:display-parent-updates`, plus `mvn dependency:tree` to see actually-resolved versions
(the display-dependency-updates output is dominated by Spring Boot's full BOM — hundreds of
libraries this project doesn't use, like `graphql-java` and `mysql-connector-j` — so it isn't
useful signal on its own; cross-referencing against the dependency tree is what actually matters).

Findings, resolved versions as of this run:

| Dependency | Resolved version | Notes |
| --- | --- | --- |
| `spring-boot-starter-parent` | 4.1.0 | Latest **stable**; only newer option is `4.2.0-M1`, a milestone — not upgrading to a milestone. |
| `spring-framework` (transitive) | 7.0.8 | Current via the Spring Boot BOM. |
| `org.apache.kafka:kafka-clients` | 4.2.1 | Current. |
| `org.postgresql:postgresql` | 42.7.11 | Well past 42.7.2, the fix version for CVE-2024-1597 (PgJDBC SQL injection via error-response parsing). Not affected. |
| `ch.qos.logback:logback-classic`/`-core` | 1.5.34 | Well past 1.4.14/1.3.14, the fix versions for CVE-2023-6378 (logback receiver deserialization). Not affected. |
| `org.yaml:snakeyaml` | 2.6 | Current major; the old SnakeYAML deserialization CVEs (CVE-2022-1471 and friends) were fixed well before 2.x. Not affected. |
| `com.fasterxml.jackson.core:*` | 2.21.4 | Current 2.x line. |
| `wiremock-standalone` (scenario-service, test scope only) | 3.9.2 | Test-only, not shipped; not a production exposure regardless of version. |
| Maven plugins (all modules) | — | `versions:display-plugin-updates` reports "All plugins with a version specified are using the latest versions" across every module. |

No dependency bump was made — everything is already at the latest stable version or, where a
newer version exists, it's a pre-release milestone (`4.2.0-M1`) that would be a breaking-change risk
for zero security benefit, so it was left alone and is noted here rather than silently taken.

**Frontend (npm).** `npm audit` in `frontend/`:

```
found 0 vulnerabilities
```

`npm outdated` shows six packages with newer minor/major versions available (`@types/node`,
`@vitejs/plugin-react`, `mermaid`, `oxlint`, `typescript`, `vite`), none of them CVE-driven — audit
is clean, these are just routine version lag. Per the task's instruction to fix only genuine
known-CVE bumps and flag anything riskier, none of these were touched: `typescript` 6.0.3 → 7.0.2
is a major-version bump with real potential for breaking changes, and the rest are minor/patch bumps
with no security motivation to justify the churn right now. Flagging `typescript` 7.x as worth a
deliberate, tested upgrade in a future sprint rather than folding into this pass.

## 2. Secret scanning

`gitleaks` was not present on the machine; installed via `brew install gitleaks` (v8.30.1) rather
than falling back to manual-only scanning, since the task allows either.

Ran two passes:

```bash
gitleaks detect --source . --no-git -v   # working tree
gitleaks detect --source . -v            # full git history (16 commits)
```

Both passes report the same 3 findings, all false positives — example UUIDs used as sample
`idempotencyKey` values in `docs/openapi/payment-service.yaml` (lines 70, 80) and
`docs/events/event-catalog.md` (line 253), flagged only because gitleaks' `generic-api-key` rule
scores them as high-entropy strings. No real secret, in any commit across the repo's full history.

Followed up with a manual pattern pass (AWS key prefixes, PEM/SSH private key headers, Slack
tokens, and a broader `password|secret|api[_-]?key|token\s*[:=]` grep across YAML/properties/env/Java
files) as a second method, independent of gitleaks' rule set: no additional matches beyond the
known, expected `POSTGRES_PASSWORD: orderfulfillment` in `01-secrets.yaml` (that's W7, addressed
below, not a new finding).

Checked for tracked `.env` files: only `frontend/.env.example` is tracked (as intended — it's a
template with no real values). `frontend/.gitignore` already has `*.local`, which covers Vite's
`.env.local` convention, so no real env file can be accidentally committed from that directory. No
`.gitignore` change was needed.

**Conclusion: no real secrets are committed anywhere in this repo's history**, beyond the
already-known, already-scoped-for-W7 Postgres dev password.

## 3. `/demo` endpoint isolation re-verification

Grepped every `@RequestMapping`/`@GetMapping`/`@PostMapping`/`@PutMapping`/`@DeleteMapping`/
`@PatchMapping` across all five services' controllers. Full inventory:

| Service | Controller | Prefix |
| --- | --- | --- |
| order | `OrderController` | `/api/orders` |
| inventory | `InventoryController` | `/api/inventory` |
| inventory | `DemoConsumerController` | `/demo/consumers` |
| payment | `PaymentController` | `/api/payments` |
| payment | `PaymentDemoController` | `/demo/payment-behavior` |
| fulfillment | `FulfillmentController` | `/api/shipments` |
| fulfillment | `DemoConsumerController` | `/demo/consumers` |
| scenario | `ScenarioController` | `/demo/scenarios` |
| scenario | `ScenarioRunController` | `/demo/scenario-runs` |
| scenario | `EventController` | `/demo/events` |
| scenario | `DemoAdminController` | `/demo/reset` |

Every controller is fully inside one namespace — no controller mixes `/api` and `/demo` mappings,
no `/demo` route lives under `/api`, and no `/api` route lives under `/demo`. ADR-002 holds as
implemented, not just as documented, across all five services.

**Actuator exposure**, checked in each service's `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
```

Identical across all five services. This is wider than health-only, but it's the correct exposure
for the environments it currently runs in (local dev, plus Prometheus scraping `/actuator/prometheus`
every 5s per the observability stack in `docker-compose.yml`) — narrowing it to `health` only would
break metrics scraping that's actually in use today. This is the same finding
`deployment-code-changes-briefing.md`'s W1 already made and already has a plan for: W1's ingress
allowlist explicitly excludes `/actuator/metrics` and `/actuator/prometheus` from what's publicly
routable, enforcing the boundary at the network/ingress layer rather than by changing the
application-level exposure. Confirming here that this repo's current `application.yml` state matches
what W1 assumes (metrics/prometheus are exposed at the app level, health is what W1 actually routes
publicly) — no code change made in this pass, since the fix belongs to W1's ingress work, not here.

## 4. LICENSE file

None existed. Added `LICENSE` (MIT, copyright 2026 Noel Schneider) at the repo root — the expected
default for a portfolio project with no commercial constraints, per the task's instruction. No
reason to deviate from MIT was found.

## 5. README/badge/link sanity check

`README.md` has **no badges and no Markdown links** (`[text](url)` syntax) anywhere in the file —
grepped for both and found zero matches. All cross-references are inline code spans naming
filenames (e.g. `` `docs/planning/agent-guidance.md` ``, `` `ADR-002` ``), not clickable links, so
there was nothing that could "404" in the usual sense.

Checked every `docs/...` path mentioned in the README against the filesystem regardless:

- `docs/planning/project-overview.md`, `docs/planning/agent-guidance.md`,
  `docs/architecture-diagram.md`, `docs/adr/ADR-007-kubernetes-only-after-local-boundaries-stabilize.md`,
  `docs/adr/ADR-008-native-structured-logging.md` — all exist.
- `docs/agent-reports/phase-7-containerization.md` and `docs/agent-reports/phase-8-kubernetes.md` —
  referenced with an explicit in-README caveat that they're the author's local, gitignored working
  notes and won't be present in a fresh clone. Confirmed both are in fact gitignored
  (`git check-ignore` returns both paths) and that the caveat text is accurate, so no fix needed —
  the README already tells the reader not to expect them.

No sprint-reorg breakage found: the README's references to `docs/planning/agent-guidance.md` and
`docs/planning/project-overview.md` are to the top-level cross-sprint files, which didn't move
during the sprint-1/sprint-2 split. Nothing in the README pointed at a sprint-scoped path that
would have broken.

**No changes were needed for this item.**

## W7 — Postgres password no longer committed for production

`infrastructure/kubernetes/01-secrets.yaml` is left **exactly as it was** — the committed
`orderfulfillment/orderfulfillment` dev credentials still work unchanged for local `kind` and
Compose flows, per CLAUDE.md rule 14 and the task's explicit instruction.

Checked `infrastructure/kubernetes/production/` before creating anything — it did not exist yet
(the parallel deployment code-changes work, W1, hadn't landed there at the time of this pass).
Created it with just the W7 piece:

- `infrastructure/kubernetes/production/README.md` — explains the overlay directory's purpose
  (production-only, additive, doesn't touch the base manifests), documents the deploy-time secret
  command, and gives the production apply sequence (apply namespace, generate the secret, apply
  every base manifest except `01-secrets.yaml`).
- `infrastructure/kubernetes/production/create-postgres-secret.sh` — generates
  `postgres-credentials` with `openssl rand -base64 24` for `POSTGRES_PASSWORD`, applied via
  `kubectl create secret generic ... --dry-run=client -o yaml | kubectl apply -f -` (idempotent,
  refuses to overwrite unless `--rotate` is passed). Verified with `bash -n` (syntax only — not
  run against a live cluster, since none exists yet for this task).

This works without touching any Deployment: every service's `secretKeyRef` already references the
`postgres-credentials` Secret by name and key (`POSTGRES_USER`, `POSTGRES_PASSWORD`,
`POSTGRES_DB`), not by inline value, so swapping where that Secret object comes from (committed
YAML locally, generated command in production) requires zero changes to `02-postgres.yaml` or any
of the five backend service manifests.

**Coordination note for whoever picks up W1 next:** this `production/` directory now exists with
one file in it. W1's ingress/`ClusterIP` work should add its manifests alongside
`create-postgres-secret.sh` and extend the README's apply-sequence section rather than overwrite
it.

## Summary of changes made

- Added `LICENSE` (MIT, Noel Schneider, 2026) at repo root.
- Added `infrastructure/kubernetes/production/README.md` and
  `infrastructure/kubernetes/production/create-postgres-secret.sh` for W7.
- No dependency bumps (nothing outdated was CVE-driven; Java deps are already at latest
  stable, npm audit is clean).
- No secret-scanning remediation needed beyond what W7 already covers.
- No `/demo` isolation code change needed — verified compliant as implemented.
- No README changes needed — no broken links or badges found.

## Flagged, not fixed

- **No NVD API key configured**, so `dependency-check-maven` cannot run a real CVE-level scan in
  this environment. Recommend provisioning a free NVD API key and wiring it into whatever CI
  eventually runs this project, rather than adding one to this repo now.
- **`typescript` 6.0.3 → 7.0.2** is available in the frontend but is a major version bump with real
  breaking-change surface; left alone deliberately rather than folded into a hygiene pass. Worth a
  dedicated, tested upgrade in a future sprint.
- **Actuator `metrics`/`prometheus` exposure** is intentionally left at the application level
  (needed for local Prometheus scraping) and relies on W1's ingress allowlist to keep it from being
  publicly reachable in production. This pass confirms the current `application.yml` state is
  consistent with what W1 assumes; the actual production-facing fix is W1's, not this pass's, to
  avoid duplicating that work.
