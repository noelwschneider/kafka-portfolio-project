# 9.1 — The platform decision

[← Chapter 9](README.md) · [Next: The production overlay →](2-the-production-overlay.md)

ADR-010. The project needs a URL a stranger can click, and this is how that decision was made.

---

## What changed

[ADR-007](../01-design-contract/4-sequencing-and-deferrals.md) put Kubernetes in the project and kept
it deliberately optional — local `kind`, plain YAML, never a prerequisite. **That decision is
unchanged.** What changed is that the project now needs

> a **URL a stranger can click** — a recruiter opening a link, running a failure scenario, and
> watching real events cross five services — which local `kind` cannot provide.

## The requirements, and the one that constrains everything

> - The deployed thing must be the *same* system, not a reduced one. The Deployments, the readiness
>   and liveness probes, the resource requests and limits, a rolling update, a consumer group
>   rebalancing after a pod is killed — **those are the demonstration.** A platform that cannot run
>   them is not cheaper, it is a different product.
> - It must be up when someone clicks. Not a cold start, not a reclaimed instance.
> - Roughly €10/month, all in, for something that will sit idle between job searches.
> - It must not be the same machine as Sprint 2's dev VPS. That box exists to be crashed — chaos
>   testing, crash loops, multi-replica load tests. **The demo box exists to be boring.**

The first requirement eliminates the whole category of cheap options. A PaaS running five containers
with no Kubernetes objects, no probes, no rolling update, and no rebalance would host the
*application* and not the *demonstration* — and the demonstration is the product.

*"Not cheaper, a different product"* is the sentence to remember. Cost comparisons between platforms
are only meaningful when both deliver the thing you need.

The last requirement is a nice piece of operational judgment: **two boxes with opposite jobs.** One is
for breaking things; one must never break. Sharing them would mean every load test risks the public
demo.

---

## Sizing, from the project's own measurements

This is the best part of the ADR, because the answer already existed.

> The instinct was a 4 vCPU / 8 GB box. Only the 2 vCPU / 4 GB CX23 was orderable — Hetzner's
> cost-optimized lines were capacity-constrained, and the lines that stayed in stock had been
> re-priced 2.2–2.7× in June 2026, putting them 3–8× over budget.

A constraint arrived from outside. The response was not to guess whether a smaller box would work:

> This did not need to be estimated, because the **Phase 10 scaling demo had already measured this
> exact stack under a harder cap than a CX23 imposes**:
>
> - The development machine's Docker Desktop VM is limited to **3.825 GiB — less memory than a CX23
>   has** — and inside that limit the full 8-pod stack at 1 replica each stood up and ran. That is
>   precisely the configuration the public demo runs.
> - Instability appeared only at 9 pods, when Inventory Service was scaled to 3 replicas. **The demo
>   box does not do that; the dev box does.**

[Chapter 8](../08-observability-and-scaling/3-scaling.md) noted that Phase 10's most valuable output
was a measurement rather than a graph. This is where it pays: the sizing question is answered with
*"we have already run this exact stack under a tighter memory cap"* rather than an estimate.

### And the risk is not where the instinct says

> So memory is not the risk. **CPU is.** The same report recorded CPU spiking to 270–406% under
> contention on a machine with 8 vCPUs available. A CX23 has 2. Five JVMs and a Kafka broker starting
> simultaneously will want more cores than exist, which makes **cold start — not steady state — the
> pressure point.**

Two reframings, both correct and both non-obvious:

**CPU, not memory.** The instinct for "is this box big enough" is memory, and the measurements say
otherwise.

**Cold start, not steady state.** Five JVMs at rest fit comfortably. Five JVMs *starting at once* want
far more CPU than exist. The dangerous moment is a deploy or a reboot — which is exactly the moment
that later produced [section 4](4-the-outage.md)'s outage.

### The probe that causes what it detects

> Phase 10 also recorded the specific failure that pressure produces: Kafka's readiness probe, an
> `exec` of `kafka-broker-api-versions.sh` that **starts a second JVM inside the broker container on
> every single check**, began timing out under CPU contention and flapped the broker Ready/NotReady,
> taking the Kafka Service's endpoints to zero. **A probe whose own cost causes the failure it is
> meant to detect is not a probe.**

The check from [Chapter 7](../07-containers-and-kubernetes/1-containers-and-compose.md) —
deliberately chosen because it proves the broker actually answers, rather than merely that the process
is up. Correct, and its *cost* is a JVM start every five seconds.

Under CPU contention it times out, marks the broker not-ready, empties the Service endpoints, and
every client loses the broker — because the health check could not get a CPU slice.

**A health check is a load.** On a machine with headroom that is invisible; on a constrained one, the
observer changes the outcome.

---

## The decision, in five parts

> **One Hetzner CX23 (2 vCPU / 4 GB / 40 GB, ~€5.99/month) running k3s, always on, reached through a
> subdomain with DNS and TLS on Cloudflare. The existing manifests apply to it, through a production
> overlay. Deploys are manual `kubectl apply`.**

**1. k3s, not managed Kubernetes and not a PaaS.**

> Everything a reader can observe about a Kubernetes deployment is byte-identical on k3s; the part a
> managed control plane buys — someone else running etcd and the API server — is **the one part nobody
> can see.**

k3s is a certified Kubernetes distribution in a single binary. The same objects, the same API, the
same `kubectl`. It also ships **Traefik**, which the routing in
[section 2](2-the-production-overlay.md) depends on.

**2. A dedicated box, separate from the dev VPS.** The dev box is a CPX32 created per session and
deleted after — *"Hetzner bills hourly up to a monthly cap, so a box that does not exist costs
nothing."* The demo box is always on.

**3. Always on rather than spin-up-on-demand**, for a reason that inverts the intuition:

> Hetzner bills a server until it is *deleted*, not until it is powered off, so "on demand" would buy
> a multi-minute cold boot for a visitor and **save nothing**.

The cost lever that actually works is snapshot-and-delete between job searches (~€0.30–1.15/month) —
*"a deliberate act, not a request-time behavior."*

**4. Manual `kubectl apply`.** No deployment pipeline. Images *are* built in GitHub Actions and
published to GHCR, and the ADR is careful that this is not a contradiction:

> the demo box is x86_64 and the development laptop is arm64, so cross-building is required either
> way, and a native x86 runner beats QEMU emulation by a wide margin. **That is image building, not
> deployment automation**, and it does not reopen the deferred CI/CD decision.

The workflow is `workflow_dispatch` only, and the reasoning holds the line:

> a build that fires on every push would produce images nobody asked for and would make the mutable
> `latest` tag move under a box that is meant to be **boringly stable**. Publishing is an explicit
> act here, matching how the deploy itself works.

Every run also pushes an immutable commit-SHA tag, *"which is what you want to pin once the first
deploy is done."* Mutable `latest` for convenience, immutable SHA for reproducibility.

**5. A production overlay rather than edited base manifests** — [section 2](2-the-production-overlay.md).

---

## The rejected options

Worth having ready, because "why not just use a PaaS?" is the obvious question.

**Managed Kubernetes (EKS/GKE).** Rejected on cost, and on the observation above: the part it buys is
the part nobody can see.

**A PaaS** (Render, Fly, Railway). Cheaper and simpler, and it cannot run the demonstration — no probe
semantics to show, no rolling update to watch, no rebalance when a pod dies.

**Serverless / scale-to-zero.** Fails the "up when someone clicks" requirement, and a Kafka consumer
that scales to zero is not a Kafka consumer.

**Docker Compose on a VPS.** Would run the application on this exact box. Rejected because Kubernetes
is an explicit portfolio goal and half the demonstration is Kubernetes behavior — the same reasoning
[ADR-007](../01-design-contract/4-sequencing-and-deferrals.md) used to reject Compose-only in the first
place.

---

[← Chapter 9](README.md) · [Next: The production overlay →](2-the-production-overlay.md)
