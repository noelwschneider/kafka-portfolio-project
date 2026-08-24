# Hetzner Remote Dev Box Setup

**Why this exists:** the M1 MacBook's Docker Desktop VM (capped ~3.8GB) can't run this project's
full Kubernetes workload at higher replica counts without Kafka readiness probes flapping and pods
crash-looping. This is a Hetzner Cloud VPS, created on demand and destroyed after each session, that
provides enough headroom (4 vCPU / 8GB RAM) to run past that ceiling — cheap specifically because it
only exists while it's being used.

Scripts live in the repo at `infrastructure/dev-box/`:
- `dev-up.sh` — provisions the box (or restores it from the last snapshot) and updates SSH config.
- `dev-down.sh` — snapshots the disk and deletes the server.
- `cloud-init.yaml.tmpl` — the first-boot provisioning template `dev-up.sh` uses on a fresh box.

---

## Day-to-day usage

### Starting a session

```
cd ~/Documents/HelloWorld/kafka-portfolio-project/infrastructure/dev-box
./dev-up.sh
```

This either restores the box from the most recent snapshot (fast — everything is already
installed) or, if no snapshot exists, provisions a fresh Ubuntu 24.04 server and installs Docker,
`kind`, and `kubectl` via cloud-init (takes a few minutes the first time). Either way it ends with:

- The server running on Hetzner Cloud (plan **cpx32**, 4 vCPU / 8GB RAM / 160GB disk, location
  `nbg1`), reachable only on port 22 (firewall blocks everything else, including the Docker/K8s
  API).
- `~/.ssh/config` updated with a managed block (between `# BEGIN kafka-portfolio-dev-box` /
  `# END kafka-portfolio-dev-box`) pointing the alias `kafka-dev-box` at the box's current IP — the
  IP changes every time the box is recreated, so always connect via the alias, never a
  hardcoded IP.

### Connecting

```
ssh kafka-dev-box
```

Logs in as the non-root `dev` user (passwordless sudo). Root login and password auth are both
disabled — only the dedicated key at `~/.ssh/kafka-portfolio-dev-box` works.

### Getting the project onto the box

The box doesn't auto-sync the repo. Push your working tree over with rsync (fast, only sends
diffs) — from your Mac:

```
rsync -az --delete \
  --exclude '.git' --exclude 'node_modules' --exclude '**/target' --exclude '**/dist' \
  -e "ssh -o StrictHostKeyChecking=accept-new" \
  ~/Documents/HelloWorld/kafka-portfolio-project/ kafka-dev-box:~/kafka-portfolio-project/
```

Re-run this whenever you want to push local changes over; it's incremental.

### Running the stack

Both of the project's normal dev flows work unmodified on the box, once connected:

**Docker Compose (modular-monolith phase):**
```
ssh kafka-dev-box
cd ~/kafka-portfolio-project
docker compose up -d --build
docker compose ps
```

**kind / Kubernetes phase:**
```
ssh kafka-dev-box
cd ~/kafka-portfolio-project
kind create cluster --config infrastructure/kind-config.yaml
# tag the compose-built images the k8s manifests expect (order-service:local, etc.)
for svc in order-service inventory-service payment-service fulfillment-service scenario-service frontend; do
  docker tag kafka-portfolio-project-$svc:latest $svc:local
done
kind load docker-image order-service:local inventory-service:local payment-service:local \
  fulfillment-service:local scenario-service:local frontend:local --name orderfulfillment
kubectl apply -f infrastructure/kubernetes/00-namespace.yaml
kubectl apply -f infrastructure/kubernetes/  # apply the rest in numeric order if not using -f on the dir directly
```

Scale a service past what the laptop can handle, e.g.:
```
kubectl -n orderfulfillment scale deployment/order-service --replicas=3
kubectl -n orderfulfillment get pods -l app=order-service
```

### Ending a session

```
cd ~/Documents/HelloWorld/kafka-portfolio-project/infrastructure/dev-box
./dev-down.sh
```

This snapshots the disk, then **deletes the server**. Deletion is what stops billing — powering off
does not. `dev-down` also prunes old snapshots, keeping only the most recent one, so snapshot
storage doesn't quietly accumulate across sessions.

**Always run `dev-down` before walking away**, even for a short break. There is no
auto-shutdown; a forgotten box bills continuously up to the plan's monthly cap.

---

## What's safe and what's not

**Safe:**
- Running `dev-up` any number of times — it detects an existing server and won't create a
  duplicate, and detects an existing snapshot and restores from it instead of reprovisioning.
- Running `dev-down` with nothing to delete — it prints a message and exits cleanly, it does not
  error or do anything destructive.
- Letting the box sit idle for a few minutes mid-session — you're just paying the hourly rate
  (~$0.0673/hr for cpx32) while it exists.

**Not safe / to avoid:**
- **Do not create a Hetzner Floating IP for this box.** It costs $3.50/month for as long as it
  exists, *including while the server is deleted* — that would silently defeat the whole
  per-session cost model. The host-alias approach in `dev-up.sh` exists specifically to avoid
  needing one.
- **Do not rely on powering the server off to save money.** A stopped-but-not-deleted Hetzner
  server still bills. Only `hcloud server delete` (which is what `dev-down.sh` does after
  snapshotting) actually stops billing.
- **Do not leave a session running unattended** (e.g. overnight, or "I'll get back to it
  tomorrow"). There's no idle timeout — run `dev-down` before stepping away for any real length of
  time. If you forget, the worst case is bounded by Hetzner's monthly cap (~$41.99 for cpx32 run
  24/7 a full month), but that's still 10-20x a normal month's actual usage.
- **Don't provision a second box "just to test something."** The scripts refuse to create a
  duplicate under the same name, but a manually-created second server (different name) wouldn't be
  caught by that check — keep to one box.

### Monthly audit (worth doing as a habit)

```
export HCLOUD_TOKEN=$(cat ~/.config/hcloud-dev-box/token)
hcloud server list          # should be empty unless a session is actively in progress
hcloud image list --type snapshot   # should show at most one snapshot
hcloud volume list          # should be empty -- this setup doesn't use standalone volumes
```

Anything unexpected here (a server that shouldn't still exist, more than one snapshot, any
volume) is the actual cost risk for this setup — not metered usage, which is capped and cheap.

---

## Revert / cleanup procedure

If something goes wrong mid-session (script errors out, box is unreachable, etc.), clean up
manually rather than leaving the account in an unknown state:

```
export HCLOUD_TOKEN=$(cat ~/.config/hcloud-dev-box/token)

# Force-delete a stuck server (skips the snapshot step -- use only if dev-down.sh itself is
# broken; you lose whatever wasn't already snapshotted):
hcloud server delete kafka-portfolio-dev-box

# Check for and clean up orphaned snapshots (keep at most the most recent one):
hcloud image list --type snapshot
hcloud image delete <old-snapshot-id>

# Check for and clean up orphaned volumes (this setup doesn't attach any, so any listed here
# are unexpected):
hcloud volume list
hcloud volume delete <volume-id>

# Check for and clean up the firewall / SSH key if you want to fully decommission this setup
# (not needed for normal use -- dev-up.sh reuses them across sessions):
hcloud firewall delete kafka-portfolio-dev-box-fw
hcloud ssh-key delete kafka-portfolio-dev-box
```

If `~/.ssh/config`'s managed block ever points at a stale/wrong IP (e.g. after a manual cleanup
outside the scripts), just re-run `dev-up.sh` — it always rewrites that block from the current
server's actual IP. To remove the block entirely, delete the lines between
`# BEGIN kafka-portfolio-dev-box` and `# END kafka-portfolio-dev-box` in `~/.ssh/config` by hand.

---

## Reference

- **Provider / plan:** Hetzner Cloud, server type `cpx32` (4 vCPU / 8GB RAM / 160GB disk), location
  `nbg1`. Fallback if `cpx32` is ever out of stock: `cpx42` (8 vCPU / 16GB), which `dev-up.sh`
  switches to automatically after a live availability check.
- **Pricing (verified live, account bills in USD):** cpx32 is $0.0673/hour, capped at $41.99/month
  if run continuously for a full month. Realistic cost for occasional sessions is a few dollars a
  month — see `docs/agent-reports/sprint-2/dev-box-cost-management-plan.md` in the repo for the
  full breakdown.
- **API token:** `~/.config/hcloud-dev-box/token` (chmod 600, not in the repo).
- **Dedicated SSH keypair:** `~/.ssh/kafka-portfolio-dev-box` / `.pub` — used only for this box, not
  shared with any other SSH identity.
- **This box is separate from Sprint 2's production demo box** (a different, always-on Hetzner
  server). Don't confuse the two -- this one is for chaos/load testing and gets destroyed after
  every session; the demo box stays up.
