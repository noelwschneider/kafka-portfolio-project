# Dev Box vs. Demo Box — Should They Be the Same Machine?

Sprint 2 runs two infrastructure tasks that both end with "a Hetzner VPS." This document explains
what each of those machines is actually for, why the instinct to combine them is reasonable but
wrong here, and what each option costs. It also states the fixed-vs-dynamic cost picture and the
prevention plan for the combined infrastructure bill.

Companion documents: `deployment-platform-options.md` (platform comparison and pricing) and
`deployment-code-changes-briefing.md` (the repo changes deployment requires).

**Decision: two separate Hetzner servers in the same Hetzner project.**

---

## 1. The two machines do opposite jobs

They sound like the same thing — "a Linux box running this project's Kubernetes stack" — and at the
level of installed software they are. What differs is the job.

**The dev box** (`docs/planning/sprint-2/vps-agent-briefing.md`) exists because an 8 GB MacBook with
Docker Desktop capped at ~3.8 GB cannot run this project at 3 replicas per service. Its job is to
have enough headroom that things *can* break under load and be observed breaking. Concretely, the two
Sprint 2 goals waiting on it are:

- the **autoscaler** demo, which needs to watch pods actually scale up under load, and
- the **bug hunt**, which is specifically hunting concurrency bugs that get *worse* at higher replica
  counts (a known race went from 3/60 orders affected at 1 replica to 34/60 at 2).

So its normal working day involves deliberately induced chaos, crash loops, `kubectl delete pod`,
paused consumers, poison messages, and clusters being torn down and rebuilt. It is also the machine
an agent session SSHes into and runs unvetted commands on.

**The demo box** (this spike) exists so a link can be shared with a recruiter. Its job is the exact
opposite: be up, be boring, be in a known-good state when a stranger clicks it at 11pm on a Sunday,
and never show anyone a crash loop that wasn't a scenario they triggered on purpose.

## 2. The four concrete conflicts

These are the reasons "just use one box" doesn't work, in descending order of how much they'd
actually bite:

**1. Chaos is the dev box's normal state.** The dev workload is *literally* the failure scenarios.
If a recruiter opens the link while a bug-hunt session is mid-run at 3 replicas with a paused
consumer and a wedged Kafka, they see a broken system and conclude the project is broken. There is no
way to schedule around this, because you don't know when they'll click.

**2. Public exposure.** The demo box must accept traffic from the whole internet on 443. The dev box
should accept nothing from the whole internet — SSH from known keys and nothing else, ideally behind
Tailscale or a firewall allowlist (the pre-sprint notes already anticipated a VPN layer for it).
Merging them means the development environment — with your SSH agent forwarding, your API tokens,
your in-progress code, and an unauthenticated demo API — inherits the public attack surface. That is
a real downgrade, not a theoretical one.

**3. They want opposite cost-control strategies.** You've chosen always-on for the demo — it's
useless if it's off when someone clicks. The dev box is the opposite: it's the one worth deleting
between work sessions, because at €16.49/month it's the more expensive of the two and it's idle
whenever you aren't working. One box can't be both permanently-on and freely deletable.

**4. Resource contention.** The measured footprint is 2.4 GiB of requests at 1 replica each. A dev
session at 3 replicas per service pushes toward 4.5–5 GiB plus its own control plane. Running a
second, separate copy of the stack for the demo on the same box means roughly doubling that — you'd
need the 16 GB CX43 minimum, and the two workloads would still fight for CPU during a load test,
which corrupts exactly the measurements the dev box exists to produce.

## 3. Options

| | Setup | Monthly | Demo stays clean? | Dev box stays private? | Complexity |
| --- | --- | --- | --- | --- | --- |
| **A. Two boxes (recommended)** | CX33 demo (always-on) + CX43 dev (deleted when idle) | **€25.48 both running; €8.99 with dev deleted** | Yes | Yes | Lowest — each box has one job |
| **B. One box, two namespaces** | CX43, one k3s cluster, `demo` + `dev` namespaces | €16.49 | No | No | Deceptively high |
| **C. One box, two clusters** | CX43, two k3s/kind clusters side by side | €16.49 | Partly | No | Highest |
| **D. Demo box only; dev stays local** | CX33 demo, no dev box | €8.99 | Yes | N/A | Lowest, but doesn't solve the dev problem |

**Why B is worse than it looks.** Namespaces separate names, not resources. A namespace gives you no
protection from the dev workload eating the CPU, and none at all from the failure modes that matter
here: `kind delete cluster`, a node running out of memory and the kubelet evicting pods (it does not
care which namespace they're in), or a `docker system prune` during cleanup. You'd be relying on
discipline in exactly the environment whose purpose is to be treated roughly. You'd also still have
one public IP serving both, which means conflict #2 stands.

**C** fixes the resource-and-blast-radius part (two real clusters, separate `kind`/k3s instances)
but keeps the public-exposure problem, needs the bigger box anyway, and adds port/ingress juggling
between two clusters on one IP. At that point you're doing more work than option A for €9 less.

**D** is worth naming because it's the cheapest honest option: if the dev box turns out not to be
worth it after trying it, the demo box stands alone at €8.99/month and nothing about it changes. The
two decisions are independent — that's a feature of option A.

## 4. Recommendation and what it costs

**Two servers in one Hetzner project:**

| Server | Plan | Spec | Price | Lifecycle |
| --- | --- | --- | --- | --- |
| `demo` | CX33 | 4 vCPU / 8 GB / 80 GB | €8.49 + €0.50 IPv4 = **€8.99/mo** | Always on |
| `dev` | CX43 | 8 vCPU / 16 GB / 160 GB | €15.99 + €0.50 = **€16.49/mo** | Created when working, snapshot-and-deleted when not |

Combined ceiling if both run all month: **€25.48/month (~$30)**. Realistic combined cost with the dev
box deleted between sessions: **€9–18/month**, plus ~€1/month if a dev-box snapshot is retained.

The demo box is the cheap half. That's the part worth internalizing: separating them costs you the
€8.99 demo box, and it's the *smaller* line item.

Two operational notes for whoever provisions:

- **Powering a Hetzner server off does not stop billing.** Hetzner bills a server until it is
  deleted, regardless of power state. The dev box's "pause" procedure is therefore snapshot → delete
  → restore-from-snapshot later, not shutdown. This needs to be in the dev box's setup doc, or the
  cost-control plan silently doesn't work.
- **The dev briefing's plan name is wrong at current prices.** Both
  `docs/planning/sprint-2/vps-agent-briefing.md` and `~/Documents/local-vs-cloud-dev-infra.md`
  specify "CX33 — 16 GB / 4 vCPU / ~€24/month." Today CX33 is 4 vCPU / **8 GB** at €8.49; the 16 GB
  shared-vCPU plan is **CX43** (8 vCPU / 16 GB) at €15.99. Provisioning "CX33" as written gets half
  the intended RAM — which is the exact ceiling that task exists to escape.

## 5. Cost structure: what is fixed, what could move, and how it's prevented

You asked for explicit detail on anything with the potential to balloon. The short answer is that
this stack has no usage-metered component — the structural reason a surprise bill isn't possible here
is that Hetzner bills per resource that *exists*, with a **monthly price cap per server**, and there
is no autoscaling, no per-request pricing, and no managed service that meters anything.

**Genuinely fixed:**

- Server prices (hourly billing, capped at the monthly figures above).
- IPv4: €0.50/server/month.
- Backups, if enabled: exactly +20% of the server price. Optional; recommend leaving off for the demo
  box, since it can be rebuilt from the repo.
- Domain: ~$10–15/year. Buy at a registrar that sells at cost with flat renewals (Cloudflare
  Registrar) rather than one with a cheap first year and a 3× renewal.
- Cloudflare: $0 on the free plan for DNS, TLS, proxying, and Tunnel.

**Variable in principle, with the real numbers:**

| Item | Rate | Realistic exposure | Prevention |
| --- | --- | --- | --- |
| Traffic overage | 20 TB/month included per server, then €1/TB (EU/US) | Serving a few-hundred-KB frontend, you would need ~50 million page loads/month to exceed 20 TB. Effectively unreachable. | Cloudflare proxy caches static assets, so most bytes never touch the box. Traffic is visible in the Hetzner console. |
| Snapshots | €0.0143/GB/month, compressed | 80 GB disk → **€1.15/month absolute maximum**, usually far less | Keep one snapshot per box; delete old ones during the monthly audit |
| Volumes | €0.0572/GB/month | €0 unless explicitly created — the k3s PVCs use the server's own disk | Don't create any; audit catches strays |
| Rescaling a server | Re-prices permanently at current rates | A rescale would move the box off any grandfathered price, and Hetzner raised CPX/CCX 2.2–2.7× in June 2026 | Size deliberately now; treat "resize" as a decision, not a convenience |

**The actual overrun risk is forgetting things exist, not metering.** The realistic bad outcome is a
dev box left running for four months (~€66) or an orphaned snapshot/volume/floating IP quietly
billing. Prevention plan:

1. **Monthly two-minute audit** — `hcloud server list`, `hcloud image list --type snapshot`,
   `hcloud volume list`, `hcloud primary-ip list`. Anything you don't recognise, delete.
2. **Check the Hetzner console's usage page monthly** for the running month-to-date total; set a
   recurring calendar reminder rather than assuming an email alert exists.
3. **A `dev-up` / `dev-down` script** as part of the dev-box deliverable, where `dev-down` snapshots
   and deletes rather than shutting down — otherwise the pause procedure doesn't actually save money.
4. **A Cloudflare rate-limiting rule** on the demo box's mutating endpoints. This is about protecting
   the box's CPU and disk, not the bill — but it's the same one-time setup.
5. **Watch disk growth on the demo box.** Scenario runs, timeline entries, and the event projection
   are retained deliberately and never pruned. This is a slow burn against 80 GB, not a cost event,
   but it's the one thing on the demo box that grows without bound.

## 6. Still open

- **Backups on the demo box.** Recommended off: +20% of the server price for a machine that rebuilds
  from the repo in minutes.
- **Whether the dev box happens this sprint** is independent of the demo deployment. Option D — demo
  box only — remains available if the dev box turns out not to earn its cost.
