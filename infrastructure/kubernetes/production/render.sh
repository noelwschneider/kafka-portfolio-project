#!/usr/bin/env bash
# Renders the production overlay to stdout.
#
#   ./render.sh                 # the overlay with the base manifests' :local image names
#   ./render.sh ghcr            # what actually gets deployed to the demo box (GHCR images)
#   ./render.sh local-verify    # the same overlay against locally-built kind images
#
# Apply it by piping into kubectl, which keeps the apply step explicit and reviewable:
#
#   ./render.sh ghcr | kubectl apply -f -
#
# Why this wrapper exists rather than `kubectl apply -k`: the overlay's `resources:` point at the
# base manifests one directory up (`../04-order-service.yaml` and friends), and kustomize refuses
# to load files outside the kustomization root unless the load restrictor is turned off.
# `kubectl kustomize` takes that flag; `kubectl apply -k` does not. The alternative — a
# kustomization.yaml inside infrastructure/kubernetes/ — would break the local flow, because
# `kubectl apply -f infrastructure/kubernetes/` applies every YAML file in that directory and
# would choke on a kustomization file. Keeping the base directory pure Kubernetes objects is the
# constraint; this script is the small price for it.
set -euo pipefail

overlay="${1:-}"
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)${overlay:+/$overlay}"

if [ ! -f "$dir/kustomization.yaml" ]; then
  echo "no kustomization.yaml in $dir" >&2
  exit 1
fi

exec kubectl kustomize --load-restrictor=LoadRestrictionsNone "$dir"
