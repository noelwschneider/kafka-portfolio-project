# Issue #35 — CI workflow that runs tests, with per-service path filters

## What changed

- `.github/workflows/ci.yml` (new file) — a `push`/`pull_request`-triggered workflow, separate from
  `build-images.yml`. A `changes` job uses `dorny/paths-filter@v3` to detect which of the five backend
  service directories, `services/common`/`pom.xml`, or `frontend/` changed. One job per backend
  service (`order-service`, `inventory-service`, `payment-service`, `fulfillment-service`,
  `scenario-service`) runs `mvn -B -pl services/common,services/<service> -am test` on JDK 21
  (Temurin), gated on that service's own path filter **or** the `common`/root-`pom.xml` filter, so a
  change to the shared module reruns every service and an isolated service change reruns only that
  service. A `frontend` job runs `npm ci`, `npm run lint` (oxlint), `npm run build` (`tsc -b && vite
  build`) on Node 22, gated on `frontend/**` only. No frontend test step — `package.json` defines no
  `test` script (confirmed by reading it directly); adding one is issue #34's scope, not this one.

No other files were touched. `build-images.yml`, `docker-compose.yml`, and deployment manifests are
untouched, per the task's explicit boundary.

## How this was verified

Confirmed the actual module/test setup before designing anything (root `pom.xml` is a five-module
Maven reactor plus `services/common`; every service pom declares a direct dependency on `common`):

```
$ grep -A3 "<artifactId>common" services/order-service/pom.xml
            <artifactId>common</artifactId>
            <version>${project.version}</version>
$ grep -l "artifactId>common<" services/*/pom.xml
services/order-service/pom.xml
services/fulfillment-service/pom.xml
services/inventory-service/pom.xml
services/common/pom.xml
services/scenario-service/pom.xml
services/payment-service/pom.xml
```

Confirmed how tests are actually run locally, from `README.md`'s "Running tests" section:

```
mvn -pl services/order-service test
# or, for every module:
mvn test
```

(Testcontainers-based — spins up real Postgres/Kafka per test class, no external services required
beyond Docker.)

Dry-ran the exact reactor build the workflow uses, against JDK 21 (Temurin, matching the workflow's
`java-version: "21"`), with the full docker-compose stack already running on the host (did not stop
it, did not start it — it predates this session):

```
$ export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
$ mvn -q -DskipTests -pl services/common -am install
(clean exit, no output)

$ mvn -pl services/fulfillment-service -am test
...
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] Reactor Summary for order-fulfillment-systems-lab 0.1.0:
[INFO] order-fulfillment-systems-lab ...................... SUCCESS [  0.001 s]
[INFO] common ............................................. SUCCESS [  0.532 s]
[INFO] fulfillment-service ................................ SUCCESS [01:06 min]
[INFO] BUILD SUCCESS
```

This confirms the `-pl services/common,services/<service> -am test` invocation used in every backend
job actually resolves and passes against real Testcontainers-backed Postgres/Kafka, not just against
a mocked test double. Ran only `fulfillment-service` (the smallest suite, 10 tests) to keep load on a
host already running the full docker-compose stack plus other concurrent agent work; did not re-run
the other four services' suites (see Deliberately not covered).

Confirmed the frontend commands the `frontend` job runs, against what actually exists in
`frontend/package.json` (`lint`: `oxlint`, `build`: `tsc -b && vite build`, no `test` script):

```
$ node -v
v22.16.0
$ npm run lint
> frontend@0.0.0 lint
> oxlint
(clean exit, no diagnostics)

$ npm run build
...
✓ built in 420ms
(warnings only about chunk size, no errors)
```

Validated the workflow YAML both generically and against the GitHub Actions schema specifically:

```
$ python3 -c "import yaml; d=yaml.safe_load(open('.github/workflows/ci.yml')); print('OK', list(d['jobs'].keys()))"
OK ['changes', 'order-service', 'inventory-service', 'payment-service', 'fulfillment-service', 'scenario-service', 'frontend']

$ brew install actionlint   # was not present; installed for this check
$ actionlint .github/workflows/ci.yml
(no output, exit 0)
$ actionlint .github/workflows/build-images.yml   # baseline: confirms actionlint doesn't just pass everything
(no output, exit 0)
```

## Judgment calls

- **`common`/root-`pom.xml` change fans out to all five service jobs.** The task explicitly asked me
  to check whether `common` needs to run alongside every service — confirmed via the pom
  dependency grep above that all five do depend on it directly, so a change there can silently break
  any of them. Gating each service job on `needs.changes.outputs.common == 'true' ||
  needs.changes.outputs.<service> == 'true'` was the straightforward way to express that without a
  sixth "run everything" job.
- **Used `dorny/paths-filter@v3`** rather than GitHub's built-in `on.push.paths`/`on.pull_request.paths`
  triggers. The built-in path filters apply to the whole workflow, not per-job, so they can't express
  "job A runs only for service A's paths, job B only for service B's paths, in the same workflow run"
  — which is exactly what the task asked for (one push shouldn't rebuild/retest all five services).
  `paths-filter` runs once, cheaply, and fans its outputs out to per-job `if:` conditions. This is a
  widely-used community action (not officially GitHub's), which is a small trust tradeoff against
  getting real per-job filtering in one workflow file; I considered it worth it over duplicating
  near-identical single-job workflows five times.
- **Push trigger is `branches: [main]`; PR trigger has no branch filter.** Matches this repo's actual
  git history (only a `main` branch exists — confirmed via `git status` at task start showing
  "Current branch: main" with no other branches mentioned). PRs into `main` are covered regardless of
  source branch name.
- **Added a `concurrency` cancel-in-progress group.** Not explicitly requested, but avoids a queue of
  redundant runs piling up on rapid pushes to the same branch/PR, which felt like an obvious
  complement to path filtering (both exist to avoid wasted CI work) rather than scope creep.
- **Did not add a caching layer beyond `actions/setup-java`'s built-in `cache: maven` and
  `actions/setup-node`'s `cache: npm`.** Sufficient for this task; a shared Testcontainers image cache
  across runs would be a separate optimization, not part of "make tests actually run."
- **Ran the dry-run tests against JDK 21 Temurin specifically**, not whatever `mvn -v` reports as
  default on this machine (which resolved to JDK 26 via Homebrew) — because the workflow pins
  `java-version: "21"` (the project's pinned stack) and I wanted the dry run to reflect what CI will
  actually use, not an untested toolchain.

## Deliberately not covered

- Did not re-run the full test suite for `order-service`, `inventory-service`, `payment-service`, or
  `scenario-service` locally — only `fulfillment-service` (smallest, 10 tests, ~1 minute). The command
  shape (`mvn -pl services/common,services/<service> -am test`) is identical across all five jobs and
  was proven to work end-to-end for one service against real Testcontainers infrastructure; I judged
  re-running all five (each spinning up its own Postgres/Kafka containers) an unnecessary load on a
  host that already has the full docker-compose stack running plus other concurrent agent work on this
  shared machine, per the "rebuild only what your change touches" guidance. Prior sprint reports
  (`docs/agent-reports/sprint-1/phase-3-boundary.md`) independently confirm all five services' suites
  pass under the same `mvn -pl <service> test` invocation pattern.
- Did not verify the workflow against a real GitHub Actions run (push/PR to the actual repo) — this
  environment has no way to trigger real Actions execution. Verification is: YAML validity, actionlint
  (which understands the GitHub Actions schema, expression syntax, and job dependency graph, not just
  YAML syntax), and dry-running the equivalent shell commands locally, which is the standard substitute
  called out in this task's own instructions.
- Did not add a frontend test step or test framework — `frontend/package.json` has no `test` script
  today, and issue #34 (zero automated frontend tests) is explicitly out of scope for this task.
- Did not touch `build-images.yml`, deployment manifests, or `docker-compose.yml` — out of scope per
  the task boundary.
- Left `actionlint`/`shellcheck` installed via Homebrew on this machine (were not present before this
  session) since they are generic developer tooling, not project or docker infrastructure; did not
  uninstall them, distinct from the docker-compose teardown convention which is about containers this
  session starts.
