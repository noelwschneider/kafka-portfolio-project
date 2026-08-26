# Issue #37: frontend missing from redeploy.sh

## What changed

- `infrastructure/kubernetes/production/redeploy.sh` — added `frontend` to the `DEPLOYMENTS` array,
  placed last after the five backend services. Updated the header comment to explain why frontend's
  ordering rationale differs from the backend fleet's (no shared memory-pressure argument, but kept
  in the same sequential script for a single source of truth). Updated the trailing summary line
  from "backend deployments" to "deployments" and the usage comment from "all five" to "all six".
- `infrastructure/kubernetes/production/README.md` — updated the `redeploy.sh` row in the layout
  table and the "Redeploying" section to state that frontend is included, at the end of the
  sequence, and explain why its inclusion doesn't carry the same memory-exhaustion rationale as the
  backend ordering (it is a static nginx container at 64Mi/128Mi request/limit — see
  `infrastructure/kubernetes/09-frontend.yaml` — and does not carry the `maxSurge: 0` patch that
  `common/patch-tuning.yaml`'s T5 applies only to the five backend Deployments).

Confirmed before editing that a `frontend` Deployment actually exists
(`infrastructure/kubernetes/09-frontend.yaml`, `metadata.name: frontend`) and that it is already
wired into the GHCR overlay's image substitution and `imagePullPolicy: Always` patch
(`infrastructure/kubernetes/production/ghcr/kustomization.yaml` lines 35-37 and 53) — it was only
`redeploy.sh` and the README's redeploy instructions that were silent about it, exactly as the issue
describes.

Chose option 1 from the issue (add frontend to the sequential restart) rather than option 2
(document a deliberate exclusion), because there's no reason to keep frontend out of the one script
that's supposed to be the source of truth for "how to redeploy" — see Judgment calls.

## How this was verified

Confirmed shell syntax is valid:

```
$ bash -n infrastructure/kubernetes/production/redeploy.sh && echo "syntax OK"
syntax OK
```

`shellcheck` is not installed in this environment; skipped.

Attempted verification against a real `kind` cluster first (this repo's default preference for
Kubernetes-touching changes). Created a plain `kind` cluster (`redeploy-verify`, no port mappings)
and `kind load docker-image`d the six `:local` images successfully:

```
$ kind load docker-image order-service:local inventory-service:local payment-service:local \
    fulfillment-service:local scenario-service:local frontend:local --name redeploy-verify
Image: "order-service:local" ... loading...
Image: "inventory-service:local" ... loading...
Image: "payment-service:local" ... loading...
Image: "fulfillment-service:local" ... loading...
Image: "scenario-service:local" ... loading...
Image: "frontend:local" ... loading...
```

But the kind control-plane container then couldn't serve API requests reliably (TLS handshake
timeouts on every `kubectl` call), because the docker-compose stack that was already running before
I started (10 containers: postgres, kafka, 5 backend services, frontend, prometheus, grafana) had
already claimed most of Docker Desktop's VM memory:

```
$ docker stats --no-stream
...
redeploy-verify-control-plane   132.53%   471.5MiB / 3.825GiB   12.04%
orderfulfillment-kafka           81.14%   790.9MiB / 3.825GiB   20.19%
orderfulfillment-order-service   63.16%   364.8MiB / 3.825GiB    9.32%
orderfulfillment-inventory-service 58.33% 295.5MiB / 3.825GiB    7.54%
... (Docker Desktop VM total: 3.825GiB)
```

This is the same class of resource contention ADR-011 documents on the demo box itself, so rather
than force it (or tear down the pre-existing compose stack, which I did not start and should not
disrupt), I deleted the kind cluster immediately:

```
$ kind delete cluster --name redeploy-verify
Deleting cluster "redeploy-verify" ...
Deleted nodes: ["redeploy-verify-control-plane"]
```

Fell back to exercising the actual script's control flow against a stubbed `kubectl` on `PATH` —
real execution of the real script, verifying ordering, frontend inclusion, and the
failure-short-circuit behavior without needing a live API server:

Happy path — all six restart in order, frontend last:

```
$ PATH="$SCRATCH/stubbin:$PATH" bash infrastructure/kubernetes/production/redeploy.sh
==> Restarting deployment/order-service
...
==> Restarting deployment/frontend
==> Waiting for deployment/frontend to become healthy (timeout 120s)
==> deployment/frontend healthy
==> All 6 deployments restarted and healthy.
--- exit code: 0 ---
```

Failure short-circuit — scenario-service's rollout stuck, frontend must not restart:

```
$ FAIL_DEPLOYMENT=scenario-service PATH="$SCRATCH/stubbin:$PATH" bash infrastructure/kubernetes/production/redeploy.sh
...
==> Restarting deployment/scenario-service
==> Waiting for deployment/scenario-service to become healthy (timeout 120s)
error: deployment "scenario-service" exceeded its progress deadline
!! deployment/scenario-service did not become healthy within 120s.
!! Stopping here rather than restarting the remaining deployments on top of a failure.
--- exit code: 1 ---
$ grep -c frontend "$SCRATCH_LOG" || echo "frontend NOT restarted (expected)"
0
frontend NOT restarted (expected)
```

Frontend's own rollout stuck — script still fails loudly (not silently skipped as "just static
files"):

```
$ FAIL_DEPLOYMENT=frontend PATH="$SCRATCH/stubbin:$PATH" bash infrastructure/kubernetes/production/redeploy.sh
...
==> Restarting deployment/frontend
==> Waiting for deployment/frontend to become healthy (timeout 120s)
error: deployment "frontend" exceeded its progress deadline
!! deployment/frontend did not become healthy within 120s.
--- exit code: 1 ---
```

`--timeout` override still propagates to frontend's `rollout status` call:

```
$ PATH="$SCRATCH/stubbin:$PATH" bash infrastructure/kubernetes/production/redeploy.sh --timeout 180s > /dev/null
$ grep frontend "$SCRATCH_LOG"
kubectl rollout restart deployment/frontend -n orderfulfillment
kubectl rollout status deployment/frontend -n orderfulfillment --timeout=180s
```

Post-verification environment check — no kind clusters left, original compose stack untouched:

```
$ kind get clusters
No kind clusters found.
$ docker ps --format '{{.Names}}' | sort
eloquent_black
naughty_bell
orderfulfillment-frontend
orderfulfillment-fulfillment-service
orderfulfillment-grafana
orderfulfillment-inventory-service
orderfulfillment-kafka
orderfulfillment-order-service
orderfulfillment-payment-service
orderfulfillment-postgres
orderfulfillment-prometheus
orderfulfillment-scenario-service
testcontainers-ryuk-e7ca275e-e5f2-4ff4-b3f7-7ba5aa99af95
```

(`eloquent_black`, `naughty_bell`, and the `testcontainers-ryuk` container were not started by this
task and are left as found; the ten `orderfulfillment-*` containers are the pre-existing compose
stack, unmodified.)

## Judgment calls

- **Option 1 vs option 2**: chose to add `frontend` to `redeploy.sh` (option 1) rather than
  document a deliberate exclusion (option 2). The frontend Deployment is already deployed to
  production via the same GHCR overlay and already gets `imagePullPolicy: Always`, so a freshly
  pushed frontend image needs the same "restart to pull it" step every backend service needs — there
  is no version of this where frontend legitimately doesn't need restarting. Keeping it out of the
  script would mean `redeploy.sh` is no longer actually "the redeploy workflow," just "the backend
  redeploy workflow," which is precisely the gap the issue is about.
- **Placement: last, not interleaved or first**: the five backend services are ordered only by the
  shared memory-pressure rationale in the header comment (ADR-011) — restarting one at a time keeps
  peak memory to "steady state plus one service." Frontend doesn't carry that rationale (64Mi/128Mi
  request/limit next to JVM services requesting hundreds of MiB, and no `maxSurge: 0` patch applied
  to it), so there's no dependency-based reason to put it anywhere specific in the sequence. I put it
  last so the backend fleet — the services actually doing message processing and holding state —
  finishes and is confirmed healthy before anything else changes, and so the existing five-service
  order isn't disturbed by an insertion in the middle.
- **Kept frontend inside the fail-fast loop rather than special-casing it to "best effort"**: a
  broken frontend rollout (bad image, crashing container, failing readiness probe) is a real user-
  facing outage even though it's lightweight, so it should stop the script and print the same
  diagnostic guidance as a backend failure, not be silently skipped. Verified this explicitly (Test
  3 above).
- **Did not add `maxSurge: 0` to the frontend Deployment in `patch-tuning.yaml`**: that would change
  the frontend's rollout *strategy*, not just the redeploy *tooling*, and the issue's scope boundary
  is "redeploy-tooling fix." Documented in the README instead that frontend intentionally doesn't
  carry that patch, so the gap is visible rather than silent (per the issue's explicit requirement
  for whichever option is chosen).
- **Verification fallback from `kind` to a stubbed `kubectl`**: `kind` is this repo's stated default
  for Kubernetes-touching changes, and I started down that path, but the pre-existing compose stack
  had already claimed most of Docker Desktop's 3.825GiB VM budget, and adding a kind control-plane on
  top produced the exact API-server-unresponsive symptom ADR-011 describes for the demo box under
  memory pressure. Since the compose stack was running before I started and I'm not supposed to
  disrupt it, and the change under test is pure bash control flow (loop order, argument parsing,
  exit-code propagation) rather than anything Kubernetes-API-specific, a stubbed `kubectl` that
  records calls and can simulate a failed rollout exercises the actual, unmodified script file and
  is sufficient evidence for this particular change. It does not prove the real `kubectl rollout
  restart`/`rollout status` semantics against an actual Deployment object — see Deliberately not
  covered.

## Deliberately not covered

- **Not verified against a live Kubernetes API server** (real `kubectl rollout restart` / `rollout
  status` against an actual `frontend` Deployment object, confirming the rollout genuinely completes
  and `kubectl rollout status` genuinely blocks until it does). The stubbed-`kubectl` test proves the
  script's own control flow is correct; it does not prove Kubernetes' rollout mechanics behave as
  the script assumes for a Deployment shaped like `09-frontend.yaml`. That gap exists because the
  host's Docker Desktop VM couldn't hold both the pre-existing compose stack and a kind cluster at
  once, not because it was skipped for convenience — worth a follow-up kind or on-box run when the
  environment isn't already this loaded.
- **Did not run the redeploy script against the actual production box.** This is redeploy tooling
  for a live public demo; the safest verification is the box itself the next time a real image gets
  pushed, not a claim that this session simulated that outcome.
- **Did not touch `patch-tuning.yaml`, `ghcr/kustomization.yaml`, or `09-frontend.yaml`.** Per the
  issue's scope boundary this is redeploy-tooling only; the frontend Deployment's own shape (probes,
  resource limits, missing `maxSurge: 0`) is unchanged and undiscussed beyond documenting the
  rollout-strategy gap in the README.
- **Did not touch any frontend page/component code**, per the explicit scope boundary in the task.
