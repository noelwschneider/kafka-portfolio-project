#!/usr/bin/env bash
# Creates (or rotates) the postgres-credentials Secret for a production deployment, with a
# freshly generated password instead of the dev value committed in
# infrastructure/kubernetes/01-secrets.yaml. See README.md in this directory for context (Sprint 2
# goal 1, work item W7).
#
# Usage:
#   ./create-postgres-secret.sh            # create; refuses to overwrite an existing secret
#   ./create-postgres-secret.sh --rotate   # replace an existing secret with a new password
#
# Requires: kubectl pointed at the target cluster, openssl.

set -euo pipefail

NAMESPACE="orderfulfillment"
SECRET_NAME="postgres-credentials"
ROTATE=false

if [[ "${1:-}" == "--rotate" ]]; then
  ROTATE=true
fi

if kubectl get secret "$SECRET_NAME" -n "$NAMESPACE" >/dev/null 2>&1; then
  if [[ "$ROTATE" != true ]]; then
    echo "Secret '$SECRET_NAME' already exists in namespace '$NAMESPACE'. Pass --rotate to replace it." >&2
    exit 1
  fi
  echo "Rotating existing secret '$SECRET_NAME'..." >&2
fi

PASSWORD="$(openssl rand -base64 24)"

kubectl create secret generic "$SECRET_NAME" \
  --namespace "$NAMESPACE" \
  --from-literal=POSTGRES_DB=orderfulfillment \
  --from-literal=POSTGRES_USER=orderfulfillment \
  --from-literal=POSTGRES_PASSWORD="$PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Secret '$SECRET_NAME' applied in namespace '$NAMESPACE'." >&2
if [[ "$ROTATE" == true ]]; then
  echo "Restart dependent deployments to pick up the new password:" >&2
  echo "  kubectl rollout restart deployment -n $NAMESPACE" >&2
fi
