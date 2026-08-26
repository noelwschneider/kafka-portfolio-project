---
name: deploy
description: Guided production deploy - build and push the six images, then redeploy and verify the demo box, with explicit checkpoints before every irreversible step. Pass --restart-only to redeploy already-pushed images without a new build (e.g. a Secret rotation, or picking up a `:latest` that was pushed without a redeploy).
argument-hint: "[tag; defaults to latest] [--dry-run] [--restart-only]"
disable-model-invocation: true
allowed-tools: Read, Bash(gh workflow *), Bash(gh run *), Bash(kubectl *), Bash(curl *), Bash(git rev-parse *), Bash(infrastructure/kubernetes/production/redeploy.sh*)
---

# Guided production deploy

Four stages, run in order: **build & push -> pre-redeploy checkpoint -> redeploy -> verify**. Each
mutating action is gated by an explicit confirmation. This command never chains into itself
automatically and never runs unattended — see `docs/adr/ADR-010-k3s-on-a-dedicated-hetzner-vps-for-the-public-demo.md`
and `docs/adr/ADR-011-sequential-production-rollouts-to-avoid-memory-exhaustion.md` for why the demo
box's deploy path is manual by design, not an oversight to fix.

## Modes

- **`/deploy [tag]`** — the full flow. Stage 0 runs for real, then every subsequent stage executes for
  real, pausing at each checkpoint below for an explicit "yes" from the operator (not inferred from
  silence, not skipped because a prior checkpoint was approved) before the next mutating action.
- **`/deploy --dry-run [tag]`** — stage 0 runs for real (it is read-only). Stages 1-4 print the exact
  commands, image list, tag, and restart order that a real run would use, and state clearly what
  differs from the box's current state. Nothing is pushed to GHCR, nothing on the cluster changes. Use
  this to rehearse the flow or sanity-check the plan before committing to a real run.
- **`/deploy --restart-only`** — skips stage 1 entirely and goes straight to stage 2. Use this when no
  new image needs building — a Secret rotation, a manifest-only change, or re-picking-up a `:latest`
  that was pushed earlier without a redeploy. This is the common case, not a rare edge: most production
  touches on this box are config/manifest changes, not new application code, so defaulting every deploy
  through a full six-image rebuild would be wasteful. `--dry-run` and `--restart-only` combine freely.

Default tag is `latest`, matching `infrastructure/kubernetes/production/ghcr/kustomization.yaml`'s
current `newTag: latest` on all six images. An explicit tag argument only changes which mutable tag
`build-images.yml` is asked to publish (see its `tag` input) — the workflow always additionally pushes
an immutable commit-SHA tag regardless, but the overlay does not currently point at it (see Judgment
call below on tag pinning). A tag argument is meaningless with `--restart-only`, since no build runs;
ignore it if both are passed.

If the operator declines a checkpoint, stop immediately and state plainly what has and has not
happened yet (e.g. "images ARE now live on GHCR; production has NOT been touched") — never leave the
operator to infer state from scrollback.

## Stage 0 — Check before you act (always runs, read-only)

```bash
kubectl config current-context
kubectl top nodes
kubectl get pods -n orderfulfillment -o wide
kubectl get deployments -n orderfulfillment \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.template.spec.containers[0].image}{"\n"}{end}'
curl -sS -o /dev/null -w '%{http_code}\n' --max-time 10 https://fulfillment-demo.noelschneider.com/
git rev-parse HEAD
```

- If `current-context` is not the demo box, **stop** — nothing past this point should run against the
  wrong cluster.
- If a node is already near its memory ceiling, pods are not in a steady `Running` state, or the demo
  is not currently serving 200, stop and diagnose first. Starting a build-and-redeploy cycle on a box
  that is already stressed is exactly the precondition ADR-011's outage started from. A build itself is
  harmless (it runs on GitHub's runners, not the box) but there is no reason to queue a redeploy behind
  an already-unhealthy box.
- Record the current image tag per deployment — since the overlay uses mutable `:latest`, this is a
  digest, not a version string; the tag alone will not visibly change after this deploy either (see
  Judgment call below). Note the commit each service's pod was actually built from where practical
  (e.g. `/actuator/info` if a service exposes build info) so stage 4 can sanity-check something moved.

**`--restart-only` skips straight to Stage 2 from here.**

## Stage 1 — Build & push

State the plan before running anything:

- **Images**: order-service, inventory-service, payment-service, fulfillment-service,
  scenario-service, frontend (the full `build-images.yml` matrix — GitHub Actions runs all six
  regardless of what changed, and `fail-fast: false` means a failure in one does not cancel the
  others).
- **Tag**: the resolved mutable tag (default `latest`) plus the immutable `github.sha` tag the
  workflow always pushes alongside it.
- **Effect**: this publishes to the public GHCR packages
  (`ghcr.io/noelwschneider/kafka-portfolio-project/{service}`). If the tag is `latest`, this moves a
  tag other tooling (including this box, once redeployed) treats as "current" — not reversible by
  re-running the build with the same tag pointing at old code, only by pushing a new build.

**Checkpoint — confirm before triggering.** Real run only:

```bash
gh workflow run build-images.yml -f tag=<resolved-tag>
```

Then locate and stream the run (the dispatch does not return a run id directly):

```bash
gh run list --workflow=build-images.yml --limit 1 \
  --json databaseId,status,conclusion,headSha,createdAt
gh run watch <databaseId> --exit-status
```

Wait for the run to finish. **Do not proceed to stage 2 on anything other than a clean success across
all six matrix legs.** `fail-fast: false` means a partial failure is possible and silent unless checked
explicitly — `gh run view <databaseId> --json jobs` shows each matrix leg's own conclusion. A partial
publish (some services updated, some still on the old image) is a worse starting position for a
redeploy than no deploy at all: report exactly which legs failed and stop, rather than redeploying a
mismatched fleet.

Dry run: print the two commands above with the resolved tag substituted, and stop — do not dispatch the
workflow.

## Stage 2 — Pre-redeploy checkpoint

Re-run stage 0's health checks — time has passed since the build started (or, in `--restart-only` mode,
since the operator last checked), and the box's state is not guaranteed to match what it was at the top
of the flow.

State the plan before touching anything:

- **Restart order**: order-service, inventory-service, payment-service, fulfillment-service,
  scenario-service, then frontend, one at a time, waiting for each rollout to report healthy before the
  next starts. This is `infrastructure/kubernetes/production/redeploy.sh`'s fixed order — see its
  header comment and ADR-011 for why sequential-with-wait, not parallel, is load-bearing here.
- **What each restart does**: `maxSurge: 0` on the five backend Deployments means each service is
  briefly *unavailable* (old pod torn down before the new one starts) rather than briefly doubled;
  frontend does not carry that patch, so its restart may briefly run both. Neither is a rollback path —
  a stuck rollout is investigated and re-run, not undone by this command (see Judgment call below).

**Checkpoint — confirm before running `redeploy.sh`.** This is the step that actually changes what is
running in production.

Dry run: print the restart order and the command below, and stop.

## Stage 3 — Redeploy

Real run only, and only after the stage 2 checkpoint:

```bash
infrastructure/kubernetes/production/redeploy.sh
```

Run it in the **foreground** and watch it to completion — do not background it and check back later.
Never use the raw `kubectl rollout restart deployment -n orderfulfillment` — it surges every Deployment
at once, which is what caused ADR-011's outage. `--timeout` is available on the script if a service is
slow to come up.

If the script stops on a failed rollout, it already prints the diagnostic commands (`kubectl describe`,
`kubectl logs`) and exits non-zero. Report that state and **stop the guided flow there** — do not re-run
`redeploy.sh` from the top (it would restart already-healthy services a second time for no reason) and
do not attempt an automatic rollback (see Judgment call below). Hand control back to the operator with
the diagnostic commands already surfaced.

## Stage 4 — Verify

Pods reporting `Ready` is not verification. Hit the real thing:

```bash
kubectl get pods -n orderfulfillment
curl -sS -o /dev/null -w '%{http_code}\n' https://fulfillment-demo.noelschneider.com/
```

If the change touched order flow, ask the operator whether to also submit a real order through the live
system and watch it reach a terminal state — this writes real state into the shared public demo (see
Judgment call below on why this is asked rather than assumed by default).

If verification fails, escalate rather than declaring partial success, and re-check capacity before
considering a second attempt.

## Judgment calls carried from this skill's design

- **One command, not two.** `--restart-only` is a mode flag on `/deploy`, not a second skill, because
  the redeploy-without-a-fresh-build case is common enough (config/manifest-only changes) that it
  doesn't deserve worse ergonomics than the full flow, and a single file means stage 2-4 behavior can
  never drift between "the restart path" and "the full path" — there is only one definition of each.
- **No automatic rollback on a mid-rollout failure.** Stage 3 surfaces the stuck rollout's diagnostics
  and stops, handing control back to a human, rather than attempting `kubectl rollout undo` itself.
  Automating exactly the failure path is the highest-risk place to add new automation.
- **Stage 4's "submit a real order" check is an in-flow question, not a default.** It writes real,
  visible state into the shared public demo (ADR-010), so whether that cost is worth paying is a
  per-run call.
- **Tag pinning not addressed here.** `infrastructure/kubernetes/production/ghcr/kustomization.yaml`
  still uses mutable `newTag: latest` on all six images, even though the file's own comment already
  flags pinning the immutable `github.sha` tag as a known, undone improvement. Until that lands, this
  skill cannot verify a redeploy actually picked up new code by tag or digest alone — verification stays
  behavioral (the app responds correctly), not cryptographic. This is a real production config decision
  outside this skill's scope.
