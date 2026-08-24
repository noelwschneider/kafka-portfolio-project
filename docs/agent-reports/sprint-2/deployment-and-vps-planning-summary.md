# Deployment & VPS Infrastructure — Planning Summary

Written for the Sprint 2 planning agent. Self-contained: it states the settled decisions, the costs,
the work each decision creates, and how the two infrastructure tasks now relate. Source detail lives
in `deployment-platform-options.md`, `deployment-platform-revision.md`,
`dev-vs-demo-host-separation.md`, and `deployment-code-changes-briefing.md`.

Two Sprint 2 goals produce servers: **goal 4 (Deployment Spike)** and the **VPS / remote development
task**. Hetzner's capacity shortage on cost-optimized plans changed the shape of both. The headline
is that the total infrastructure bill went *down*, not up — but the dev box changes character.

---

## 1. Settled decisions

| | Decision |
| --- | --- |
| Platform category | One VPS running self-managed **k3s**, applying the existing `infrastructure/kubernetes/` manifests. Not managed Kubernetes, not a PaaS, not AWS |
| Demo box | Hetzner **CX23** (2 vCPU / 4 GB / 40 GB), **€5.99/month** including IPv4 |
| Availability | **Always on**, custom domain, HTTPS |
| Deployment method | **Manual** `kubectl apply`. No CD pipeline this sprint |
| Host separation | Demo box and dev box are **separate servers** |
| `/demo` endpoints | Stay **publicly reachable**; protected by ingress routing, not authentication |
| Dev box | Hetzner **CPX32** (4 vCPU / 8 GB), **created and destroyed per session** — see §3 |

Rejected, with reasons on file: managed Kubernetes (~$43–90/month for a control plane nobody can
observe), AWS EKS ($150–190/month, $73 of it the control plane alone), Render/Railway/Fly (no
Kubernetes at all, which deletes the autoscaler goal's home), Oracle Always Free (unannounced
capacity cuts and instance reclamation — the wrong failure mode for a résumé URL).

## 2. What changed, and why the budget improved

Hetzner's 15 June 2026 price change hit product lines unevenly: CX and CAX (the cheap, shared lines)
rose ~1.3–1.4×, while CPX rose 2.5–2.7× and CCX ~2.7×. The cheap lines are also the capacity-
constrained ones. Currently orderable on this account: **CX23 only** among cost-optimized plans; the
CPX and CCX lines are fully available at their raised prices.

That combination broke the original plan (a €8.49 CX33) but did not break the budget, because the two
boxes respond to it differently:

- **The demo box is always-on, so its monthly price is what matters.** It moves down to the CX23 at
  €5.99/month — cheaper than the original plan.
- **The dev box is intermittent, so its hourly price is what matters.** Hetzner bills hourly up to a
  monthly cap, which makes even the re-priced CPX plans cheap when used in sessions.

## 3. The dev box: same goals, different shape

### The original spec was over-provisioned relative to its own goals

`docs/planning/sprint-2/vps-agent-briefing.md` specifies a 16 GB / 4 vCPU box sized for "5 backend
services + frontend, at least 3 replicas each" — 18 pods. The two goals driving that requirement are
narrower than the spec:

- **The autoscaler goal** needs to watch an HPA scale *one* service up under load.
- **The bug hunt** targets concurrency bugs that worsen with replica count — and the report it cites
  found the known race went from 3/60 orders affected at 1 replica to **34/60 at 2**. The
  amplification is already dramatic at two replicas of a single service.

Neither goal requires every service at three replicas simultaneously. Sizing against what they
actually do:

| Configuration | Pods | Estimated real memory |
| --- | --- | --- |
| Baseline, 1 replica each | 8 | ~3.5 GiB |
| One service at 3 replicas | 10 | ~4.2 GiB |
| One service at 5 replicas (autoscaler headroom) | 12 | ~4.9 GiB |
| Every service at 3 replicas (the original spec) | 18 | ~7 GiB+ |

An **8 GB box covers both goals with headroom**. 16 GB is only required for the "3 replicas
everywhere" framing, which nothing in Sprint 2 actually needs.

### Hourly billing makes the plan price nearly irrelevant

Approximate hourly rates (monthly cap ÷ 730 — the provider's published hourly figure should be
confirmed at provisioning time):

| Plan | Spec | Monthly cap | ~Hourly | One 8-hour session | Ten working days |
| --- | --- | --- | --- | --- | --- |
| **CPX32** | 4 vCPU / 8 GB | €35.49 | **€0.049** | **€0.39** | **€3.89** |
| CPX42 | 8 vCPU / 16 GB | €69.49 | €0.095 | €0.76 | €7.62 |
| CCX23 | 4 dedicated vCPU / 16 GB | €85.99 | €0.118 | €0.94 | €9.42 |

The June price increase moved a full working day on a CPX42 from roughly €0.28 to €0.76. For a box
used in sessions, that is not a budget event. **The capacity crisis that reshaped the demo box barely
touches the dev box.**

**Recommendation: CPX32, provisioned per session.** CPX42 is worth the extra €0.37 per day only if
the "3 replicas everywhere" configuration is genuinely wanted. CCX23's dedicated vCPUs are worth
considering if the bug hunt's measurements prove too noisy on shared cores — though contention
arguably *helps* reproduce races, so this is unlikely to be needed.

### What this changes about the VPS task's deliverables

The original briefing frames the dev box as a persistent day-to-day environment. At €35–69/month
standing, that framing is no longer affordable; at €0.05/hour it is excellent value **provided
recreating it is cheap**. That inverts the priority order of the task's deliverables:

1. **Provisioning must be automated, not documented.** A cloud-init script or `hcloud` sequence that
   goes from nothing to a working k3s box is now the *primary* deliverable. A hand-followed runbook
   makes every session cost an hour of setup, which defeats the model.
2. **`dev-down` must snapshot and delete, not shut down.** Powering a Hetzner server off does not
   stop billing — only deletion does. If the script only stops the server, the entire cost model
   silently fails. This is the single most important detail to get right in that task.
3. **Snapshot retention is negligible** — billed on used, compressed space, so a dev box with images
   and the repo is roughly €0.10–0.30/month while deleted. Caution to verify at provisioning: a
   snapshot generally cannot be restored onto a plan with a *smaller* disk, so take the snapshot from
   the smallest plan the workflow may restore onto.
4. **The connection model should assume ephemerality.** VS Code Remote-SSH against a box whose IP
   changes each session needs a host alias or a floating IP; the task should pick one deliberately
   rather than discovering it on the second session.
5. **The plan name in the briefing is wrong and must not be provisioned as written.** Both
   `vps-agent-briefing.md` and `~/Documents/local-vs-cloud-dev-infra.md` specify "CX33 — 16 GB /
   4 vCPU / ~€24/month." CX33 is 4 vCPU / **8 GB** at €8.49 and is **not currently orderable**. Neither
   the spec nor the price nor the availability matches.

### Sequencing

The VPS task is **unblocked and can start immediately** — CPX plans are available now, and it depends
on nothing else in the sprint. The demo deployment is gated behind the security pass and the code
changes in §4. These two tasks do not contend for anything.

## 4. Work the deployment decision creates

All of it is specified in `deployment-code-changes-briefing.md`, which is written as a standalone
handoff for the code agent. Summary for planning purposes:

| Item | Scope | Blocking deploy? |
| --- | --- | --- |
| W1 | Ingress with per-service path prefixes and a path allowlist | Yes |
| W2 | Frontend production build against relative URLs | Yes |
| W3 | SPA fallback in the frontend container (deep links currently 404) | Yes |
| W4 | Idle auto-reset in Scenario Service | Yes — demo integrity |
| W5 | Configurable CORS origins | No — same-origin routing makes it moot |
| W6 | x86 image build and publish path | Yes |
| W7 | Postgres password out of the repo | Coordinate with security task |
| W8 | Docs for the third way to run the system | Yes |
| **T1–T4** | **2-vCPU tuning: Kafka readiness probe, JVM heap caps, probe timings, scenario-timeout re-check** | **Yes** |

**T1–T4 are new**, created by the CX23's 2 vCPU limit rather than by the deployment itself. The
project's own `phase-10-scaling-demo.md` establishes both that the 8-pod stack fits in under 4 GiB
(it already runs in a 3.825 GiB Docker Desktop VM) and that CPU is the binding constraint — it
recorded CPU spiking to 270–406% under contention, and identified Kafka's readiness probe
(`kafka-broker-api-versions.sh`, which starts its own JVM on every probe) as what flapped the broker
Ready/NotReady. Replacing that probe with a TCP socket check is the highest-value item of the four.

**These tuning items benefit both boxes.** Reducing per-service resident memory by 25% takes fifteen
JVMs from ~5.25 GiB to ~3.9 GiB, which raises the replica ceiling the dev box exists to reach. Work
done for the €5.99 demo box makes the €0.05/hour dev box go further.

## 5. Budget

| Line | Cost |
| --- | --- |
| Demo box (CX23, always on) | **€5.99/month** |
| Dev box (CPX32, ~3 sessions/month) | **~€1.20–4/month** |
| Dev box snapshot while deleted | ~€0.10–0.30/month |
| Domain | ~€1–12/year |
| Cloudflare (DNS, TLS, proxy, rate limiting) | €0 |
| **Realistic total** | **~€7–10/month** |

Nothing here is usage-metered. Hetzner bills per resource that exists, with a per-server monthly price
cap; there is no autoscaling, per-request pricing, or managed service that can spike. Included traffic
is 20 TB per server against a frontend measured in hundreds of kilobytes.

The realistic overrun risk is a forgotten dev box, not metering: a CPX32 left running for a month is
€35.49 instead of €4. Controls: `dev-down` must delete rather than stop; a monthly `hcloud server
list` / `hcloud image list --type snapshot` audit; a calendar reminder to check the console's
month-to-date usage.

## 6. Risks and watch items

- **Demo box CPU.** 2 vCPU is the plan's weakest point. If T1–T4 do not settle it, adding a second
  CX23 as a k3s agent node costs €5.99/month, brings the cluster to 4 vCPU / 8 GB, and is an
  incremental step rather than a migration — it also turns the demo into a genuine multi-node cluster.
  Total would remain at or under the original €10.99 target.
- **High-volume scenario timeouts.** `order-poll-timeout-ms` (20 s) and
  `high-volume-order-watch-timeout-ms` (60 s) were tuned on 8-core hardware. On 2 vCPUs the 60-order
  burst may exceed them and report a false failure. Verify the work is genuinely completing before
  widening anything.
- **Hetzner capacity.** CX33/CAX21 stockouts have been intermittent rather than permanent, so a
  larger demo box may become orderable later; rescaling re-prices at current rates, and there is no
  grandfathered price to lose. A small poll against the server-types endpoint is cheaper than watching
  the console. Not worth delaying the deployment for.
- **If Hetzner capacity closes entirely**, Netcup's VPS 1000 G11 (6 vCPU / 8 GB, €6.53/month net) is
  the cheapest route to comfortable specs and removes the CPU concern — at the cost of a contract
  minimum and no hourly billing, which would end the dev box's per-session cost model.
- **Architecture is consistent.** CX23 and CPX are both x86, so one set of `linux/amd64` images serves
  both boxes. The development laptop is arm64, so images must be cross-built; GitHub Actions on public
  repos gives native x86 runners for free and keeps deployment manual.

## 7. Open items for planning

- **Confirm the dev box size**: CPX32 (8 GB, covers both Sprint 2 goals) or CPX42 (16 GB, matches the
  original "3 replicas everywhere" framing at +€0.37 per working day).
- **Whether the dev box is still wanted this sprint.** With per-session billing it is ~€1–4/month
  rather than a subscription, which weakens the case for deferring — but it remains independent of
  the deployment and can be dropped without affecting it.
- **Domain name and registrar**, needed before TLS.
- **Idle auto-reset threshold** for W4; 15 minutes is the suggested default.
