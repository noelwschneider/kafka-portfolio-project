# 9.2 — The production overlay

[← The platform decision](1-the-platform-decision.md) · [Next: Tuning for a small box →](3-tuning-for-a-small-box.md)

`infrastructure/kubernetes/production/` — what changes between a local `kind` cluster and a box on the
public internet, expressed as patches rather than a second copy.

---

## Why an overlay

The requirement is unusually strict:

> `kubectl apply -f infrastructure/kubernetes/` against local `kind` behaves **exactly as it did
> before this ADR**.

The base manifests do not change. Everything production needs arrives as an overlay, so the local
development flow of [Chapter 7](../07-containers-and-kubernetes/README.md) is untouched — no new
required arguments, no environment variable that has to be set, no way to accidentally apply
production settings locally.

> **Primer — [Kustomize](../technology/kubernetes/kustomize.md)**
> Bases and overlays, strategic-merge vs. JSON 6902 patches and when each is right, built-in
> transformers, composing overlays, `kubectl kustomize` before `kubectl apply -k`, and Kustomize vs.
> Helm.

Note this is where [Chapter 7](../07-containers-and-kubernetes/2-kubernetes-manifests.md)'s rejection
of Helm gets revisited — and the answer is still not Helm. A genuine second environment appeared, and
the response was **patches over plain YAML**, keeping the base manifests readable and applyable on
their own.

## The structure

```
production/
├── common/
│   ├── kustomization.yaml     what to include, what to exclude, what to patch
│   ├── ingress.yaml           production-only: Traefik Ingress + StripPrefix
│   └── patch-tuning.yaml      CX23 tuning (section 3)
├── ghcr/                      common + real GHCR image references
├── local-verify/              common + local image tags
├── create-postgres-secret.sh
├── redeploy.sh                (section 4)
└── README.md
```

Two leaf overlays over one `common`, and the reason for the split is a good distinction:

> the registry path is **the one piece of the production configuration that is an environment fact
> rather than a design decision.**

`common` holds design decisions and stays renderable with no registry — so `kubectl kustomize
production/common` can be inspected and reviewed by anyone. `ghcr/` adds the environment fact.
`local-verify/` exists so the whole production configuration can be validated on a laptop before it
reaches the box.

---

## Five changes

The `kustomization.yaml` header enumerates them, which is itself worth copying — an overlay that lists
what it does and why is far easier to audit than one you have to diff.

### 1. The committed Secret is omitted

```yaml
resources:
  - ../../00-namespace.yaml
  # ../../01-secrets.yaml is deliberately absent — see note 1 above.
```

> The committed Postgres password is fine for a throwaway kind cluster and not for a public box;
> production generates that Secret **imperatively** first.

Exclusion by omission, with a comment marking it as deliberate — so a future reader does not "fix" the
missing file.

`create-postgres-secret.sh` creates it with `kubectl create secret` outside version control.

> **We got this wrong.** `01-secrets.yaml` contained a real committed password for most of the
> project's life, caught by Sprint 2's security pass. Base64 is an encoding, not encryption.
> [Chapter 10](../10-retrospective/README.md).

### 2. NodePort becomes ClusterIP

```yaml
- target:
    version: v1
    kind: Service
    name: order-service
  patch: |-
    - op: replace
      path: /spec/type
      value: ClusterIP
    - op: remove
      path: /spec/ports/0/nodePort
```

Six times, once per Service. And the reason it is a patch rather than a suggestion:

> A reachable NodePort would **bypass that allowlist completely**, which is why this is a patch and
> not a suggestion.

[Chapter 7](../07-containers-and-kubernetes/2-kubernetes-manifests.md) flagged this: `NodePort` opens
a port on **every node**. On an internet-facing box, anyone scanning 30000–32767 reaches the service
directly, and every routing rule you wrote is irrelevant.

**A security boundary is only a boundary if there is no way around it.** An ingress allowlist with live
NodePorts beside it is decoration.

Note the JSON 6902 style, and why:

> JSON 6902 rather than a strategic merge: **removing the `nodePort` field is an explicit operation
> here**, instead of relying on how a null merges into a keyed list.

Removing a field is exactly where strategic merge gets subtle. When the correctness of a security
boundary depends on a field being gone, use the patch format that says "remove."

### 3. The Ingress, and the allowlist

One hostname, everything behind it, `/svc/{service}/...` prefixes stripped before the request reaches
a pod:

> a StripPrefix middleware removes `/svc/{service}` before the request reaches the pod, so **every
> service keeps serving the exact paths it serves locally** and the Deployments' probe paths are
> untouched. The frontend production build is built with those same prefixes as its base URLs, which
> also makes every browser request **same-origin — so CORS is never consulted in production at all.**

Two payoffs collected at once. The services are unmodified — a path prefix is an edge concern.
And CORS, which cost real debugging in
[Chapter 8](../08-observability-and-scaling/2-metrics.md), simply **does not apply**, because
everything is same-origin. The
[CORS primer](../technology/http/cors.md)'s closing note — that a reverse proxy is often the better
answer — turns out to be this project's production answer.

The frontend side is the `ARG` mechanism from
[Chapter 7](../07-containers-and-kubernetes/1-containers-and-compose.md):
`--build-arg VITE_ORDER_SERVICE_URL=/svc/order`. A relative prefix works because `apiFetch` and both
`EventSource` URLs concatenate `${baseUrl}${path}` — a decision from
[Chapter 2](../02-domain/6-the-first-frontend.md) with no code change since.

#### The allowlist is the security model

> **THIS IS AN ALLOWLIST, AND THAT IS THE POINT.** Only the paths listed below are routed; anything
> else has no matching router and Traefik answers 404. The endpoints that can wedge the demo
> indefinitely with no auto-recovery —
>
> ```
> POST /demo/consumers/{name}/pause   (inventory-service, fulfillment-service)
> PUT  /demo/payment-behavior         (payment-service)
> ```
>
> — are **never called by the browser.** Scenario Service invokes them server-side over
> cluster-internal DNS, which keeps working because that traffic never touches this Ingress. So they
> are simply not routed, and the wedge risk is removed **structurally rather than by
> authentication.**

This is where [ADR-002](../01-design-contract/3-state-and-api-contracts.md) pays off in a way nobody
planned.

The `/api`–`/demo` split was made in Phase 0 for *cleanliness* — to keep the business API honest. Five
chapters later it turns out to be the **security boundary** that makes a public demo possible: a
visitor can run every scenario, and cannot pause a consumer or arm a payment rejection, because
Scenario Service calls those over cluster-internal DNS and the Ingress simply does not route them.

**"Not deployed" beats "authenticated."** No credentials to manage, no auth code to get wrong, no
session handling — the endpoint is unreachable from outside the cluster, full stop.

`/actuator/metrics` and `/actuator/prometheus` are excluded on the same reasoning: nothing in the
browser needs them.

#### The cost of no catch-all

> Adding a frontend route means adding it to the frontend Ingress below. That is the cost of not
> having a catch-all: a `/` prefix rule would match everything, including
> `/svc/inventory/demo/consumers/...`, and would answer it with the SPA's `index.html` instead of a
> 404. **Nothing would leak** — that request would still never reach a backend — but **"not listed
> means 404" is a far easier property to check than "not listed means you get some HTML"**, so the
> frontend is enumerated too.

A stricter invariant chosen because it is *checkable*, not because the looser one is unsafe. Verifying
"every unlisted path 404s" is a one-line test; verifying "every unlisted path returns HTML that does
nothing dangerous" requires reasoning about the SPA.

Consequences, stated: the SPA's own catch-all route is unreachable for unrouted paths — they 404 at
the edge — and every new frontend route needs an Ingress entry.

And one more deliberate choice:

> No `host:` is set, so these rules apply to every hostname pointed at the box. That is deliberate —
> it means **the allowlist cannot be sidestepped by hitting the node's IP directly.**

Host-based rules would leave a hole: request the raw IP, match no rule, and fall through to whatever
Traefik's default is. Applying to every hostname closes it.

Even the version check is written down rather than assumed:

> k3s ships Traefik, and Traefik v3 serves these CRDs under `traefik.io`. **Confirm on the box before
> applying**, since a v2 install would need `traefik.containo.us`.

### 4. The CX23 tuning

[Section 3](3-tuning-for-a-small-box.md).

### 5. The production Spring profile

`SPRING_PROFILES_ACTIVE=production` on Scenario Service turns on the **idle auto-reset** (15 minutes)
and widens scenario timeouts for slower hardware.

Idle reset is what makes an unattended public demo work: a visitor who pauses a consumer and closes
the tab leaves the system wedged, and fifteen minutes later it resets itself. The
[demo-state-is-real-state](../05-scenarios-and-frontend/3-the-eight-scenarios.md) cost from ADR-002,
paid automatically.

---

## Images, and the arm64 problem

```yaml
images:
  - name: order-service
    newName: ghcr.io/noelwschneider/kafka-portfolio-project/order-service
    newTag: latest
```

The base manifests keep `<service>:local`, so `common` renders with no registry. `ghcr/` maps them.

The reason a registry exists at all is hardware:

> the demo box is x86_64 and the development laptop is arm64, so **images built locally will not run
> there.** GitHub's hosted runners are native x86, free for public repositories, and build without
> QEMU emulation.

An Apple Silicon laptop cannot build a runnable x86 image without emulation, and emulating a
five-module Maven build is *"the difference between minutes and a large multiple of that."*

Once images are in a registry, the deploy gets simpler than local development:

> k3s pulls these itself via containerd — **no `docker` on the box, no `kind load`**, no
> `k3s ctr images import`. Public GHCR packages need no `imagePullSecret`.

No `kind load` step ([Chapter 7](../07-containers-and-kubernetes/3-probes-and-resources.md)) because
there is a real registry.

And a documented manual fallback with the exact `docker buildx --platform linux/amd64 --push`
commands, including the frontend's five build args — *"if this workflow is unavailable or not yet
trusted."* Writing down the manual path for an automated step is what keeps the automation from
becoming the only way anyone knows how to do it.

---

[← The platform decision](1-the-platform-decision.md) · [Next: Tuning for a small box →](3-tuning-for-a-small-box.md)
