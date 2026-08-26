# Production overlay

Everything specific to the public demo box (single-node k3s on a Hetzner CX23 — see
`docs/adr/ADR-010-k3s-on-a-dedicated-hetzner-vps-for-the-public-demo.md`). Nothing here replaces a
base manifest: `kubectl apply -f infrastructure/kubernetes/` against a local `kind` cluster keeps
behaving exactly as it did before this directory existed. This is additive, and it is only applied
when deploying to the demo box.

Layout:

| Path | What it is |
| --- | --- |
| `common/` | The overlay itself — the base manifests minus the dev Secret, plus the ingress, the `ClusterIP` patch and the CX23 tuning |
| `common/ingress.yaml` | Traefik `Ingress` + `StripPrefix` middleware. **The path allowlist is the demo's security boundary** — read the header comment before editing |
| `common/patch-tuning.yaml` | JVM heap caps, `startupProbe`s, relaxed probe timings for a 2-vCPU box, and `maxSurge: 0` on the five backend Deployments |
| `ghcr/` | The overlay as deployed: `common/` plus real GHCR image references |
| `local-verify/` | The same overlay against locally-built `kind` images, so the whole thing can be exercised without a registry |
| `render.sh` | Renders any of the above to stdout, to be piped into `kubectl apply -f -` |
| `create-postgres-secret.sh` | Generates the Postgres `Secret` at apply time instead of using the committed dev password |
| `redeploy.sh` | Restarts the five backend Deployments, then the frontend Deployment, one at a time, waiting for each to become healthy before starting the next |

## `kubectl` access from off the box

The k3s API server (port 6443) is not exposed publicly — only SSH, HTTP, and HTTPS reach the box.
`redeploy.sh` and `kubectl` were originally only ever run from the box itself, where the `deploy`
user's `~/.kube/config` already points at `127.0.0.1:6443`. To run them from a local machine or an
agent session instead, tunnel through the existing SSH alias rather than opening the API port:

```bash
ssh -f -N -L 16443:127.0.0.1:6443 kafka-demo-box
```

Then use a kubeconfig context named `kafka-demo-box` pointed at `https://127.0.0.1:16443` (the same
client cert/key `scp`'d from `kafka-demo-box:~/.kube/config`, with only the server address rewritten
to the tunnel's local port). `current-context` should stay unset by default so `kubectl` never
silently targets production — switch to it deliberately per session:

```bash
kubectl config use-context kafka-demo-box
```

The tunnel is a background process tied to the shell that started it — it does not persist across a
new terminal or a new agent session, so this needs repeating each time.

## Deploying

```bash
# 1. Namespace first — the Secret needs somewhere to live.
kubectl apply -f infrastructure/kubernetes/00-namespace.yaml

# 2. Generate the Postgres credentials on the box (never from the repo — see below).
infrastructure/kubernetes/production/create-postgres-secret.sh

# 3. Everything else.
infrastructure/kubernetes/production/render.sh ghcr | kubectl apply -f -
```

`render.sh` exists because the overlay's `resources:` reach one directory up into the base
manifests, and kustomize only loads files outside its root when the load restrictor is turned off —
a flag `kubectl kustomize` accepts and `kubectl apply -k` does not. The alternative, a
`kustomization.yaml` inside `infrastructure/kubernetes/`, would break the local flow, because
`kubectl apply -f infrastructure/kubernetes/` applies every YAML file in that directory. Keeping the
base directory pure Kubernetes objects is the constraint; the script is the price.

## Redeploying

After new images are pushed to GHCR (or to pick up a Secret rotation), restart the running
Deployments to pull them:

```bash
infrastructure/kubernetes/production/redeploy.sh
```

**Do not use `kubectl rollout restart deployment -n orderfulfillment`** (restarts every Deployment
in the namespace at once) or `kubectl rollout restart deployment/<name>` run manually one at a time
without waiting between them. Either one can put old and new pods of multiple services in memory at
the same time on a box with no spare RAM and no swap — that combination took the demo box down for a
full outage; see `docs/adr/ADR-011-sequential-production-rollouts-to-avoid-memory-exhaustion.md`.
`redeploy.sh` restarts the five backend Deployments, then `frontend`, one at a time, and waits for
`kubectl rollout status` to confirm each is healthy before restarting the next, so the fleet never
needs more than one service's worth of extra memory during the restart, and a stuck rollout for one
service fails loudly instead of compounding into a second one.

Frontend is included in the same script, at the end of the sequence, but for a different reason than
the backend ordering: it is a static nginx container requesting 64Mi/limited to 128Mi (see
`../09-frontend.yaml`), small enough next to the JVM services that it does not carry the
maxSurge: 0 patch applied to the five backend Deployments (`common/patch-tuning.yaml`'s T5 comment
covers only those five), so a brief overlap of its old and new pod during rollout does not
meaningfully change the box's memory headroom. It stays in `redeploy.sh` regardless, so that pushing
a new frontend image to GHCR has the same one-command path to the live site as a backend change —
there is no separate, undocumented step required to pick it up.

`ghcr/kustomization.yaml` points at `ghcr.io/noelwschneider/kafka-portfolio-project/{service}`,
the same path `.github/workflows/build-images.yml` derives from `${{ github.repository }}` at build
time. The workflow is `workflow_dispatch` only — run it whenever the images need rebuilding, and it
prints the exact names it pushed. The six packages are public, so k3s pulls them with no
`imagePullSecret`; a newly created GHCR package defaults to private and would need switching once.

## What the overlay changes, and why each one matters

**Routing and the allowlist (`common/ingress.yaml`).** One hostname, everything behind it. Backend
calls use `/svc/{service}/...` prefixes that a `StripPrefix` middleware removes before the request
reaches the pod, so every service serves the paths it serves locally and the Deployments' probe
paths are untouched. Only the listed paths are routed; everything else 404s at the edge. That is how
`POST /demo/consumers/{name}/pause` and `PUT /demo/payment-behavior` — the two endpoints that leave
the system wedged indefinitely with no auto-recovery — become unreachable from the internet while
still working for Scenario Service, which calls them server-side over cluster-internal DNS.
`/actuator/metrics` and `/actuator/prometheus` are excluded on the same basis: nothing in the
browser needs them.

**The six application Services become `ClusterIP`.** A reachable NodePort bypasses the allowlist
entirely, so this is not cosmetic. It is one of two independent defenses; the other is the box's
firewall blocking 30000–32767, which belongs to the provisioning task and is not in this repo.

**CX23 tuning (`common/patch-tuning.yaml`).** Explicit heap caps and `startupProbe`s, sized for
2 vCPU, plus `maxSurge: 0` on the five backend Deployments so a rolling update tears the old pod
down before starting the new one instead of briefly running both
(`docs/adr/ADR-011-sequential-production-rollouts-to-avoid-memory-exhaustion.md`). The heap/probe
rationale is in `docs/agent-reports/sprint-2/deployment-platform-revision.md` §3 and summarized in
ADR-010; the file's own header comment explains each number. One related change is *not* here:
Kafka's readiness probe no longer spawns a JVM per check, which is a strict improvement locally too,
so it lives in the base `03-kafka.yaml`.

**`SPRING_PROFILES_ACTIVE=production` on scenario-service.** Turns on the idle auto-reset (15
minutes) and the widened scenario timeouts in that service's `application-production.yml`. Nothing
else in the repo sets that profile, which is why local Compose and `kind` are unaffected by either.

## Postgres credentials

`infrastructure/kubernetes/01-secrets.yaml` commits a Postgres password
(`orderfulfillment/orderfulfillment`) in `stringData`. That is fine for a throwaway `kind` cluster —
it is the same value `docker-compose.yml` uses — and stops being fine once applied to a publicly
reachable box. Postgres itself stays `ClusterIP` and is never internet-reachable either way, but a
committed password is still a password in a public git history.

The overlay simply does not include that file, so there is nothing to remember not to apply.
`create-postgres-secret.sh` produces a `Secret` named `postgres-credentials` with the same keys
(`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`), so every Deployment's `secretKeyRef` works
unmodified — the only thing that changes is where the Secret comes from.

Equivalently, by hand:

```bash
kubectl create secret generic postgres-credentials \
  --namespace orderfulfillment \
  --from-literal=POSTGRES_DB=orderfulfillment \
  --from-literal=POSTGRES_USER=orderfulfillment \
  --from-literal=POSTGRES_PASSWORD="$(openssl rand -base64 24)"
```

Rotation: re-run `create-postgres-secret.sh --rotate` and
`infrastructure/kubernetes/production/redeploy.sh`. There is no automatic rotation; this is a
manual-ops box.

## Verifying the overlay locally

The allowlist is worth checking rather than trusting, and it can be checked end to end on `kind`.
The only piece `kind` lacks is an ingress controller — k3s ships Traefik, `kind` ships nothing — so
install Traefik first:

```bash
kind create cluster --config infrastructure/kind-config.yaml

# Traefik v3: CRDs, RBAC, then a Deployment exposing entrypoint :8000 on nodePort 30173, which
# infrastructure/kind-config.yaml already maps to host port 5173.
kubectl apply -f https://raw.githubusercontent.com/traefik/traefik/v3.3/docs/content/reference/dynamic-configuration/kubernetes-crd-definition-v1.yml
kubectl apply -f https://raw.githubusercontent.com/traefik/traefik/v3.3/docs/content/reference/dynamic-configuration/kubernetes-crd-rbac.yml
# ... plus a small traefik Deployment/Service/ServiceAccount with:
#     --entrypoints.web.address=:8000 --providers.kubernetesingress --providers.kubernetescrd
```

Build the images as `README.md`'s `kind` section describes, plus a `frontend:local-prod` built with
the production base URLs (the command is in `local-verify/kustomization.yaml`), `kind load` them,
then:

```bash
kubectl apply -f infrastructure/kubernetes/00-namespace.yaml
infrastructure/kubernetes/production/create-postgres-secret.sh
infrastructure/kubernetes/production/render.sh local-verify | kubectl apply -f -
```

Everything is then reachable on `http://localhost:5173` through Traefik, and the two properties that
matter can be checked directly:

```bash
curl -o /dev/null -w '%{http_code}\n' http://localhost:5173/svc/scenario/demo/scenarios          # 200
curl -o /dev/null -w '%{http_code}\n' -X POST \
  http://localhost:5173/svc/inventory/demo/consumers/order-created/pause                          # 404
curl -o /dev/null -w '%{http_code}\n' http://localhost:8082/actuator/health                       # fails: no NodePort
```

Note on prefix matching: Traefik implements `pathType: Prefix` as a literal string prefix rather
than matching on path segments, so a URL that shares a literal prefix with an allowlisted one
(`/svc/scenario/demo-secret`) reaches that same service and gets the service's own error response.
It never reaches a different service, and it never reaches a path the allowlist excludes.
