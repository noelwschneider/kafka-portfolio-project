# Deployment Execution Report — Public Demo Box

Sprint 2 goal 4 is deployed. The application is live, publicly reachable over HTTPS, and every
verification item from `deployment-code-changes-briefing.md` has been run against the real box rather
than a local cluster.

**One defect blocks unattended operation**, with three observed facets. It is not a deployment
problem — it is a pre-existing application defect that only becomes visible once the demo runs
continuously. It is written up as a
task brief in §6, which is the part to act on.

---

## 1. What is live

**https://fulfillment-demo.noelschneider.com**

| | |
| --- | --- |
| Server | Hetzner CX23 — 2 vCPU / 4 GB / 40 GB, x86, location `nbg1` |
| Cost | **$6.49/month** + IPv4 (USD account, no VAT) |
| Origin IP | `178.104.236.176` |
| OS / orchestrator | Ubuntu 24.04, k3s v1.36.3 (single node) |
| Ingress | Traefik (k3s built-in), CRD group `traefik.io/v1alpha1` |
| Images | `ghcr.io/noelwschneider/kafka-portfolio-project/*:latest`, `linux/amd64`, public packages |
| DNS / TLS | Cloudflare-proxied; edge certificate served by Cloudflare (Google Trust Services) |
| SSH | `ssh kafka-demo-box` (alias in `~/.ssh/config`, key `~/.ssh/kafka-portfolio-demo-box`) |

Access hardening: firewall allows inbound **22, 80, 443 only** — the Kubernetes API (6443), kubelet
(10250) and the NodePort range (30000–32767) are unreachable from the internet. Root login and
password authentication are disabled; day-to-day access is a non-root `deploy` user with sudo.

## 2. How it was deployed

Provisioning used a cloud-init file (non-root user, SSH hardening, k3s install). The application was
deployed from a clean `git clone` of the public repository on the box, following the documented
sequence in `infrastructure/kubernetes/production/README.md` exactly:

```bash
kubectl apply -f infrastructure/kubernetes/00-namespace.yaml
infrastructure/kubernetes/production/create-postgres-secret.sh
infrastructure/kubernetes/production/render.sh ghcr | kubectl apply -f -
```

It worked as written; no step needed adjustment. The rendered overlay produced 27 objects and all
eight Deployments reached Available.

**To redeploy after an image change:** run the `Build images` workflow (`workflow_dispatch`), then
`kubectl -n orderfulfillment rollout restart deployment` on the box. The overlay sets
`imagePullPolicy: Always` against the mutable `:latest` tag, so a restart is sufficient — no manifest
edit required.

## 3. Verification results

**Pods:** all 8 Running, **zero restarts**. On a 2-vCPU box this is the T2/T3 tuning working — the
local A/B in `deployment-code-changes-w1-w6-w8-tuning.md` had 2 of 8 pods restart during startup
without it.

**The allowlist holds from the public internet.** All 19 allowlisted paths return 200:

```
/  /index.html  /orders  /orders/abc-123  /scenarios  /scenario-runs/xyz
/events  /health  /architecture
/svc/order/api/orders          /svc/order/actuator/health
/svc/inventory/api/inventory   /svc/inventory/actuator/health
/svc/payment/actuator/health   /svc/fulfillment/actuator/health
/svc/scenario/demo/scenarios   /svc/scenario/demo/scenario-runs
/svc/scenario/demo/events      /svc/scenario/actuator/health
```

Everything excluded returns 404 at the edge:

```
POST /svc/inventory/demo/consumers/order-created/pause      404
GET  /svc/inventory/demo/consumers                          404
POST /svc/fulfillment/demo/consumers/x/pause                404
PUT  /svc/payment/demo/payment-behavior                     404
GET  /svc/order/actuator/prometheus                         404
GET  /svc/order/actuator/metrics                            404
GET  /svc/scenario/actuator/prometheus                      404
GET  /nope                                                  404
```

Ports 6443, 10250, 30081, 30082 and 30173 are all closed from outside.

**All eight scenarios complete through the ingress:** `standard-order`, `out-of-stock`,
`payment-failure`, `duplicate-event`, `consumer-outage`, `poison-message`, `inventory-contention`,
`high-volume`. `consumer-outage` passing is the load-bearing one — it proves Scenario Service's
server-side call to `POST /demo/consumers/{name}/pause` still works over cluster-internal DNS while
the same path 404s from the internet.

**High-volume genuinely completes** — the T4 check that mattered:

```
ordersSubmitted: 60   ordersFulfilled: 60   ordersNotFulfilled: 0
drainDurationMs: 12844   endToEndDurationMs: 14698
```

The drain took **12.8 s on the CX23 against 22 s on the development machine**. The production
timeouts (180 s) therefore have roughly 14× headroom, not the 3× that was estimated. They are safe
where they are; there is no need to widen them further, and a case could be made for narrowing them
back toward the defaults on this evidence.

**Both SSE streams survive the ingress unbuffered.** 413 live `timeline-entry` frames on
`/svc/scenario/demo/scenario-runs/{id}/stream`, and real `order-status-changed` transitions
(`PENDING` → terminal) on `/svc/order/api/orders/stream`.

**Resource headroom, measured after a full scenario sweep:**

| | Value |
| --- | --- |
| Node memory | **2919 MiB / 3819 MiB (76%)**, 618 MB available |
| Node CPU | 308 m / 2000 m (15%) |
| Largest pods | kafka 463Mi, scenario-service 338Mi, order-service 336Mi, inventory-service 293Mi |
| Disk | 2.9 GB / 38 GB used |

CPU is comfortable. Memory at 76% is the top of the band `phase-10-scaling-demo.md` identified as the
contention zone, and it is stable there with no restarts — but there is no room for a second replica
of anything. If the demo ever needs headroom, the documented next step is a second CX23 as a k3s
agent node (`deployment-platform-revision.md` §1), not a bigger single box.

## 4. Open items from the code work, now resolved

Referring to "Needs a human decision or follow-up" in `deployment-code-changes-w1-w6-w8-tuning.md`:

1. **GHCR** — resolved. All six packages are public and `linux/amd64`; the owner placeholder is
   filled in and the workflow has run successfully.
2. **Firewall half of the NodePort defense** — resolved. Inbound is restricted to 22/80/443, verified
   externally.
5. **Traefik CRD group** — confirmed `traefik.io/v1alpha1` on k3s v1.36.3. No v2 fallback needed.
6. **`create-postgres-secret.sh` namespace ordering** — the documented order is correct; the script
   ran cleanly as step 2.

Still open: **item 4**, the pre-existing 500-instead-of-404 on an unmapped path. Unchanged by this
work and still a Bug Hunt candidate.

## 5. TLS

Visitors reach the demo over HTTPS via Cloudflare's edge certificate. The origin serves TLS as well:
Traefik's `websecure` entrypoint has `http.tls=true` by default on k3s, but it was presenting
`CN=TRAEFIK DEFAULT CERT` — self-signed, and therefore not usable with Cloudflare's Full (strict)
mode.

**A Cloudflare Origin CA certificate is now installed** (`*.noelschneider.com`, valid to 2041). It
was chosen over Let's Encrypt because it needs no ACME account, never renews within the life of this
project, and does not depend on an HTTP-01 challenge surviving the Cloudflare proxy — the origin's
ingress allowlist would otherwise have to make an exception for `/.well-known/acme-challenge/`.

Installed as a `kubernetes.io/tls` Secret plus a Traefik `TLSStore` named `default` in `kube-system`:

```yaml
apiVersion: traefik.io/v1alpha1
kind: TLSStore
metadata:
  name: default
  namespace: kube-system
spec:
  defaultCertificate:
    secretName: cloudflare-origin-cert
```

The default store is the right mechanism here precisely because the Ingress carries no `host:` rule —
the certificate applies to every TLS connection regardless of SNI, so nothing about the routing or
the allowlist changes. Installing it required no Traefik redeploy and no `HelmChartConfig`, so the
running demo was never interrupted. Verified afterwards: the origin presents the Cloudflare Origin CA
certificate, allowlisted paths still return 200 over it, `PUT /svc/payment/demo/payment-behavior`
still returns 404, and the site still serves through Cloudflare.

Port 80 remains open and serving, which Cloudflare requires.

**Remaining step, in the Cloudflare dashboard:** set SSL/TLS mode to **Full (strict)**. Until that is
done the certificate is installed but not necessarily used — the zone was demonstrably not on Full
(strict) before this work, since a self-signed origin certificate would have produced a 526 rather
than the 200s observed.

**Credential handling.** The certificate and private key are deliberately **not** in the repository,
matching how the Postgres credentials are handled. The Secret was created by piping a generated
manifest over SSH into `kubectl apply -f -`, so the private key never touched the box's filesystem.
The source files live outside the repo on the operator's machine.

**Suggested follow-up for the code agent:** an `infrastructure/kubernetes/production/create-origin-cert-secret.sh`
alongside the existing `create-postgres-secret.sh`, taking cert and key paths as arguments and
applying the Secret plus `TLSStore`. It would make this reproducible if the box is ever rebuilt,
which is currently the one deployment step that exists only in this report.

## 6. DEFECT — reset cannot restore stock, and the auto-reset makes it worse

**This is the call to action. Everything above is done; this is not.**

### What happens

`POST /demo/reset` restores `availableQuantity` to the seed values but never clears
`reservedQuantity`. Reservations are only released on the payment-failure compensation path, so on
every successful order the reservation persists forever. Since

```java
// services/inventory-service/.../InventoryItemEntity.java:44
public int freeQuantity() { return availableQuantity - reservedQuantity; }
```

free stock trends monotonically to zero, and once it reaches zero **every order is rejected with
`INSUFFICIENT_STOCK`, permanently, and reset does not fix it.**

### Observed on the live box

Roughly twenty minutes of scenario testing was enough to reach it — about three `high-volume` runs
(60 orders each against SKU-003, seeded at 100):

```
POST /svc/scenario/demo/reset
  -> {"inventoryRestored":true,"consumersResumed":[],"paymentBehaviorCleared":true, ...}

GET /svc/inventory/api/inventory
  SKU-003: available=100  reserved=100  free=0
  SKU-001: available= 10  reserved=  9  free=1
  SKU-004: available=  2  reserved=  2  free=0

POST /svc/order/api/orders {"items":[{"sku":"SKU-003","quantity":1}]}
  -> order-20130 ... REJECTED_OUT_OF_STOCK
```

Reset reported success. The order was still rejected.

### Why this is worse than it looks — three findings, all observed on the live box

**Finding 1: the idle auto-reset wedges a working demo.** `IdleResetScheduler` calls
`demoResetService.reset()` — the same path. This was watched happening. The stopgap described below
had SKU-003 at `available=200, reserved=100, free=100` (working). Then:

```
04:21:24  INFO  Idle auto-reset triggered after PT15M51.87S of no scenario activity

SKU-003: available=100  reserved=100  free=0    <-- wedged
SKU-004: available=  2  reserved=  2  free=0    <-- wedged
```

The mechanism built to keep the demo healthy while unattended is the mechanism that wedges it. On an
unattended box this is not a slow drift — it is guaranteed within 15 minutes of the stock being
consumed.

**Finding 2: reset's own write is rejected once reservations exceed the seed.** Inventory Service
validates `availableQuantity >= reservedQuantity`, so restoring a seed value below the accumulated
reservation fails outright:

```
04:21:24  WARN  Failed to restore seed quantity for SKU-001:
                409 Conflict {"code":"INVENTORY_CONFLICT",
                "message":"availableQuantity 10 is ..."}
```

`restoreInventory()` catches this per SKU, logs a warning and continues, so the failure is invisible
to the caller. Note the interaction with finding 1: the reset that wedged SKU-003 and SKU-004
*silently failed* on SKU-001 — partial success reported as success.

**Finding 3: the auto-reset re-fires every check interval, indefinitely.** `lastActivityAt()` reads
only the most recent scenario run, and the scheduler never records its own execution as activity, so
the idle condition stays true forever once crossed:

```
04:21:24  Idle auto-reset triggered after PT15M51S
04:22:24  Idle auto-reset triggered after PT16M52S
```

That is one full reset — four inventory writes plus consumer and payment-behavior calls — **every 60
seconds for as long as nobody uses the demo**, along with a warning log line per cycle. Each firing
is cheap, but on a box at 76% memory it is permanent avoidable background work and it makes the logs
useless for spotting anything else.

`PUT /api/inventory/{sku}` accepts only `availableQuantity`
(`docs/openapi/inventory-service.yaml`, `UpdateInventoryRequest`), so reset **structurally cannot**
fix findings 1 and 2 through the existing contract. Any real fix touches a frozen contract file and
therefore follows `.claude/CLAUDE.md`'s coordination protocol.

### Stopgap currently in place — remove it as part of the fix

A stopgap was applied to the live box — `availableQuantity` set manually to `reserved + seed` — and
it worked for about sixteen minutes before the idle auto-reset overwrote it (finding 1 above). **The
demo is therefore currently wedged for SKU-003 and SKU-004**, and re-applying the stopgap only
restarts the same countdown. Do not treat the stopgap as holding; the demo needs the real fix to be
reliable unattended. `standard-order` still succeeds because it uses a SKU whose reservation has not
yet passed its seed, which is exactly the kind of intermittent behaviour that makes this defect easy
to miss in casual testing.

### Recommended resolution

**Option A — give reset the ability to clear reservations (recommended).** Add an inventory-service
demo capability to zero `reservedQuantity` alongside setting `availableQuantity`, and have
`DemoResetService.restoreInventory()` use it. Smallest change that makes reset mean what its name
says and makes the idle auto-reset actually restorative. It is a contract change:
`docs/openapi/inventory-service.yaml` needs updating, plus a note in `docs/CHANGELOG-contracts.md`
if that file exists. Keep it under `/demo` per Agent Rule 9 — it is demo administration, not
business API — and note that `/demo/consumers`-style paths are unrouted in production by design, so
whichever path is chosen must either stay server-side (Scenario Service calls it over cluster DNS,
which is how reset already works) or be added to the ingress allowlist deliberately.

**Option B — release reservations when an order reaches a terminal success state.** The
domain-correct fix: on `FULFILLED`, convert the reservation (decrement `availableQuantity`, clear
`reservedQuantity`) rather than leaving it held forever. This makes the stock model honest and fixes
the root cause rather than the reset symptom — but it changes saga behaviour, touches the
compensation path shared with `payment-failure`, and deserves its own design pass. Worth evaluating
on its merits, not as a deployment hotfix.

**Option C — reset sets `availableQuantity = seed + currentReserved`.** Requires no contract change
because it uses only the existing PUT. Explicitly **not recommended**: it is the stopgap above,
promoted to code. It makes displayed quantities drift upward forever and leaves the underlying
accounting wrong.

### Acceptance criteria for whichever option is chosen

- After `POST /demo/reset`, `freeQuantity` equals the seed quantity for all four SKUs.
- A `standard-order` scenario succeeds immediately after a reset that followed a full `high-volume`
  run.
- The idle auto-reset restores a wedged demo to working rather than wedging a working one — worth an
  integration test, since this is the specific behaviour that failed.
- Reset reports failure honestly: a SKU it could not restore must not be reported as
  `inventoryRestored: true` (finding 2).
- The auto-reset fires at most once per idle period rather than once per check interval — recording
  its own run as activity is the obvious fix (finding 3).
- The manual stopgap values on the live box are no longer needed; a reset returns the box to a clean
  seeded state on its own.

### Deploying the fix

Run the `Build images` workflow, then on the box:
`kubectl -n orderfulfillment rollout restart deployment/inventory-service deployment/scenario-service`
(or all deployments). No manifest change is required.

## 7. Cost and operations

| Line | Cost |
| --- | --- |
| CX23 demo box, always on | **$6.49/month** + IPv4 |
| Cloudflare (DNS, TLS, proxy) | $0 |
| GHCR (public packages) | $0 |
| **Total** | **under $8/month** |

Nothing here is usage-metered; included traffic is 20 TB. The dev box remains a separate,
per-session server (`hetzner-dev-box-setup.md`) — its `dev-down.sh` prunes snapshots by name
(`kafka-portfolio-dev-box`), so it cannot touch demo box resources.

## 8. Action item — correct the dev box audit instructions

Both servers now live in the **same Hetzner project**, which invalidates a check in
`docs/agent-reports/sprint-2/hetzner-dev-box-setup.md`. That document's "Monthly audit" section
currently reads:

```
hcloud server list          # should be empty unless a session is actively in progress
```

That is no longer true — `kafka-portfolio-demo-box` is always present, by design. Left as written,
the audit's most important signal (a dev box someone forgot to destroy) becomes something the reader
learns to ignore. Suggested replacement:

```
hcloud server list          # should show ONLY kafka-portfolio-demo-box (the always-on demo box).
                            # kafka-portfolio-dev-box appearing here means a session was never
                            # torn down -- run dev-down.sh.
```

The two lines below it need no change: `hcloud image list --type snapshot` should still show at most
one snapshot (the demo box takes none), and `hcloud volume list` should still be empty.

Worth stating explicitly for whoever picks this up: `dev-down.sh` is safe as written. Its snapshot
pruning filters on the literal server name `kafka-portfolio-dev-box`
(`infrastructure/dev-box/dev-down.sh:46`), so it cannot delete demo box snapshots, and it deletes
only the server it is named after. This is a documentation correction, not a script fix.
