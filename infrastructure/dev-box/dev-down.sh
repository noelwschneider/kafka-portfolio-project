#!/usr/bin/env bash
# Tears down the kafka-portfolio-project remote dev box: snapshots the disk, then DELETES the
# server. Deletion is what stops billing on Hetzner -- powering off does not. Also prunes old
# snapshots, keeping only the most recent one, so snapshot storage doesn't accumulate.
#
# See ~/Documents/hetzner-dev-box-setup.md for day-to-day usage.

set -euo pipefail

SERVER_NAME="kafka-portfolio-dev-box"
TOKEN_FILE="$HOME/.config/hcloud-dev-box/token"

if [[ ! -f "$TOKEN_FILE" ]]; then
  echo "ERROR: Hetzner API token not found at $TOKEN_FILE" >&2
  exit 1
fi
export HCLOUD_TOKEN
HCLOUD_TOKEN="$(cat "$TOKEN_FILE")"

hc() { hcloud "$@"; }

if ! hc server describe "$SERVER_NAME" >/dev/null 2>&1; then
  echo "No server named '$SERVER_NAME' exists. Nothing to snapshot or delete."
  echo "(This is not an error -- it just means dev-down has nothing to do right now.)"
  exit 0
fi

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
SNAPSHOT_DESCRIPTION="${SERVER_NAME}-${TIMESTAMP}"

echo "==> Snapshotting '$SERVER_NAME' disk as '$SNAPSHOT_DESCRIPTION'..."
hc server create-image --type snapshot --description "$SNAPSHOT_DESCRIPTION" "$SERVER_NAME"

echo "==> Deleting server '$SERVER_NAME' (this is what stops billing)..."
hc server delete "$SERVER_NAME"

echo "==> Verifying server is gone..."
if hc server describe "$SERVER_NAME" >/dev/null 2>&1; then
  echo "ERROR: server still shows up after delete. Check 'hcloud server list' manually." >&2
  exit 1
fi
echo "Confirmed: '$SERVER_NAME' no longer exists."

echo "==> Pruning old snapshots (keeping only the most recent one for '$SERVER_NAME')..."
# Avoid `mapfile` / associative arrays for compatibility with macOS's stock bash (3.2).
SNAPSHOT_ID_LIST="$(hc image list --type snapshot -o noheader -o columns=id,description \
  | grep "$SERVER_NAME" | sort -k2 -r | awk '{print $1}')"
KEEP_ONE=1
while IFS= read -r SNAP_ID; do
  [[ -z "$SNAP_ID" ]] && continue
  if [[ "$KEEP_ONE" -eq 1 ]]; then
    KEEP_ONE=0
    continue
  fi
  echo "Deleting old snapshot ID $SNAP_ID..."
  hc image delete "$SNAP_ID"
done <<< "$SNAPSHOT_ID_LIST"
echo "Snapshots remaining for '$SERVER_NAME':"
hc image list --type snapshot -o columns=id,description,created | grep -E "ID|$SERVER_NAME" || true

echo ""
echo "=== dev-down complete ==="
echo "Server deleted, snapshot kept. Billing for compute has stopped; only snapshot storage"
echo "(a few cents/month) continues until the next dev-down prunes it or you delete it manually."
