---
name: dev-box
description: Stand up the Hetzner dev box for resource-heavy verification (kind/K8s at scale, or a
  second full-stack rebuild that can't be serialized with one already running locally), and tear it
  down afterward. Orchestrator-invoked only — never delegated to a subagent to decide on its own.
disable-model-invocation: true
allowed-tools: Bash(hcloud *), Bash(infrastructure/dev-box/*), Bash(rsync *), Bash(ssh kafka-dev-box*)
---

# Dev box for resource-heavy verification

This is the orchestrator's tool, not a subagent's. Every preset's boundary rule says provisioning or
connecting to the dev box is off-limits on a subagent's own initiative — if you're reading this from
inside a delegated task, stop and report back instead of continuing.

## Live state at invocation

- Existing dev-box server: !`HCLOUD_TOKEN=$(cat ~/.config/hcloud-dev-box/token) hcloud server list 2>&1`

If `kafka-portfolio-dev-box` already appears, someone else's session may still be using it — don't
tear it down out from under them; check before running `dev-down.sh`.

## Before anything else

Confirm the task actually needs this rather than defaulting to it because local looked slow. Per
`docs/workflow/agent-workflow.md`'s dev-box usage policy, route here only for:

- `kind`/Kubernetes work at a scale the local ~3.8GB Docker Desktop cap can't hold, or
- more than one full-stack rebuild (`docker compose up --build -d` with no service arguments) that
  would otherwise need to run concurrently and can't just be serialized.

Local `docker-compose` stays the default for everything else — it's free, needs no credentials, and
is what the large majority of delegated work has always run against without incident.

## Lifecycle

1. `infrastructure/dev-box/dev-up.sh` — provisions (or restores from the last snapshot) the box.
2. Sync the working tree — the box does not auto-sync:
   ```bash
   rsync -az --delete --exclude '.git' --exclude 'node_modules' --exclude '**/target' \
     --exclude '**/dist' -e "ssh -o StrictHostKeyChecking=accept-new" \
     ~/Documents/HelloWorld/kafka-portfolio-project/ kafka-dev-box:~/kafka-portfolio-project/
   ```
3. `ssh kafka-dev-box` and run the same `docker compose` / `kind` commands used locally.
4. `infrastructure/dev-box/dev-down.sh` — snapshots the disk then **deletes** the server; deletion,
   not power-off, is what stops billing. Run this even if the task fails partway — do not leave it
   for a later turn to remember. There is no auto-shutdown; a forgotten box bills continuously.

## Cost

The box is a Hetzner `cpx32` (4 vCPU / 8GB RAM, up to $41.99/month if left running). State this before
provisioning if the developer hasn't already approved dev-box usage for this task.
