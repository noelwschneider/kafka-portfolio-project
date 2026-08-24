# Chapter 9 — Production

**Build history:** Sprint 2 goal 4 — ADR-010 (platform), ADR-011 (`0c5ad13 fix production redeploys
taking the demo box down`), plus the security pass and the GHCR image workflow.

Everything until now ran on a laptop. This chapter puts it on the public internet, on a €6/month box
with 2 vCPUs and no swap — and the constraints of that box drive every decision in it.

---

## Sections

| # | Section | Covers |
|---|---|---|
| 1 | [The platform decision](1-the-platform-decision.md) | Why the deployed thing must be the same system, sizing from Phase 10's own measurements, k3s over managed Kubernetes, always-on billing reality, and manual deploys with automated builds |
| 2 | [The production overlay](2-the-production-overlay.md) | Kustomize over a base that never changes, NodePort → ClusterIP as a security requirement, the Ingress allowlist, same-origin routing that removes CORS entirely, and why images need a registry |
| 3 | [Tuning for a small box](3-tuning-for-a-small-box.md) | The four blocking changes — TCP broker probe, explicit heap caps, a startup probe, widened scenario timeouts — each with its stated cost |
| 4 | [The outage](4-the-outage.md) | One routine command, a control plane that starved with its workload, a fix that caused a second outage, and three changes at three layers |

---

## The requirement that shaped everything

> The deployed thing must be the *same* system, not a reduced one. The Deployments, the probes, the
> resource requests and limits, a rolling update, a consumer group rebalancing after a pod is killed —
> **those are the demonstration.** A platform that cannot run them is not cheaper, it is a different
> product.

That single sentence eliminates every cheap PaaS option, and it is the right answer to "why not just
deploy it somewhere simple?"

---

## Four ideas worth carrying out

**Measurements you already have beat estimates.** Sizing did not need a guess: Phase 10 had run this
exact 8-pod stack inside 3.825 GiB — *less memory than the box being considered.* It also showed the
real risk was CPU rather than memory, and cold start rather than steady state.

**A security boundary is only a boundary if there is no way around it.** An Ingress allowlist with live
NodePorts beside it is decoration, which is why turning six Services into `ClusterIP` is *"a patch and
not a suggestion."*

**"Not deployed" beats "authenticated."** The endpoints that can wedge the demo are simply not routed.
Scenario Service reaches them over cluster-internal DNS. No credentials, no auth code, no sessions —
and this works because [ADR-002](../01-design-contract/3-state-and-api-contracts.md) split `/api` from
`/demo` in Phase 0 for entirely different reasons.

**On a single-node cluster, the control plane is a workload.** Starve the node and Kubernetes cannot
observe that it needs to recover — which is how a memory spike became an outage that required a reboot
rather than one that self-resolved.

---

## Build it yourself

This chapter is the least reproducible — it needs a VPS, a domain, and a few euros a month. The
repo-side work is all doable without one.

**Repo side** — [sections 2](2-the-production-overlay.md) and [3](3-tuning-for-a-small-box.md)

1. Remove any committed Secret. Write a `create-postgres-secret.sh` that generates it with
   `kubectl create secret`, outside version control.
2. `production/common/kustomization.yaml`: enumerate the base resources, **omit the secret**, and patch
   every Service from `NodePort` to `ClusterIP` with **JSON 6902** so `nodePort` is explicitly removed.
3. `production/common/ingress.yaml`: a Traefik `Middleware` doing StripPrefix, and an `Ingress` that
   **enumerates every routed path** — backend `/svc/{service}/…` prefixes and frontend routes.
   Deliberately omit `/demo/consumers/*/pause`, `/demo/payment-behavior`, `/actuator/metrics`, and
   `/actuator/prometheus`. Set no `host:`.
4. `production/common/patch-tuning.yaml`: `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=60`, a
   `startupProbe` allowing ~5 minutes, relaxed liveness/readiness timings, and
   `strategy.rollingUpdate.maxSurge: 0` on all five backends.
5. Replace Kafka's `exec` readiness probe with a `tcpSocket` check — **in the base manifests**, since it
   is a strict improvement everywhere.
6. Widen scenario timeouts in `application-production.yml`, and enable idle auto-reset there.
7. `production/ghcr/` and `production/local-verify/`, both over `../common`, differing only in the
   `images` transformer.
8. A `workflow_dispatch`-only GitHub Actions workflow building all six images for `linux/amd64` and
   pushing to GHCR with both `latest` and an immutable commit-SHA tag. Build the frontend with the
   `/svc/{service}` build args.
9. `redeploy.sh` — `set -euo pipefail`, restarting the five backends **one at a time**, waiting for
   `kubectl rollout status` before continuing.
10. Verify the whole overlay locally: `kubectl kustomize production/common` to inspect, then
    `kubectl apply -k production/local-verify` against kind.

**Box side** — [section 1](1-the-platform-decision.md)

11. A small always-on VPS. Install k3s (which brings Traefik and metrics-server).
12. **Firewall the NodePort range 30000–32767**, so nothing can bypass the Ingress even by accident.
13. DNS and TLS at your provider; a `host:` and `tls:` block on the Ingress once the name exists.
14. `create-postgres-secret.sh`, then `kubectl apply -k production/ghcr`.
15. Confirm Traefik's CRD API group before applying — v3 uses `traefik.io`, v2 `traefik.containo.us`.

**Done when:** a stranger with the URL can run all eight scenarios; every unlisted path 404s at the
edge; no NodePort is reachable; a full five-service redeploy via `redeploy.sh` completes without the
box exceeding its steady-state memory; and an abandoned session resets itself within fifteen minutes.

---

## Next

[Section 1 — The platform decision](1-the-platform-decision.md).
