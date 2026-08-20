# VPS / Remote Development Workflow — Agent Briefing

Standalone context for whoever picks up this task. Written for a fresh Claude Code session with no
access to the conversation that produced it.

**Suggested model/effort:** Sonnet, medium effort. Well-specified execution against standard,
well-documented patterns (SSH auth, firewall rules, Docker install), with one real judgment call
(the connection model) that doesn't need frontier-level reasoning. Stakes are real but bounded and
reversible.

## Project

`kafka-portfolio-project` — an event-driven order-fulfillment portfolio system (Java/Spring Boot,
Kafka, PostgreSQL, Kubernetes, React/TypeScript). Read `.claude/CLAUDE.md` first for repo norms; this
task mostly won't touch application code, so the rest of `docs/planning/` isn't required reading.

## Why this exists

The project's local dev environment (an M1 MacBook, 8GB RAM, Docker Desktop capped at ~3.8GB VM) has
a proven, hard ceiling: a `kind` Kubernetes cluster running this project's full stack at 3 replicas
per service (9 pods) causes genuine CPU/memory contention — Kafka readiness probes flap, pods
crash-loop. This isn't a bug to fix; it's confirmed hardware headroom, documented in
`docs/agent-reports/phase-10-scaling-demo.md`. Two Sprint 2 goals need to test past that ceiling:

- An **autoscaler** demo needs to actually watch pods scale up under load — which is exactly the
  scenario that hits the wall locally.
- A **bug hunt** pass is targeting concurrency bugs, which have gotten measurably worse at higher
  replica counts (a known race condition went from affecting 3/60 orders at 1 replica to 34/60 at 2
  — a trend likely worse at 3+, unreachable locally).

Both are blocked on this task landing a working remote environment with real headroom.

## Existing reference material (read these before starting)

- `~/Documents/docker-external-drive-setup.md` — the current *local* Docker setup (an external SSD
  as Docker's data volume, mitigating a related-but-different disk-I/O problem from an earlier
  session). Useful for understanding what's already been tried and why it isn't sufficient — that
  fix solved a disk-I/O bottleneck, not the CPU/memory ceiling this task addresses.
- `~/Documents/local-vs-cloud-dev-infra.md` — the decision record for this task. **Hetzner is the
  chosen platform** (CX33: 16GB RAM / 4 vCPU / ~€24/month), pricing already confirmed. Pursue Hetzner
  unless setup surfaces a real problem with the platform; if so, DigitalOcean's equivalent 16GB
  droplet is the fallback. Oracle Cloud's free tier is out of scope for this task.

## Goal

A working remote development environment with enough headroom to run this project's full Kubernetes
workload (5 backend services + frontend, at least 3 replicas each) without hitting the local ceiling,
usable for normal day-to-day development — not a one-off test box.

## Concrete deliverables

1. **A provisioned VPS** — Hetzner CX33 or equivalent (16GB RAM / 4 vCPU class), sized so 9+ pods
   under load has real headroom, not just barely more than the current 3.8GB ceiling.
2. **Secured access** — SSH key auth only (no password auth), a firewall that doesn't expose the
   Docker/Kubernetes API to the public internet, no direct root login for day-to-day use.
3. **Docker + `kind` working on the box**, verified against this project's actual startup flow (the
   same `docker compose` / `kind create cluster` commands used locally should work there).
4. **A comfortable day-to-day workflow** from the laptop to the VPS — decide and implement one
   concrete model (e.g. VS Code Remote-SSH, a remote Docker context, or SSH-ing in directly to run
   Claude Code on the box) rather than leaving it ambiguous. State which one was chosen and why.
5. **A setup/usage doc**, in the same spirit as `~/Documents/docker-external-drive-setup.md` — how to
   start/stop the box, day-to-day usage, what's safe and unsafe, a revert/cleanup procedure. Write it
   to describe the finished setup only — no narration of alternatives considered or how the doc itself
   evolved; that belongs in this conversation, not in the reference doc.

## Constraints

- **Account creation and payment cannot be performed by an AI agent** — signing up with the VPS
  provider and entering billing details has to be done by the user directly. Guide through it, don't
  attempt it. Everything after the account exists (provisioning via CLI/API with an API token, SSH
  setup, software install) is fair game to do directly.
- Cost control matters — the research doc flags "remember to shut down" as real mental overhead
  ($16–24/month becomes $190–290/year if left running continuously by accident). Consider a
  start/stop script or scheduled shutdown as part of the deliverable, not an afterthought.
- This task runs independently of the rest of Sprint 2's work and shouldn't need to touch application
  code. If it does turn out to need repo changes (e.g. a `Makefile` target, a small doc addition),
  keep them minimal and commit them separately from anything another agent is doing in this repo at
  the same time.

## When done

Report back (to the user, who is coordinating this against a separate Sprint 2 planning thread):
what was provisioned, the access model chosen, verified proof the project's Kubernetes workload runs
there past the old ceiling, and the path to the setup doc.

## Starter prompt

The message used to start this session (included for reference, not as an additional instruction —
everything it refers to is covered above):

> This project has an external planning doc at `docs/planning/sprint-2/vps-agent-briefing.md` — read
> it first, it has everything you need (why, what's already been tried, the goal, and the
> deliverables). We're setting up a Hetzner (or equivalent) VPS as a remote dev environment for a
> Kubernetes-heavy project that's outgrown a MacBook's hardware. Let's start with getting the account
> created — walk me through what you need from me for that part, then take it from there.
