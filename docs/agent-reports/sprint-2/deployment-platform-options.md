# Deployment Spike — Platform Options and Pricing

Research output for Sprint 2 goal 4 (`docs/planning/sprint-2/deployment-agent-briefing.md`). Nothing
has been provisioned and no accounts have been created. The four decision points in the briefing are
settled in §7–§8; §9 lists what remains open.

Prices were verified against vendor pages or vendor-derived sources in August 2026 and are stated
per month excluding VAT unless noted. EUR→USD conversions use ~1.18. Where a number could not be
read from the vendor directly, it is labelled.

---

## 1. What actually has to run

Measured from `infrastructure/kubernetes/*.yaml`, at the committed 1 replica per Deployment:

| Component | CPU request | Memory request | Memory limit |
| --- | --- | --- | --- |
| PostgreSQL | 100m | 256Mi | 512Mi |
| Kafka (KRaft) | 250m | 512Mi | 1Gi |
| 5 × Spring Boot service | 750m total | 1600Mi total | 3200Mi total |
| Frontend (static) | 50m | 64Mi | 128Mi |
| **Total** | **1.15 vCPU** | **2.4 GiB** | **4.75 GiB** |

Add ~0.5 GiB and ~0.3 vCPU for a k3s control plane and system pods (a full kubeadm/kind node is
closer to 1 GiB). Each extra replica of a business service adds 320Mi request / 640Mi limit, so the
Sprint 2 autoscaler demo at 3 replicas of one service adds ~0.65 GiB.

**Sizing conclusion:** 8 GB / 4 vCPU comfortably runs the whole stack at 1 replica each plus a
3-replica scale demo of one service. 4 GB is too tight once system pods and JVM headroom are counted.
16 GB is what "3 replicas everywhere" wants.

Two PersistentVolumeClaims exist (Postgres 1Gi, Kafka 1Gi), so storage cost is negligible everywhere.

## 2. Work that is required regardless of which platform is chosen

These are repo changes, not platform choices — none of the options below is "deploy the existing
images unchanged." They are specified as work items in `deployment-code-changes-briefing.md`, which is
the handoff for that work; this section is the summary of what it found.

1. **CORS is hardcoded to localhost.** `services/common/src/main/java/com/orderfulfillment/common/WebConfig.java:14`
   allows only `http://localhost:*`. A public origin needs this to come from configuration.
2. **The frontend bakes backend URLs in at build time.** `frontend/.env.example` points the browser at
   `http://localhost:8081`–`8085` — five separate backend origins, contacted directly by the browser
   including the two SSE streams. A public deployment needs a production build against public URLs,
   which means either five public hostnames or an ingress doing path-based routing plus a frontend
   change. `infrastructure/kind-config.yaml` documents that these exact ports were deliberately
   preserved for the local demo, so this is a real (small) piece of work, not an oversight.
3. **No Ingress manifest exists.** Every Service is `NodePort` (30081–30085, 30173). Public HTTPS needs
   either an ingress controller (k3s ships Traefik) or a tunnel that maps hostnames to node ports.
4. **`/demo` endpoints are unauthenticated.** On a public URL, anyone can trigger the chaos scenarios
   and the demo reset. That may be acceptable — the project *is* a sandbox — but it should be a
   decision. See §7.
5. **Images need a registry** the target can pull from (GHCR is free for public repos) or a build on
   the box.

## 3. Option A — single VPS running k3s

One server, `k3s` (or `kind`) on it, the existing manifests applied unchanged.

| Plan | vCPU / RAM / disk | Price | Notes |
| --- | --- | --- | --- |
| Hetzner CX33 | 4 / 8 GB / 80 GB | €8.49 + €0.50 IPv4 = **€8.99 (~$10.60)** | recommended size |
| Hetzner CX43 | 8 / 16 GB / 160 GB | €15.99 + €0.50 = **€16.49 (~$19.50)** | if the public demo should run 3 replicas everywhere |
| Hetzner CAX31 (ARM) | 8 / 16 GB / 160 GB | €20.99 | more expensive than the x86 CX43 — no reason to take on arm64 image builds |
| DigitalOcean Droplet | 4 / 8 GB | **$48** | same shape, 4.5× the price |
| Vultr / Linode equivalent | 4 / 8 GB | ~$48 (Linode's current list price is sales-gated and unverified) | |

Included traffic on Hetzner is 20 TB, so egress is effectively free. Backups are +20% of the instance
price if enabled; snapshots are €0.0143/GB/month.

Two Hetzner-specific facts that matter:

- **Hetzner raised prices on 15 June 2026.** CX and CAX went up ~1.3–1.4×; CPX and CCX went up
  2.2–2.7× (CPX32 €13.99 → €35.49; CCX23 €31.49 → €85.99). Only the CX line is still cheap — do not
  reach for CPX or CCX out of habit. Existing servers are grandfathered; a rescale re-prices them.
- **Powering a server off does not stop billing.** Hetzner bills a server until it is deleted,
  regardless of power state. The only real "pause" is snapshot-then-delete (~€0.30–1.15/month for an
  80 GB disk's compressed snapshot), restoring when needed.

**Honesty check:** this runs the real architecture. Real Deployments, real probes, real rolling
updates, real HPA, real pod restarts, five services talking over real Kafka. The only thing it is not
is a *managed* control plane — which is the one part of a Kubernetes story a reviewer cannot see from
the outside anyway. Portable: the same manifests move to any other cluster unchanged. No lock-in.

## 4. Option B — managed Kubernetes

| Platform | Control plane | Node (4 vCPU / 8 GB) | Load balancer | Realistic monthly |
| --- | --- | --- | --- | --- |
| DigitalOcean DOKS | free (HA $40) | $48 basic | $12 | **$48–62** |
| Civo | free | $43.45 | $10.86 | **$43–55** (+$250 first-month credit, bandwidth free) |
| Vultr VKE | free (HA +$40) | ~$48 | ~$10 | **~$48–60** |
| GKE | first zonal cluster's management fee free, then $0.10/hr | Compute Engine rates | ~$18 | **~$60–90** |
| AWS EKS | **$0.10/hr = $73/month**, no free tier | t3.large ~$60 | ALB/NLB ~$16–20 | **$150–190** |

Notes: EKS's $73 buys nothing visible in the demo. Its extended-support tier is $0.60/hr ($438/month)
if a cluster is left on an old Kubernetes version for more than 14 months — a genuine trap on a
project that will not be actively maintained between job searches. GKE Autopilot bills per pod
request (~2.4 vCPU / 4.8 GiB here), which lands in the same range as a node-based cluster.

**Honesty check:** identical architecture fidelity to Option A, plus a managed control plane and a
real cloud load balancer. Costs 4–15× more. AWS is the only one with meaningful résumé keyword value,
and it is the most expensive and the easiest to leave running by accident.

## 5. Option C — PaaS (Render / Railway / Fly.io)

| Platform | Shape | Realistic monthly | Kubernetes? |
| --- | --- | --- | --- |
| Render | 5 web services + Kafka as a private service with a disk + managed Postgres. Workspace Hobby $0 / Pro $25. Instances: Free 512 MB, Starter $7 (512 MB), Standard $25 (2 GB), Pro $85 (4 GB). | **$60 (if 512 MB instances suffice) to ~$175** | none |
| Railway | 7 containers, per-second billing: $0.0000039/GB-s RAM (~$10/GB-month), $0.0000077/vCPU-s (~$20/vCPU-month), egress $0.05/GB. Hobby $5 (incl. $5 credit), Pro $20. | **~$25–45 always-on**; near the plan floor with scale-to-zero | none |
| Fly.io | Kafka `shared-cpu-1x` 2 GB $11.11, 5 services @ 1 GB $5.92 = $29.60, frontend 256 MB $2.02, Postgres ~$2–6, volumes $0.15/GB. Public free tier retired Oct 2024; new orgs get a 2-VM-hour / 7-day trial only. | **~$45–55 always-on**; auto-stop/start helps the HTTP services but Kafka and Postgres must stay up, so the floor is ~$15–20 | none |

**Honesty check — this is the option the briefing asked to be flagged.** Railway and Fly *can* run the
real topology: five separate services, a real KRaft Kafka with a volume, a real Postgres, real events.
That part survives. What does not survive is Kubernetes — no Deployments, no readiness/liveness
probes as the platform understands them, no HorizontalPodAutoscaler, no "kill a pod and watch the
consumer group rebalance." ADR-007 argues Kubernetes earned its place precisely through replicas,
restart behaviour and scaling; a PaaS deployment deletes the demonstration of all three, and Sprint 2
goal 6 (the autoscaler) would have nowhere to live. Render's free tier is additionally a poor fit for
a shareable link: free instances spin down after 15 minutes of inactivity and take ~1 minute to wake,
which for a six-service app means a recruiter watching a blank page.

Render's free tier also cannot host this at all in practice: 512 MB / 0.1 CPU per instance against
five JVMs and a broker, with 750 free instance-hours per month shared across the whole workspace.

## 6. Option D — Oracle Cloud always-free (excluded, but stated for completeness)

`~/Documents/local-vs-cloud-dev-infra.md` already ruled Oracle out for the dev box. It is worth
recording that the case got weaker since: Oracle **halved** the Always Free Ampere A1 allowance from
4 OCPU / 24 GB to 2 OCPU / 12 GB on 15 June 2026, with no announcement — users found out when
instances were shut down. Combined with the long-standing "out of capacity" provisioning problem and
idle-instance reclamation, this is not a platform to put a shareable portfolio link on. Excluded.

## 7. Decision

**A dedicated single VPS running k3s, always on, behind a custom domain, deployed manually.** It sits
on its own server, separate from the Sprint 2 dev box (`dev-vs-demo-host-separation.md`).

**The specific plan is settled in `deployment-platform-revision.md`, which is authoritative on
provider and size.** Hetzner's CX (Intel) line is subject to capacity shortages; the ARM CAX line and
the non-Hetzner fallbacks are compared there. The category decision below — one VPS, self-managed
k3s, not managed Kubernetes and not a PaaS — is unaffected by which of those is chosen.

Why this option:

- It is the only category that keeps the full architecture *and* Kubernetes honest at roughly $10/month.
  The existing manifests apply unchanged, the delta being the ingress/CORS/frontend-URL work in §2 —
  which every other option needs too.
- Zero lock-in: nothing in the deployment is provider-specific, which is exactly the "boring, portable
  stack" claim the project makes elsewhere.
- k3s ships Traefik, so HTTPS on a custom domain is Let's Encrypt plus DNS. Cloudflare Tunnel is a
  free alternative that needs no inbound ports open and keeps the Kubernetes API off the public
  internet entirely.

Budget the domain (~$10–15/year). Backups (+20%) are optional and not recommended for a machine that
rebuilds from the repo. The CX43 (16 GB, ~€16.49/month) is the upgrade path if the public demo should
itself run 3 replicas per service rather than scaling up on demand during the HPA scenario — a €7.50
question, changeable later.

### Why not managed Kubernetes, and specifically why not EKS

Managed Kubernetes (DOKS, Civo, Vultr, GKE) costs $43–90/month against this option's ~$10. What that
premium buys is a managed control plane: someone else runs etcd and the API server, and patches them.
That is genuinely valuable on a production system with an on-call rotation. It is worth close to
nothing here, because **the control plane is the one part of a Kubernetes story a reader cannot
observe.** What a reviewer can actually see — the Deployments, the readiness and liveness probes, the
resource requests and limits, the HorizontalPodAutoscaler, a rolling update, a consumer group
rebalancing after a pod is killed — is byte-identical on k3s. The manifests in
`infrastructure/kubernetes/` do not change.

EKS is the same trade with a worse number. Its floor is **$0.10/hour — $73/month — for the control
plane alone**, before a single node, load balancer, or NAT gateway. A realistic single-node EKS
cluster for this workload lands at **$150–190/month**: roughly 15× the recommended option, or about
$1,900/year, for a portfolio project that will sit idle between job searches. Two further specifics
make it worse for this particular use case:

- **Extended version support costs $0.60/hour ($438/month)** once a cluster falls more than 14 months
  behind on Kubernetes versions. A demo box nobody is actively maintaining is exactly the machine
  that drifts onto an old version and quietly starts billing 6× more.
- **It is the easiest of all the options to accidentally overspend on.** NAT gateways, load balancers,
  and EBS volumes bill independently of whether anyone is looking at the demo, and AWS has no simple
  per-resource monthly cap of the kind Hetzner provides.

The honest framing is that EKS's value here is résumé keyword recognition, not capability. That is a
real thing to want, and it can be had later without redoing any of this work: the same manifests
apply to an EKS cluster, so "deployed this to EKS" can be a time-boxed exercise done against a
short-lived cluster, torn down the same day, and written up — at a cost of a few dollars rather than
a standing $150/month subscription. Nothing in this decision forecloses that, which is the same
reasoning ADR-007 used when it chose local `kind` over a managed cloud cluster for v1.

## 8. The four decision points, as settled

**1. Platform category — decided: single VPS + k3s.** Rationale in §7; the full comparison is in
§3–§6, and the specific provider and plan in `deployment-platform-revision.md`. Not chosen: managed Kubernetes and EKS (§7), Render (spin-down plus no
Kubernetes), Railway and Fly (no Kubernetes at all, §5), Oracle (unreliable, §6).

**2. Always-on vs spin-up-on-demand — decided: always on.** At €8.99/month (~$127/year) the baseline
costs less than the complexity of the alternative. Worth recording why on-demand was never really
available: on Hetzner, powering a server off does not stop billing — only deletion does — so "on
demand" would mean snapshot-restore plus a full Kafka-and-five-JVMs cold boot, which is minutes of
blank screen for a visitor who has no reason to wait.

**3. Budget ceiling — the costs here are structurally fixed.** Roughly €6.50–11/month for the demo
box depending on the plan chosen, plus a ~$10–15/year domain, with no usage-metered component that can spike. The itemised fixed-vs-variable
breakdown and the prevention plan for the combined infrastructure bill (demo box plus dev box) are in
`dev-vs-demo-host-separation.md` §5.

**4. Relationship to the Sprint 2 VPS task — a separate server on the same provider.** The demo box's
job is to be up and boring for a stranger; the dev box's job is to run deliberately-induced chaos at
high replica counts. Full reasoning, options considered, and combined costs are in
`dev-vs-demo-host-separation.md`.

**Deployment method — manual `kubectl apply` for now.** CI/CD deployment is deferred; see §9.

**Flag for the parallel VPS task:** `docs/planning/sprint-2/vps-agent-briefing.md` and
`~/Documents/local-vs-cloud-dev-infra.md` both specify "Hetzner CX33 — 16 GB RAM / 4 vCPU / ~€24/month."
Current Hetzner pricing does not match that on either axis: CX33 is 4 vCPU / **8 GB** at **€8.49**,
and the 16 GB shared-vCPU plan is the CX43 (8 vCPU / 16 GB) at €15.99. The dev task should re-check
the plan name before provisioning, or it will get half the intended RAM. (The €24 figure is close to
the current CAX31 ARM 16 GB plan at €20.99, but there is no reason to prefer ARM here.)

## 9. Remaining open items

- **The public `/demo` surface.** Anyone with the link can trigger chaos scenarios and the demo reset.
  This is a demo-integrity problem rather than a security one — there is no data to steal, no auth to
  bypass, and nothing that escapes the box — but two endpoints (`POST /demo/consumers/{name}/pause`
  and `PUT /demo/payment-behavior`) wedge the system indefinitely with no auto-recovery, so one
  curious visitor can leave the demo broken for the next. `docs/planning/sprint-1/high-level-design.md`
  §21 and ADR-002 both anticipated exactly this and left it to deployment time. Recommended fix: keep
  `/demo` public (it is the product), add an idle auto-reset in Scenario Service reusing the existing
  `DemoResetService`, and add a Cloudflare rate-limiting rule. Overlaps the Sprint 2 security task.
- Which domain name, and at which registrar. A custom domain is decided; Cloudflare Registrar sells
  at cost with flat renewals and keeps DNS, TLS and (optionally) Tunnel in one free account.
- CI/CD deployment is deliberately out of scope for this spike — deployment is a manual `kubectl
  apply`. Revisit once the deployment itself is stable.

---

## Sources

- [Hetzner Cloud pricing calculator (Aug 2026)](https://costgoat.com/pricing/hetzner)
- [Hetzner — new CX plans announcement](https://www.hetzner.com/pressroom/new-cx-plans/)
- [Hetzner 2026 price increases — Northflank](https://northflank.com/blog/hetzner-cloud-server-price-increases)
- [Hetzner June 2026 price changes — byteiota](https://byteiota.com/hetzner-june-2026-price-shock/)
- [Hetzner billing for stopped servers — CloudTally](https://cloudtally.eu/blog/why-hetzner-charges-for-stopped-servers)
- [DigitalOcean Kubernetes pricing](https://www.digitalocean.com/pricing/kubernetes)
- [DigitalOcean Droplet pricing](https://www.digitalocean.com/pricing/droplets)
- [Civo pricing](https://www.civo.com/pricing)
- [Vultr VKE docs](https://docs.vultr.com/support/products/vke)
- [AWS EKS pricing](https://aws.amazon.com/eks/pricing/)
- [Google Kubernetes Engine pricing](https://cloud.google.com/kubernetes-engine/pricing)
- [Fly.io pricing](https://fly.io/docs/about/pricing/)
- [Render instance types](https://render.com/docs/compute-plans)
- [Render pricing explained — livemy.app](https://livemy.app/blog/render-pricing)
- [Railway pricing](https://railway.com/pricing)
- [Oracle free tier Ampere limits halved — InfoQ](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/)
- [Cloudflare plans](https://www.cloudflare.com/plans/)
