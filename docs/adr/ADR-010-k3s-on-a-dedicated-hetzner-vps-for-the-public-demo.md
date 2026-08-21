# ADR-010: Run the public demo on k3s on a dedicated Hetzner CX23, deployed manually

- **Status:** Accepted. Decided in Sprint 2 goal 4; the repo-side changes are implemented, the
  server itself is provisioned separately.
- **Date:** 2026-08-21

## Context

ADR-007 put Kubernetes in this project only after the service boundaries stabilized, and kept it
deliberately optional: local `kind`, plain YAML, no Helm, never a prerequisite for development.
That decision is unchanged. What changed is that the project now needs a **URL a stranger can
click** — a recruiter opening a link, running a failure scenario, and watching real events cross
five services — which local `kind` cannot provide.

The requirements that shaped the choice:

- The deployed thing must be the *same* system, not a reduced one. The Deployments, the readiness
  and liveness probes, the resource requests and limits, a rolling update, a consumer group
  rebalancing after a pod is killed — those are the demonstration. A platform that cannot run them
  is not cheaper, it is a different product.
- It must be up when someone clicks. Not a cold start, not a reclaimed instance.
- Roughly €10/month, all in, for something that will sit idle between job searches.
- It must not be the same machine as Sprint 2's dev VPS. That box exists to be crashed — chaos
  testing, crash loops, multi-replica load tests. The demo box exists to be boring.

### The sizing question, answered with the project's own measurements

The instinct was a 4 vCPU / 8 GB box. Only the 2 vCPU / 4 GB CX23 was orderable — Hetzner's
cost-optimized lines were capacity-constrained, and the lines that stayed in stock (CPX, CCX) had
been re-priced 2.2–2.7× in June 2026, putting them 3–8× over budget.

This did not need to be estimated, because the Phase 10 scaling demo had already measured this
exact stack under a harder cap than a CX23 imposes:

- The development machine's Docker Desktop VM is limited to **3.825 GiB — less memory than a CX23
  has** — and inside that limit the full 8-pod stack at 1 replica each (Postgres, Kafka, five
  services, frontend) stood up and ran. That is precisely the configuration the public demo runs.
- Instability appeared only at 9 pods, when Inventory Service was scaled to 3 replicas: memory at
  76–84%, pods crash-looping. The demo box does not do that; the dev box does.

So memory is not the risk. **CPU is.** The same report recorded CPU spiking to 270–406% under
contention on a machine with 8 vCPUs available. A CX23 has 2. Five JVMs and a Kafka broker starting
simultaneously will want more cores than exist, which makes cold start — not steady state — the
pressure point. Phase 10 also recorded the specific failure that pressure produces: Kafka's
readiness probe, an `exec` of `kafka-broker-api-versions.sh` that **starts a second JVM inside the
broker container on every single check**, began timing out under CPU contention and flapped the
broker Ready/NotReady, taking the Kafka Service's endpoints to zero. A probe whose own cost causes
the failure it is meant to detect is not a probe.

## Decision

**One Hetzner CX23 (2 vCPU / 4 GB / 40 GB, ~€5.99/month including the IPv4 address) running k3s,
always on, reached through a subdomain of `noelschneider.com` with DNS and TLS on Cloudflare. The
existing `infrastructure/kubernetes/` manifests apply to it, through a production overlay. Deploys
are manual `kubectl apply`.**

Five parts, each load-bearing:

1. **k3s, not managed Kubernetes and not a PaaS.** Everything a reader can observe about a
   Kubernetes deployment is byte-identical on k3s; the part a managed control plane buys — someone
   else running etcd and the API server — is the one part nobody can see. k3s also ships Traefik,
   which the routing below depends on.

2. **A dedicated box, separate from the dev VPS** (`dev-vs-demo-host-separation.md`). Different
   jobs, opposite requirements: the dev box is a CPX32 created per session and deleted after (Hetzner
   bills hourly up to a monthly cap, so a box that does not exist costs nothing), while the demo box
   is always on.

3. **Always on rather than spin-up-on-demand.** Hetzner bills a server until it is *deleted*, not
   until it is powered off, so "on demand" would buy a multi-minute cold boot for a visitor and save
   nothing. Snapshot-and-delete between job searches (~€0.30–1.15/month retention) is the lever that
   actually works, and it is a deliberate act, not a request-time behavior.

4. **Manual `kubectl apply`.** No deployment pipeline this sprint. Images *are* built in GitHub
   Actions and published to GHCR — the demo box is x86_64 and the development laptop is arm64, so
   cross-building is required either way, and a native x86 runner beats QEMU emulation by a wide
   margin. That is image building, not deployment automation, and it does not reopen the deferred
   CI/CD decision.

5. **A production overlay rather than edited base manifests**
   (`infrastructure/kubernetes/production/`). `kubectl apply -f infrastructure/kubernetes/` against
   local `kind` behaves exactly as it did before this ADR. The overlay adds three things the local
   flow must not have: a Traefik Ingress whose path allowlist is the demo's security boundary (the
   six Services become `ClusterIP`, so no NodePort can bypass it), a Postgres Secret generated at
   apply time instead of the committed dev password, and the CX23 tuning below.

### The CX23 tuning is part of the decision, not a follow-up

Accepting a 2-vCPU box means accepting four specific changes, all implemented:

- **Kafka's readiness probe is a TCP check on the broker's internal listener**, not an exec that
  spawns a JVM. This one is in the *base* manifests, not the overlay: it is a strict improvement
  locally too. The honest cost is that a TCP accept proves the listener is bound, not that the
  broker will answer a metadata request — a weaker signal, bought by removing the probe's own CPU
  cost.
- **Heaps are capped explicitly.** `-XX:MaxRAMPercentage=60` on the five services (384 MiB of a
  640 MiB limit, leaving real room for metaspace, code cache, thread stacks and direct buffers) and
  `-Xmx512m` for Kafka, whose image otherwise takes a flat 1 GB regardless of its container limit.
- **A `startupProbe` on every service and on Kafka.** While it runs, liveness and readiness are not
  evaluated at all, so a slow cold start cannot be read as a failure and cannot restart a pod that
  is merely waiting for a CPU slice. Up to five minutes to come up, then normal probes take over.
- **Scenario timeouts widened 3× in the `production` Spring profile only** — roughly the ratio of
  available cores — since the committed values were tuned against 8-vCPU hardware. Explicitly
  provisional: a wider timeout that hides orders which never finish would be worse than the false
  failure it replaces, so the deployment session must confirm the high-volume scenario *completes*
  on the box, not merely that it stops reporting a timeout.

## Alternatives considered

**Managed Kubernetes (DOKS, Civo, Vultr, GKE)** — $43–90/month against ~€6. The premium buys a
managed control plane, which is genuinely valuable with an on-call rotation and worth close to
nothing here: the control plane is the one part of a Kubernetes story a reader cannot observe. The
manifests do not change.

**EKS specifically** — the same trade with a worse number: **$0.10/hour ($73/month) for the control
plane alone**, before any node, load balancer, or NAT gateway; realistically $150–190/month for this
workload, ~15× the chosen option. Two specifics make it worse for a portfolio box: extended version
support jumps to $0.60/hour ($438/month) once a cluster falls more than 14 months behind, which is
exactly what happens to a machine nobody actively maintains, and its per-resource billing has no
simple monthly cap of the kind Hetzner provides. EKS's value here is résumé keyword recognition, not
capability — and it stays available later, since the same manifests apply to a short-lived EKS
cluster torn down the same day.

**PaaS (Render / Railway / Fly.io)** — $25–175/month depending on platform, and the honest problem
is not price. Railway and Fly can run the real topology: five services, a real KRaft broker with a
volume, a real Postgres, real events. What does not survive is Kubernetes — no Deployments, no
probes as the platform understands them, no HorizontalPodAutoscaler, no "kill a pod and watch the
consumer group rebalance." ADR-007 argues Kubernetes earned its place through exactly those three
things, and Sprint 2's autoscaler goal would have nowhere to live. Render's free tier additionally
spins down after 15 minutes and takes ~1 minute to wake, which for a shared link means a visitor
watching a blank page — and 512 MB / 0.1 CPU per instance cannot host five JVMs and a broker anyway.

**Oracle Cloud Always Free** — €0, and 2 OCPU / 12 GB ARM would fit the workload comfortably. Ruled
out on reliability: Oracle halved the Always Free Ampere allowance on 15 June 2026 with no
announcement (users found out when instances were shut down), A1 capacity is frequently unavailable
to free accounts, and idle instances can be reclaimed. For a machine whose entire job is being up
when a stranger clicks a link, unannounced reclamation is the worst available failure mode.
Acceptable as a second mirror; not as the only home of a résumé URL.

**A bigger Hetzner box (CX33/CX43) or a non-Hetzner VPS (Netcup, Contabo, OVH)** — the larger CX
plans were simply not orderable, and the cheap non-Hetzner providers (Netcup VPS 1000 at €6.53 for
6 vCPU / 8 GB is genuinely attractive on specs) do not bill hourly and impose contract minimums,
which breaks the snapshot-and-delete lever. Revisitable: rescaling upward, or adding a second CX23
as a k3s **agent node** for another €5.99, is an incremental step rather than a migration — and two
nodes would make the demo a genuine multi-node cluster, which is a better story than the single node
it replaces.

**Authentication on `/demo`** — rejected on purpose, and worth recording because it looks like the
obvious answer. The scenarios *are* the product; putting a password in front of them defeats the
demo. The routing allowlist achieves the same protection where it matters (the two endpoints that
can wedge the system indefinitely are simply not routed) at no cost in demo value.

## Consequences and tradeoffs

- **A third supported way to run the system**, alongside local Compose and local `kind`. Rule 14
  applies: `README.md` and `infrastructure/kubernetes/production/README.md` must stay accurate as
  this evolves.
- **The ingress allowlist is now a real security boundary, and it is maintenance.** Adding a
  frontend route or a new browser-facing endpoint means adding it to
  `infrastructure/kubernetes/production/common/ingress.yaml`, or it 404s in production while working
  locally. That is the price of "anything not listed is unreachable" being a checkable property.
- **The demo is a shared public sandbox.** Anyone can run scenarios, including concurrently; state
  may reset under a visitor. W4's idle auto-reset (15 minutes, production profile only) exists to
  keep an abandoned wedged state from being the next visitor's first impression.
- **A firewall requirement crosses into the provisioning task**: the box must block the NodePort
  range 30000–32767. The overlay's `ClusterIP` patch and the firewall are two independent defenses
  of the same property, and only one of them lives in this repo.
- **2 vCPU is a real ceiling, and it is the interesting part.** Measuring this stack into a 4 GB /
  2 vCPU box — and finding that a readiness probe was spending CPU to discover it had no CPU — is a
  better answer to "tell me about a performance problem you diagnosed" than provisioning around it
  would have been. If the ceiling turns out to bind, the second CX23 is €5.99 away.
