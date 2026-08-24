# Kustomize

*Referenced from [Chapter 9.2 — The production overlay](../../09-production/2-the-production-overlay.md).*

---

## The problem

The same application in two environments differs in a handful of ways — image tags, replica counts,
resource limits, one or two extra objects. Three bad answers:

- **Copy the manifests per environment.** They diverge within a month, and a fix applied to one is
  forgotten in the other.
- **Edit before applying.** Not reproducible, not reviewable, not in version control.
- **Templating** (Helm). Works, and introduces a template language between the reader and the YAML.

Kustomize takes a fourth: **keep plain YAML, and describe the differences as patches.** Base manifests
stay valid, applyable Kubernetes objects. An overlay says what to change.

It is built into `kubectl` (`kubectl apply -k`), so there is nothing to install.

## Structure

```
kubernetes/
├── 00-namespace.yaml         base — plain, valid, applyable on its own
├── 04-order-service.yaml
└── production/
    ├── kustomization.yaml    the overlay
    ├── ingress.yaml          an object only production has
    └── patch-tuning.yaml     changes to base objects
```

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - ../00-namespace.yaml
  - ../04-order-service.yaml
  - ingress.yaml            # production-only

patches:
  - path: patch-tuning.yaml
```

Note what `resources` implies: an overlay **enumerates** what it includes. Omitting a base file is how
you exclude an object — which is a deliberate, reviewable act rather than a deletion.

## Two patch styles

### Strategic merge

A partial object, merged by field:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
spec:
  template:
    spec:
      containers:
        - name: order-service         # the merge key
          env:
            - name: JAVA_TOOL_OPTIONS
              value: "-XX:MaxRAMPercentage=60"
```

Readable, and it looks like the thing it modifies. The catch is **lists**: Kubernetes list merging
uses a merge key (usually `name`), and behavior differs by field — some lists merge by key, others
replace wholesale. Adding is reliable; *removing* is where it gets subtle.

### JSON 6902

Explicit operations against paths:

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

Verbose, and unambiguous. `op: remove` **deletes a field**, which strategic merge cannot reliably do.

**Rule of thumb:** strategic merge to add or change; JSON 6902 to remove, or when the merge semantics
of a list are not obvious.

## Built-in transformers

Common changes have first-class support rather than needing patches:

```yaml
images:
  - name: order-service                                   # matches the base image name
    newName: ghcr.io/owner/project/order-service
    newTag: latest

namespace: orderfulfillment
namePrefix: staging-
commonLabels:
  environment: production

replicas:
  - name: order-service
    count: 3
```

`images` is the one that earns its keep: base manifests can carry a local tag and stay renderable
without a registry, while the overlay supplies the real reference.

## Composing overlays

An overlay's `resources` can point at another overlay:

```
production/
├── common/          shared production config
├── ghcr/            common + real registry images
└── local-verify/    common + local image tags
```

```yaml
# ghcr/kustomization.yaml
resources:
  - ../common
images:
  - name: order-service
    newName: ghcr.io/owner/project/order-service
```

This separates **design decisions** (what production configuration is) from **environment facts**
(where the images live) — so the design half can be rendered and reviewed with no registry access.

## Working with it

```bash
kubectl kustomize <dir>          # render to stdout — inspect before applying
kubectl apply -k <dir>           # render and apply
kubectl diff -k <dir>            # what would change
```

**Always render before applying.** Kustomize output is plain YAML, so `kubectl kustomize` shows
exactly what the cluster will receive. A patch that silently matched nothing is visible here and
invisible in the source.

## Kustomize vs. Helm

| | Kustomize | Helm |
|---|---|---|
| Mechanism | Patch plain YAML | Render templates |
| Base validity | Base is applyable as-is | Templates are not valid YAML alone |
| Learning curve | Small | A template language plus a values schema |
| Distribution | Not really | Charts, repositories, versioning |
| Release management | None — `kubectl` does it | Install/upgrade/rollback, release history |

**Kustomize** suits your own application in a few environments, where you want the YAML to stay
readable. **Helm** suits software distributed to others, or configuration with real conditional
logic.

They compose — a Helm chart's rendered output can be patched with Kustomize — though needing both is
usually a signal that one of them is doing something it should not.
