#!/usr/bin/env bash
# Restarts the five backend Deployments, then the frontend Deployment, one at a time, waiting for
# each rollout to finish before starting the next.
#
# Why this exists instead of `kubectl rollout restart deployment -n orderfulfillment`: that command
# restarts all Deployments in the namespace in one shot. With maxSurge: 0 (see patch-tuning.yaml's
# T5 comment and docs/adr/ADR-011-sequential-production-rollouts-to-avoid-memory-exhaustion.md)
# Kubernetes never runs old and new pods of the *same* service together, but it does nothing to stop
# five *different* services from all restarting at once — five old pods tearing down and five new
# pods starting up simultaneously is still a memory and CPU spike on a 2 vCPU / 4 GB box with no
# swap. Restarting one service, confirming it is actually healthy, then moving to the next keeps the
# peak footprint to "steady state plus one service's worth of startup," and turns a stuck rollout
# for one service into a loud, immediate failure instead of a compounding one — the next service
# never starts if the current one does not come up clean.
#
# Frontend goes last, after the same one-at-a-time discipline as the backend fleet. It does not
# carry the memory rationale above — it is a static nginx container (64-128Mi request/limit, see
# ../09-frontend.yaml) next to JVM services requesting hundreds of MiB each, and it does not get the
# maxSurge: 0 patch (patch-tuning.yaml's T5 applies to the five backend Deployments only), so its own
# restart can briefly run old and new pods together without materially affecting the box's memory
# headroom. It is still restarted through this script rather than by hand so that "run redeploy.sh"
# stays the one command that actually gets a freshly pushed image live, for any of the six services.
#
# Usage:
#   ./redeploy.sh                    # restart all six, in order, 120s timeout each
#   ./redeploy.sh --timeout 180s     # override the per-service rollout timeout
#
# Requires: kubectl pointed at the target cluster (KUBECONFIG set, as it is on the demo box).

set -euo pipefail

NAMESPACE="orderfulfillment"
TIMEOUT="120s"

# Order matters only in that it is deterministic and easy to reason about; there is no dependency
# ordering between these six at the Kubernetes level (Kafka and Postgres are not restarted here).
# Frontend is last because it is the one entry here without the memory rationale that orders the
# other five — see the header comment.
DEPLOYMENTS=(
  order-service
  inventory-service
  payment-service
  fulfillment-service
  scenario-service
  frontend
)

while [[ $# -gt 0 ]]; do
  case "$1" in
    --timeout)
      TIMEOUT="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--timeout <duration>]" >&2
      exit 1
      ;;
  esac
done

for deployment in "${DEPLOYMENTS[@]}"; do
  echo "==> Restarting deployment/${deployment}" >&2
  kubectl rollout restart "deployment/${deployment}" -n "$NAMESPACE"

  echo "==> Waiting for deployment/${deployment} to become healthy (timeout ${TIMEOUT})" >&2
  if ! kubectl rollout status "deployment/${deployment}" -n "$NAMESPACE" --timeout="$TIMEOUT"; then
    echo "!! deployment/${deployment} did not become healthy within ${TIMEOUT}." >&2
    echo "!! Stopping here rather than restarting the remaining deployments on top of a failure." >&2
    echo "!! Investigate with: kubectl describe deployment/${deployment} -n ${NAMESPACE}" >&2
    echo "!!                   kubectl logs -n ${NAMESPACE} -l app=${deployment} --tail=100" >&2
    exit 1
  fi

  echo "==> deployment/${deployment} healthy" >&2
done

echo "==> All ${#DEPLOYMENTS[@]} deployments restarted and healthy." >&2
