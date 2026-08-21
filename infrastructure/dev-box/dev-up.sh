#!/usr/bin/env bash
# Provisions (or restores) the kafka-portfolio-project remote dev box on Hetzner Cloud.
#
# Model: create-per-session, not persistent. If a snapshot from a prior dev-down exists, this
# restores from it; otherwise it provisions fresh via cloud-init. Either way it ends with the box
# reachable at `ssh kafka-dev-box`, firewall applied (SSH only), and ~/.ssh/config updated.
#
# See ~/Documents/hetzner-dev-box-setup.md for day-to-day usage.

set -euo pipefail

SERVER_NAME="kafka-portfolio-dev-box"
FIREWALL_NAME="kafka-portfolio-dev-box-fw"
SSH_KEY_NAME="kafka-portfolio-dev-box"
SNAPSHOT_LABEL_KEY="dev-box"
SNAPSHOT_LABEL_VALUE="kafka-portfolio"
SERVER_TYPE="cpx32"
FALLBACK_SERVER_TYPE="cpx42"
LOCATION="nbg1"
BASE_IMAGE="ubuntu-24.04"

TOKEN_FILE="$HOME/.config/hcloud-dev-box/token"
SSH_PRIVATE_KEY="$HOME/.ssh/kafka-portfolio-dev-box"
SSH_PUBLIC_KEY="$HOME/.ssh/kafka-portfolio-dev-box.pub"
SSH_CONFIG="$HOME/.ssh/config"
SSH_ALIAS="kafka-dev-box"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLOUD_INIT_TEMPLATE="$SCRIPT_DIR/cloud-init.yaml.tmpl"

if [[ ! -f "$TOKEN_FILE" ]]; then
  echo "ERROR: Hetzner API token not found at $TOKEN_FILE" >&2
  exit 1
fi
export HCLOUD_TOKEN
HCLOUD_TOKEN="$(cat "$TOKEN_FILE")"

if [[ ! -f "$SSH_PUBLIC_KEY" ]]; then
  echo "ERROR: dev-box SSH public key not found at $SSH_PUBLIC_KEY" >&2
  echo "Generate it with: ssh-keygen -t ed25519 -f $SSH_PRIVATE_KEY -N '' -C '$SSH_KEY_NAME'" >&2
  exit 1
fi

hc() { hcloud "$@"; }

echo "==> Checking for an existing server named '$SERVER_NAME'..."
if hc server describe "$SERVER_NAME" >/dev/null 2>&1; then
  echo "A server named '$SERVER_NAME' already exists. Not creating a second one."
  EXISTING_IP="$(hc server ip "$SERVER_NAME")"
  echo "Existing server IP: $EXISTING_IP"
  UPDATE_SSH_CONFIG_ONLY=1
else
  UPDATE_SSH_CONFIG_ONLY=0
fi

if [[ "$UPDATE_SSH_CONFIG_ONLY" -eq 0 ]]; then
  echo "==> Ensuring SSH key '$SSH_KEY_NAME' is registered with Hetzner..."
  if ! hc ssh-key describe "$SSH_KEY_NAME" >/dev/null 2>&1; then
    hc ssh-key create --name "$SSH_KEY_NAME" --public-key-from-file "$SSH_PUBLIC_KEY"
  else
    echo "SSH key already registered."
  fi

  echo "==> Ensuring firewall '$FIREWALL_NAME' exists (SSH only, nothing else exposed)..."
  if ! hc firewall describe "$FIREWALL_NAME" >/dev/null 2>&1; then
    hc firewall create --name "$FIREWALL_NAME"
    hc firewall add-rule "$FIREWALL_NAME" \
      --direction in --protocol tcp --port 22 \
      --source-ips 0.0.0.0/0 --source-ips ::/0 \
      --description "SSH"
  else
    echo "Firewall already exists."
  fi

  echo "==> Checking for a server type '$SERVER_TYPE' in '$LOCATION'..."
  ACTUAL_SERVER_TYPE="$SERVER_TYPE"
  if ! hc server-type describe "$SERVER_TYPE" -o json 2>/dev/null | grep -q "\"$LOCATION\""; then
    echo "WARNING: $SERVER_TYPE not available in $LOCATION right now. Falling back to $FALLBACK_SERVER_TYPE."
    ACTUAL_SERVER_TYPE="$FALLBACK_SERVER_TYPE"
  fi

  echo "==> Looking for a snapshot to restore from..."
  SNAPSHOT_ID="$(hc image list --type snapshot -o noheader -o columns=id,description \
    | grep "$SERVER_NAME" | sort -k2 -r | head -n1 | awk '{print $1}' || true)"

  if [[ -n "${SNAPSHOT_ID:-}" ]]; then
    echo "Restoring from snapshot ID $SNAPSHOT_ID."
    IMAGE_TO_USE="$SNAPSHOT_ID"
    USER_DATA_ARGS=()
  else
    echo "No snapshot found. Provisioning fresh from $BASE_IMAGE with cloud-init."
    IMAGE_TO_USE="$BASE_IMAGE"
    RENDERED_CLOUD_INIT="$(mktemp)"
    trap 'rm -f "$RENDERED_CLOUD_INIT"' EXIT
    PUBKEY_CONTENT="$(cat "$SSH_PUBLIC_KEY")"
    # Substitute the placeholder token in place, preserving the rest of its line (the YAML list
    # dash and indentation) -- do NOT replace the whole line, that would drop the "- " and corrupt
    # the ssh_authorized_keys YAML list.
    sed "s|__SSH_PUBLIC_KEY__|${PUBKEY_CONTENT}|" "$CLOUD_INIT_TEMPLATE" > "$RENDERED_CLOUD_INIT"
    USER_DATA_ARGS=(--user-data-from-file "$RENDERED_CLOUD_INIT")
  fi

  echo "==> Creating server '$SERVER_NAME' ($ACTUAL_SERVER_TYPE @ $LOCATION, image=$IMAGE_TO_USE)..."
  hc server create \
    --name "$SERVER_NAME" \
    --type "$ACTUAL_SERVER_TYPE" \
    --location "$LOCATION" \
    --image "$IMAGE_TO_USE" \
    --ssh-key "$SSH_KEY_NAME" \
    --firewall "$FIREWALL_NAME" \
    ${USER_DATA_ARGS[@]+"${USER_DATA_ARGS[@]}"}

  IS_FRESH_PROVISION=1
  if [[ -n "${SNAPSHOT_ID:-}" ]]; then
    IS_FRESH_PROVISION=0
  fi

  EXISTING_IP="$(hc server ip "$SERVER_NAME")"
fi

echo "==> Server IP: $EXISTING_IP"

echo "==> Updating $SSH_CONFIG (managed block for host alias '$SSH_ALIAS')..."
BEGIN_MARK="# BEGIN kafka-portfolio-dev-box"
END_MARK="# END kafka-portfolio-dev-box"
TMP_SSH_CONFIG="$(mktemp)"
if [[ -f "$SSH_CONFIG" ]]; then
  awk -v begin="$BEGIN_MARK" -v end="$END_MARK" '
    $0==begin {skip=1}
    !skip {print}
    $0==end {skip=0}
  ' "$SSH_CONFIG" > "$TMP_SSH_CONFIG"
else
  : > "$TMP_SSH_CONFIG"
fi
{
  cat "$TMP_SSH_CONFIG"
  echo "$BEGIN_MARK"
  echo "Host $SSH_ALIAS"
  echo "    HostName $EXISTING_IP"
  echo "    User dev"
  echo "    IdentityFile $SSH_PRIVATE_KEY"
  echo "    StrictHostKeyChecking accept-new"
  echo "    UserKnownHostsFile $HOME/.ssh/known_hosts_kafka_dev_box"
  echo "$END_MARK"
} > "$SSH_CONFIG.new"
mv "$SSH_CONFIG.new" "$SSH_CONFIG"
chmod 600 "$SSH_CONFIG"
rm -f "$TMP_SSH_CONFIG"
# Fresh box each time -> fresh host key. Drop any stale known_hosts entry for this alias's file.
rm -f "$HOME/.ssh/known_hosts_kafka_dev_box"

echo "==> Waiting for SSH to come up on $EXISTING_IP..."
for i in $(seq 1 60); do
  if ssh -o ConnectTimeout=5 -o BatchMode=yes "$SSH_ALIAS" true 2>/dev/null; then
    echo "SSH is up."
    break
  fi
  sleep 5
  if [[ "$i" -eq 60 ]]; then
    echo "ERROR: SSH did not come up within 5 minutes." >&2
    exit 1
  fi
done

if [[ "${IS_FRESH_PROVISION:-0}" -eq 1 ]]; then
  echo "==> Fresh provision: waiting for cloud-init to finish (Docker/kind/kubectl install)..."
  for i in $(seq 1 60); do
    if ssh "$SSH_ALIAS" test -f /home/dev/.dev-box-ready 2>/dev/null; then
      echo "cloud-init finished."
      break
    fi
    sleep 10
    if [[ "$i" -eq 60 ]]; then
      echo "ERROR: cloud-init did not finish within 10 minutes. SSH in and check 'cloud-init status' / /var/log/cloud-init-output.log." >&2
      exit 1
    fi
  done
else
  echo "==> Restored from snapshot: Docker/kind/kubectl already installed, skipping cloud-init wait."
fi

echo ""
echo "=== Dev box ready ==="
echo "Connect with:  ssh $SSH_ALIAS"
echo "IP:            $EXISTING_IP"
echo "Type:          ${ACTUAL_SERVER_TYPE:-already running}"
echo "Reminder:      run ./dev-down.sh when you're done to stop billing (deletes the server)."
