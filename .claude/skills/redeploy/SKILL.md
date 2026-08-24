---
name: redeploy
description: Redeploy the production demo box safely, with pre-flight capacity checks and real post-deploy verification.
disable-model-invocation: true
allowed-tools: Bash(kubectl *), Bash(curl *), Bash(infrastructure/kubernetes/production/redeploy.sh*)
---

# Production redeploy

## Live state at invocation

- kubectl context: !`kubectl config current-context 2>&1 || true`
- Nodes: !`kubectl top nodes 2>&1 | head -5 || true`
- Pods: !`kubectl get pods -n orderfulfillment 2>&1 | head -20 || true`
- Demo health: !`curl -s -o /dev/null -w "%{http_code}" --max-time 10 https://fulfillment-demo.noelschneider.com/ 2>&1 || true`

## Before anything else

Read the state above. If the context is not the demo box, stop — everything below targets production.

If a node is already near its memory ceiling or pods are not in a steady `Running` state, do not start
a rollout. A redeploy surges memory, and starting one on a box that is already stressed is exactly
what caused the outage recorded in
`docs/adr/ADR-011-sequential-production-rollouts-to-avoid-memory-exhaustion.md`. Diagnose first.

## Deploy

```bash
infrastructure/kubernetes/production/redeploy.sh
```

This restarts the five backend Deployments one at a time, waiting for each to become healthy before
the next. Run it in the **foreground** and wait for it to finish.

**Never** use `kubectl rollout restart deployment -n orderfulfillment`. It surges every Deployment at
once, which is what took the box down. `--timeout 180s` is available if a service is slow to come up.

## Verify

Pods reporting `Ready` is not verification. Hit the real thing:

```bash
curl -sS -o /dev/null -w "%{http_code}\n" https://fulfillment-demo.noelschneider.com/
kubectl get pods -n orderfulfillment
```

Confirm the demo actually serves traffic and, if the change touched order flow, submit a real order
against the live system and watch it reach a terminal state.

If verification fails, escalate rather than declaring partial success. Re-check capacity before a
second attempt.
