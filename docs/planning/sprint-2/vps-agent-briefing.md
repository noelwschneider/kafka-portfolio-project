# VPS / Remote Development Workflow — Agent Briefing

Standalone context for whoever picks up this task. Written for a fresh Claude Code session with no
access to the conversation that produced it.

**Suggested model/effort:** Sonnet, medium effort. Well-specified execution against standard,
well-documented patterns (SSH auth, firewall rules, Docker install, a provisioning script), with a
couple of real judgment calls (the connection model, how to handle a changing IP per session) that
don't need frontier-level reasoning. Stakes are real but bounded and reversible.

## Project

`kafka-portfolio-project` — an event-driven order-fulfillment portfolio system (Java/Spring Boot,
Kafka, PostgreSQL, Kubernetes, React/TypeScript). Read `.claude/CLAUDE.md` first for repo norms; this
task mostly won't touch application code, so the rest of `docs/planning/` isn't required reading.

## Why this exists

The project's local dev environment (an M1 MacBook, 8GB RAM, Docker Desktop capped at ~3.8GB VM) has
a proven, hard ceiling: a `kind` Kubernetes cluster running this project's full stack at 3 replicas
per service (9 pods) causes genuine CPU/memory contention — Kafka readiness probes flap, pods
crash-loop. This isn't a bug to fix; it's confirmed hardware headroom, documented during the
Phase 10 scaling demo. Two Sprint 2 goals need to test past that ceiling:

- An **autoscaler** demo needs to actually watch pods scale up under load — which is exactly the
  scenario that hits the wall locally.
- A **bug hunt** pass is targeting concurrency bugs, which have gotten measurably worse at higher
  replica counts (a known race condition went from affecting 3/60 orders at 1 replica to 34/60 at 2
  — a trend likely worse at 3+, unreachable locally).

Neither goal needs every service at 3 replicas simultaneously — only one service scaled up at a
time — which is why the target spec below is smaller than an early draft of this briefing assumed.

## Existing reference material (read these before starting)

- `~/Documents/local-vs-cloud-dev-infra.md` — the decision record for this task. **Hetzner is the
  chosen platform, plan CPX32** (4 vCPU / 8GB RAM), billed hourly up to a ~$41.99/month cap — this
  box is provisioned per session and deleted when not in use, not run as a persistent server. Pursue
  Hetzner unless setup surfaces a real problem with the platform; if so, CPX42 (8 vCPU / 16GB) is the
  same-provider fallback. Oracle Cloud's free tier is out of scope for this task.
- This box is deliberately separate from Sprint 2 goal 4's production demo box (a different Hetzner
  server, plan CX23, always-on, ~€5.99/month). This box's job is to run deliberately-induced chaos,
  crash loops, and multi-replica load at will; the demo box's job is to stay up and boring for a
  stranger clicking a link. Don't conflate them in any doc or script this task produces. The sizing
  and cost analysis behind both boxes' current specs, including why hourly billing changes what "the
  deliverable" means for this task, is covered below.

## Goal

A remote development environment, created on demand and destroyed when not in use, with enough
headroom to run this project's full Kubernetes workload (5 backend services + frontend, one service
scaled to several replicas at a time) past the laptop's hardware ceiling — cheap specifically because
it doesn't persist.

## Concrete deliverables

1. **Automated provisioning — the primary deliverable, not a runbook.** A script (cloud-init,
   `hcloud` CLI, or equivalent) that goes from nothing to a working k3s box with SSH access and a
   firewall in one run. At an hourly rate, a manually-followed setup guide defeats the cost model by
   turning every session into an hour of setup first.
2. **Secured access, baked into the provisioning script** — SSH key auth only (no password auth), a
   firewall that doesn't expose the Docker/Kubernetes API to the public internet, no direct root
   login for day-to-day use.
3. **Docker + `k3s` (or `kind`) working on the box**, verified against this project's actual startup
   flow (the same `docker compose` / cluster-creation commands used locally should work there).
4. **A connection model that tolerates a changing IP every session** — a host alias updated by the
   provisioning script, or a floating IP if that proves simpler. Decide one concretely; don't leave
   it to be rediscovered on the second session.
5. **A `dev-up` / `dev-down` script pair** — `dev-up` provisions fresh or restores from the last
   snapshot; `dev-down` snapshots the disk and deletes the server. Not a shutdown script — powering a
   Hetzner server off does not stop billing, only deletion does, so a shutdown-only script would
   silently fail to save any money.
6. **A setup/usage doc**, in the same spirit as `~/Documents/docker-external-drive-setup.md` — how to
   run `dev-up`/`dev-down`, day-to-day usage, what's safe and unsafe, a revert/cleanup procedure.
   Write it to describe the finished setup only — no narration of alternatives considered or how the
   doc itself evolved.

## Constraints

- **Account creation and payment cannot be performed by an AI agent** — signing up with the VPS
  provider and entering billing details has to be done by the user directly. Guide through it, don't
  attempt it. Everything after the account exists (provisioning via CLI/API with an API token, SSH
  setup, software install) is fair game to do directly.
- **The realistic cost risk is a forgotten box, not metered usage.** A CPX32 left running a full month
  is ~$41.99 instead of ~$1–4 for a few real sessions. `dev-down` deleting (not stopping) the server
  is the single most important detail in this task — get it wrong and the whole cost model silently
  fails. A monthly audit (`hcloud server list` / `image list --type snapshot` / `volume list`) is
  worth building in as a habit, not just documenting.
- A snapshot generally can't be restored onto a plan with a smaller disk than it was taken from —
  take snapshots from the smallest plan this workflow may ever restore onto.
- This task runs independently of the rest of Sprint 2's work and shouldn't need to touch application
  code. If it does turn out to need repo changes (e.g. a `Makefile` target, a small doc addition),
  keep them minimal and commit them separately from anything another agent is doing in this repo at
  the same time.

## When done

Report back (to the user, who is coordinating this against a separate Sprint 2 planning thread):
what the provisioning script does, the access and connection model chosen, verified proof the
project's Kubernetes workload runs there past the old ceiling, confirmation `dev-down` actually stops
billing (not just stops the process), and the path to the setup doc.

## Starter prompt

The message used to start this session (included for reference, not as an additional instruction —
everything it refers to is covered above):

> This project has an external planning doc at `docs/planning/sprint-2/vps-agent-briefing.md` — read
> it first, it has everything you need (why, what's already been tried, the goal, and the
> deliverables). We're setting up a Hetzner (or equivalent) VPS as a remote dev environment for a
> Kubernetes-heavy project that's outgrown a MacBook's hardware. Let's start with getting the account
> created — walk me through what you need from me for that part, then take it from there.
