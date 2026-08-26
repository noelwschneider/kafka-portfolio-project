# Issue #35 — CI test workflow, independent verification

Verifies the deliverable reported in `docs/agent-reports/sprint-5/issue-35-ci-test-workflow.md`
against the actual artifact (`.github/workflows/ci.yml`) rather than the report's claims.

## What changed

No source files changed. This is a verification pass only. The only file added is this report,
`docs/agent-reports/sprint-5/issue-35-verification.md`.

## How this was verified

### 1. Path filters match the real directory structure — PASS

```
$ ls -d services/order-service services/inventory-service services/payment-service \
      services/fulfillment-service services/scenario-service services/common frontend
frontend
services/common
services/fulfillment-service
services/inventory-service
services/order-service
services/payment-service
services/scenario-service
```

All seven paths referenced in `ci.yml`'s `dorny/paths-filter` block (`services/order-service/**`,
`services/inventory-service/**`, `services/payment-service/**`, `services/fulfillment-service/**`,
`services/scenario-service/**`, `services/common/**` + root `pom.xml`, `frontend/**`) exist exactly as
named. No typos, no stale paths.

### 2. Each backend job's `if:` really gates on (own-service OR common) — PASS

Re-derived independently from each service's actual `pom.xml`, not from the report's grep output:

```
$ for s in order-service inventory-service payment-service fulfillment-service scenario-service; do
    echo "== $s =="
    grep -B2 -A2 "<artifactId>common</artifactId>" services/$s/pom.xml
  done
== order-service ==
        <dependency>
            <groupId>com.orderfulfillment</groupId>
            <artifactId>common</artifactId>
            <version>${project.version}</version>
        </dependency>
== inventory-service ==   (same shape)
== payment-service ==     (same shape)
== fulfillment-service == (same shape)
== scenario-service ==    (same shape)
```

All five declare a direct `<dependency>` on `services/common`. Cross-checked each job's `if:` line in
`.github/workflows/ci.yml` against this:

```
order-service:      if: needs.changes.outputs.order == 'true' || needs.changes.outputs.common == 'true'
inventory-service:  if: needs.changes.outputs.inventory == 'true' || needs.changes.outputs.common == 'true'
payment-service:    if: needs.changes.outputs.payment == 'true' || needs.changes.outputs.common == 'true'
fulfillment-service: if: needs.changes.outputs.fulfillment == 'true' || needs.changes.outputs.common == 'true'
scenario-service:    if: needs.changes.outputs.scenario == 'true' || needs.changes.outputs.common == 'true'
```

Every job is gated correctly: own filter OR `common` filter, matching the dependency graph.

### 3. `actionlint` passes cleanly on the new workflow — PASS

`actionlint` was not present; installed via Homebrew for this check (consistent with the original
report's note that it left this tooling installed as generic dev tooling, not project infra):

```
$ actionlint --version
1.7.12
installed from Homebrew

$ actionlint .github/workflows/ci.yml; echo "EXIT:$?"
EXIT:0

$ actionlint .github/workflows/build-images.yml; echo "EXIT:$?"
EXIT:0
```

Both workflows lint clean. (Ran `build-images.yml` too, as a sanity baseline that actionlint isn't
silently no-op'ing — it also passes, which is expected since that workflow predates this change and
is presumably already correct.)

### 4. Independent Maven dry-run against real Testcontainers infra, different service than the original report — PASS

The original report only exercised `fulfillment-service`. Ran `order-service` instead for independent
coverage, checking `docker compose ps` first to confirm no conflict with the already-running stack:

```
$ docker compose ps
NAME                                   ... STATUS
orderfulfillment-frontend              ... Up 4 hours
orderfulfillment-fulfillment-service   ... Up 5 hours (healthy)
orderfulfillment-grafana               ... Up 5 hours
orderfulfillment-inventory-service     ... Up 5 hours (healthy)
orderfulfillment-kafka                 ... Up About an hour (healthy)
orderfulfillment-order-service         ... Up 5 hours (healthy)
orderfulfillment-payment-service       ... Up 5 hours (healthy)
orderfulfillment-postgres              ... Up 5 hours (healthy)
orderfulfillment-prometheus            ... Up 5 hours
orderfulfillment-scenario-service      ... Up About an hour (healthy)
```

(Full docker-compose stack already running from a prior session — did not stop or touch it.)

```
$ export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
$ mvn -B -pl services/common,services/order-service -am test
...
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 36.12 s -- in com.orderfulfillment.order.OrderStreamIntegrationTest
[INFO] Running com.orderfulfillment.order.UnmappedRouteIntegrationTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.074 s -- in com.orderfulfillment.order.UnmappedRouteIntegrationTest
[INFO] Running com.orderfulfillment.order.OrderStatusTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.005 s -- in com.orderfulfillment.order.OrderStatusTest
...
[INFO] Results:
[INFO]
[INFO] Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] Reactor Summary for order-fulfillment-systems-lab 0.1.0:
[INFO] order-fulfillment-systems-lab ...................... SUCCESS [  0.001 s]
[INFO] common ............................................. SUCCESS [  0.475 s]
[INFO] order-service ...................................... SUCCESS [02:17 min]
[INFO] BUILD SUCCESS
```

Confirmed via `docker ps` mid-run that Testcontainers spun up its own independent Postgres/Kafka
containers (`amazing_carson`, `hardcore_bartik`, `testcontainers-ryuk-*`) alongside — not instead of —
the pre-existing docker-compose stack, with no port conflicts:

```
$ docker ps --format '{{.Names}}\t{{.Image}}'
amazing_carson                            apache/kafka:4.0.0
hardcore_bartik                           postgres:16-alpine
testcontainers-ryuk-25198ff4-...          testcontainers/ryuk:0.12.0
orderfulfillment-kafka                    apache/kafka:4.0.0
... (rest of the pre-existing compose stack, untouched)
```

After the build finished, Ryuk reaped its own containers automatically (confirmed via a follow-up
`docker ps` showing only the original compose stack remaining) — no manual teardown needed, and the
pre-existing stack was left exactly as found.

This is genuinely independent evidence: a different service (`order-service`, 37 tests including
Kafka-consuming integration tests) than the report's `fulfillment-service` run (10 tests), confirming
the `-pl services/common,services/<service> -am test` command shape the workflow uses actually
resolves and passes for a second, distinct service.

### 5. Frontend lint + build — PASS

```
$ node -v
v22.16.0

$ npm run lint
> frontend@0.0.0 lint
> oxlint
(exit 0, no diagnostics)

$ npm run build
> frontend@0.0.0 build
> tsc -b && vite build
...
✓ built in 569ms
(warnings only: chunk-size advisories from mermaid deps, no errors)
(exit 0)
```

Confirmed `frontend/package.json` has no `test` script (`scripts` block only has `dev`, `build`,
`lint`, `preview`) — matches the report's claim that a frontend test step is genuinely out of scope
(tracked separately as #34), not an omission.

### 6. No overlap with `build-images.yml` — PASS

Read `build-images.yml` in full:

- Trigger: `workflow_dispatch` only, with a `tag` input. No `on: push` or `on: pull_request`.
- Job: matrix build of six Docker images (five backend services + frontend), `docker/build-push-action`
  to GHCR. No `mvn test`, no `npm test`/`lint`/`build` step, no Testcontainers.
- Explicitly scoped to image publish only; deployment stays a manual `kubectl apply` per its own header
  comment.

`ci.yml`'s job set (`changes`, five backend test jobs, `frontend` lint+build job) does not build or
push any image, and does not touch deployment. The two workflows are additive, not overlapping: one
tests, the other publishes. No shared trigger conditions, no shared job names, no resource contention
(different `concurrency` scope keys — `ci.yml` sets one, `build-images.yml` sets none, but they run on
different triggers regardless).

## Judgment calls

- Ran the order-service dry-run in the foreground per the verifier mandate (no unfinished background
  work at turn end), even though the harness auto-backgrounded it past the 120s default timeout —
  waited for the background task's completion notification rather than reporting before it finished.
- Did not re-run `inventory-service`, `payment-service`, or `scenario-service` locally, on top of the
  original report's `fulfillment-service` run and this session's `order-service` run. Two independent,
  passing runs of the identical command shape (`mvn -B -pl services/common,services/<service> -am test`)
  against real Testcontainers infrastructure, spanning both the smallest suite (10 tests) and a
  Kafka-consumer-heavy suite (37 tests, multiple integration tests exercising the full outbox/consumer
  pipeline), is judged sufficient to confirm the invocation pattern works — the remaining two jobs
  differ from these only in `-pl` module name, not in mechanism.
- Verified the `if:` gating logic by reading and re-deriving from source (`pom.xml` files, `ci.yml`
  itself) rather than trusting the original report's grep output, per the task's explicit instruction
  not to just trust the report's claim.

## Deliberately not covered

- Did not trigger a real GitHub Actions run (push/PR against the actual GitHub repo). This environment
  has no path to that; `actionlint` (schema/expression-aware, not just YAML-valid) plus local dry-runs
  of the equivalent shell commands is the substitute the delegation prompt itself specified.
- Did not re-run `inventory-service`, `payment-service`, or `scenario-service` test suites locally —
  see Judgment calls for why this was judged unnecessary given two independent passing runs of the
  identical command shape already exist (one from the original report, one from this verification).
- Did not investigate or attempt to add frontend test coverage — confirmed out of scope (issue #34)
  and not part of what issue #35 claims to deliver.
- Noted but did not treat as a defect: the original report's judgment-call rationale ("only a main
  branch exists") is slightly inaccurate — `git branch -a` shows a `feature/workflow` branch in
  addition to `main`. This doesn't affect correctness: `push: branches: [main]` still only fires on
  direct pushes to `main`, and `pull_request:` (no branch filter) still covers PRs from any branch
  including `feature/workflow` into `main`. Flagging the inaccurate rationale for completeness, not as
  a functional issue.
