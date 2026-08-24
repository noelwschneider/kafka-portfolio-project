# Platform Revision — Sizing Against Hetzner's Available Capacity

Hetzner's cost-optimized capacity is constrained. Of the plans that were candidates, **only CX23
(2 vCPU / 4 GB / 40 GB, €5.49/month) is currently orderable**; CX33, CX43 and the entire ARM CAX line
are not. Regular-performance plans (CPX/CCX) are available but were re-priced 2.2–2.7× in June 2026
and are out of budget.

This document sizes the deployment against what can actually be bought, using the project's own
measurements rather than estimates.

Companion documents: `deployment-platform-options.md` (platform category comparison),
`deployment-code-changes-briefing.md` (repo changes), `dev-vs-demo-host-separation.md`.

---

## 1. Recommendation

**Start with one CX23 at €5.49 + €0.50 IPv4 = €5.99/month.** It is in stock, it is the cheapest
option on the table, and the project's own prior measurements say the workload fits. Do the JVM and
probe tuning described in §3 as part of the deployment work — it was already a recommended lever and
on this box it becomes load-bearing.

**If the 2 vCPU limit proves to be a real problem during deployment verification, add a second CX23
as a k3s agent node for another €5.99.** This is the property that makes starting small safe: it is
an incremental step, not a migration. Two CX23s give 4 vCPU / 8 GB for €11.98/month — the same money
as the CAX21 that isn't available — and turn the demo into a genuine multi-node cluster, which is a
better Kubernetes story than the single node it replaces.

Ceiling either way: **€5.99–11.98/month**, below the original €10.99 target at the starting size.

## 2. Why the available alternatives looked 8× more expensive

Hetzner's 15 June 2026 price change hit the product lines very unevenly:

- **CX and CAX** (shared Intel / shared ARM): up ~1.3–1.4×. Still cheap — and the scarce ones.
- **CPX** (shared AMD): CPX32 €13.99 → €35.49; CPX42 €25.49 → €69.49. Up 2.5–2.7×.
- **CCX** (dedicated AMD): CCX13 €15.99 → €42.99; CCX23 €31.49 → €85.99. Up ~2.7×.

The cheap line is the constrained one, and what stays in stock is the line that got re-priced. CPX42
at €69.49 against a CX33 at €8.49 is the 8× gap. Those are the plans to avoid — not Hetzner itself,
whose CX23 is still one of the cheapest 4 GB servers available anywhere.

Stockouts on the CX line have been intermittent — sold out in one location and back within minutes to
an hour — rather than a permanent withdrawal, so a larger plan may become orderable later. See §6.

## 3. Does the workload actually fit in 4 GB? Yes — with evidence

This does not need to be estimated. `docs/agent-reports/sprint-1/phase-10-scaling-demo.md` measured
this exact stack under a hard memory cap:

- The development machine's Docker Desktop VM is capped at **3.825 GiB** — *less* than a CX23's 4 GB.
- Inside that cap, the full 8-pod stack at 1 replica each (Postgres, Kafka, five services, frontend)
  **stood up successfully and ran**. That is the same configuration the public demo would run.
- Instability appeared only at **9 pods** — when Inventory Service was scaled to 3 replicas — where
  memory sat at 76–84% and pods crash-looped.

So the demo configuration is not a gamble; it is what has been running locally all along, and a CX23
gives it marginally *more* memory than it has now, with less overhead (k3s on bare Linux is lighter
than Docker Desktop's VM plus a `kind` node container).

**The real constraint on a CX23 is CPU, not memory.** The development machine had 8 vCPUs available;
a CX23 has 2. The same report recorded CPU spiking to 270–406% under contention — i.e. the workload
will happily consume more than two cores when five JVMs and a broker start simultaneously. Expect
slow cold starts and probe pressure during boot, and treat these as required work rather than
optional tuning:

1. **Replace Kafka's readiness probe.** It currently shells out to
   `kafka-broker-api-versions.sh`, which starts *its own JVM* on every probe. The Phase 10 report
   identifies this exact probe as what began timing out and flapping the broker Ready/NotReady under
   CPU contention, taking the Kafka Service's endpoints to zero. On a 2-vCPU box this is the single
   highest-value change: use a TCP socket check on the broker port instead.
2. **Cap the JVM heaps explicitly.** Kafka's default broker heap is 1 GB regardless of the container
   limit; 512 MB is ample for one broker with these topics. Set `-XX:MaxRAMPercentage` on the five
   services rather than relying on the JVM's default 25% of limit.
3. **Relax probe timings and stagger startup** so a slow cold start is not read as a failure —
   longer `initialDelaySeconds` and `failureThreshold` on startup/readiness probes.
4. **Re-check the scenario timeouts.** `order-poll-timeout-ms` (20 s),
   `high-volume-order-watch-timeout-ms` (60 s) and the lag-poll timeout were tuned on 8-core hardware.
   On 2 vCPUs the high-volume scenario's 60-order burst may exceed them and report a false failure.
   Verify on the deployed box and widen if needed — but only after confirming the work is genuinely
   completing, not by widening the timeout to hide a real problem.

Disk is not a concern: images plus PVCs plus the OS land around 7 GB against the CX23's 40 GB.

## 4. Container images: CX23 is x86

The CX line is Intel/AMD, so images must be `linux/amd64` while the development laptop is arm64. Three
ways to bridge that, in order of preference:

1. **Build in GitHub Actions and push to GHCR.** Free for public repos, native x86 runners, no QEMU.
   This is image *building*, not deployment automation — deployment stays a manual `kubectl apply`, so
   it does not reopen the deferred CI/CD decision.
2. **`docker buildx --platform linux/amd64` on the laptop.** Works, but Maven compilation under QEMU
   emulation is several times slower.
3. **Build on the server.** Native and simple, but a five-module Maven build on 2 vCPU / 4 GB
   alongside a running cluster is a poor idea. If used at all, build before the cluster is up.

This supersedes the architecture note in `deployment-code-changes-briefing.md` W6: the cross-build
requirement stands as originally written, with option 1 as the recommended way to satisfy it.

## 5. Cost levers that still apply

**Lever 1 — the dev box is hourly, not monthly (saves ~€20/month).** Hetzner bills by the hour up to
the monthly cap, so a dev box only costs money while it exists. Three eight-hour sessions on a larger
plan is under €1. This requires the `dev-down` script to **snapshot and delete**, not shut down —
powering a Hetzner server off does not stop billing. Without that script, the lever does not work.

**Lever 2 — JVM and probe tuning.** Now mandatory rather than optional (§3). Worth noting it is also
the one lever that adds to the portfolio: measuring and tuning a stack into a 4 GB / 2 vCPU box is a
better interview answer than provisioning around the problem.

**Lever 3 — snapshot and delete the demo box between job searches.** ~€0.30–1.15/month retention
against €5.99 running.

**Lever 4 — new-account credits as a runway, not a foundation.** DigitalOcean gives $200 valid for
60 days (card required, bills normally afterward); Civo gives $250 for the first month. Useful if
Hetzner capacity forces a move, but calendar the expiry the day you sign up — a forgotten expiry on
DO's $48/month 8 GB droplet is exactly the surprise this budget cannot absorb.

**Lever 5 — the domain is nearly free.** Cloudflare Registrar sells at cost with flat renewals; a
`.xyz` is a few dollars a year; `sslip.io` is a $0 stopgap that works with Let's Encrypt.

**Lever 6 — Oracle Cloud Always Free (€0, with a real catch).** 2 OCPU / 12 GB ARM is enough for the
workload, but Oracle halved that allowance in June 2026 with no announcement, A1 capacity is often
unavailable to free accounts, and idle instances can be reclaimed. For a machine whose job is being
up when a stranger clicks a link, unannounced reclamation is the worst possible failure mode. Fine as
a second mirror; poor as the only home of a résumé URL.

## 6. If a larger plan becomes available later

CX33 and CAX21 stockouts have been intermittent. Two zero-cost responses:

- **Rescaling a CX23 upward** requires stock of the target plan and re-prices at current rates —
  which are the current rates anyway, so there is no grandfathering to lose here.
- **Poll the Hetzner API** for availability rather than checking the console by hand. This is a small
  script against the server-types endpoint; it costs nothing and removes the need to watch for it.

Neither is worth waiting on before deploying. Starting on a CX23 and growing later is cheaper than
staying undeployed.

## 7. Non-Hetzner fallbacks, if capacity closes entirely

| Provider | Plan | Spec | Price | Hourly billing? |
| --- | --- | --- | --- | --- |
| Netcup | VPS 1000 G11 | 6 vCPU / 8 GB / 256 GB | €6.53/mo net (+VAT) | No — minimum contract term applies |
| Contabo | Cloud VPS 4 | 4 vCPU / 8 GB / 100 GB | €6.60/mo on 24-month term; monthly ~15–20% higher plus one-time setup fee | No |
| OVHcloud | VPS-2 | 4 vCPU / 8 GB / 75 GB | from $8.50/mo on annual upfront | No |
| DigitalOcean / Vultr | Basic 8 GB | 4 vCPU / 8 GB | $48/mo | Yes |

Netcup is the cheapest route to 8 GB and 6 vCPU, and would remove the CPU pressure in §3 entirely.
The tradeoff is that none of the cheap providers bill hourly, so levers 1 and 3 stop working, and
contract minimums replace a delete-anytime model.

## 8. What this changes in the existing plan

- `deployment-code-changes-briefing.md` W1–W5 and W7–W8 are unaffected — ingress routing, the
  frontend build, the SPA fallback, auto-reset, CORS, secrets and docs are all provider-independent.
- **W6 stands as written** (x86 cross-build), with GitHub Actions image builds as the recommended
  way to satisfy it (§4).
- **New work, driven by the 2 vCPU limit:** the Kafka readiness probe change, explicit JVM heap caps,
  probe timing, and a scenario-timeout re-check (§3). These belong with the same code agent as the
  W-items; they are small but should not be discovered during deployment.
- `dev-vs-demo-host-separation.md` holds unchanged.
