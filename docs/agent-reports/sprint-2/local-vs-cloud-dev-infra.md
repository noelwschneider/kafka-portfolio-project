# Dev Infra Decision: Hetzner VPS

## Context

M1 MacBook, 8GB RAM, Docker Desktop capped at ~3.8GB VM. This project's full-stack deployment (5
backend services + frontend, 9 pods at 3 replicas) hits that ceiling — Kafka readiness probes flap,
pods crash-loop. Confirmed hardware headroom, not a fixable bug.

## Decision

**Pursuing a Hetzner VPS, unless setup surfaces a real problem with the platform.** Oracle Cloud's
free tier is out of scope — its capacity/provisioning/account-suspension risk isn't worth building a
workflow around, free or not. Staying local isn't viable long-term either: the ceiling blocks
multi-replica scaling and chaos/failure-scenario testing, which this project specifically wants to
demonstrate.

## Target spec — CPX32, provisioned per session, not persistent

Hetzner CPX32 — 4 vCPU, 8GB RAM. Hetzner bills this plan hourly up to a $41.99/month cap, so an
8-hour working session costs ~€0.39, not a monthly subscription. 8GB covers what this box's two
jobs (autoscaler headroom, bug-hunt concurrency testing) actually need — neither requires every
service at 3 replicas simultaneously, only one service scaled up at a time. If CPX32 is ever
unavailable, CPX42 (8 vCPU / 16GB, ~€0.095/hour) is the fallback; the per-hour cost difference is
small enough not to matter for occasional sessions.

This box is deliberately a separate server from Sprint 2's production demo box (Hetzner CX23,
always-on, ~€5.99/month). See `docs/agent-reports/sprint-2/dev-vs-demo-host-separation.md` for why
combining them doesn't work: this box's job is to run deliberately-induced chaos and multi-replica
load; the demo box's job is to stay up and boring for a stranger.

## The model: create it when working, delete it when not

At an hourly rate, this box is cheap specifically *because* it doesn't persist — the entire cost
model depends on recreating it being fast and automated, not on remembering to shut it down.

- **Powering the server off does not stop billing on Hetzner** — only deletion does. The pattern is
  snapshot-then-delete, restoring from the snapshot (or reprovisioning from scratch) when work
  resumes.
- **Provisioning must therefore be automated** (a script — cloud-init, `hcloud` CLI, or equivalent —
  that goes from nothing to a working k3s box), not a hand-followed runbook. A manual setup process
  defeats the model by turning every session into an hour of setup first.
- **The connection model must assume the box's IP changes every time it's recreated.** A host alias
  or a small script that updates SSH config after provisioning is part of this, not an afterthought
  discovered on the second session.

## Cost to budget for

- **Egress bandwidth:** 20TB included per server on Hetzner — effectively unreachable for this
  workload.
- **Snapshot retention while deleted:** billed on compressed used space, roughly €0.10–0.30/month for
  a box with a repo and some images on it.
- **Realistic total:** ~€1.20–4/month for a few sessions a month; the actual overrun risk is a
  forgotten box left running ($41.99/month if that happens for a full month on CPX32), not metered
  usage. A monthly two-minute audit (`hcloud server list` / `image list --type snapshot` / `volume
  list`) catches anything orphaned.

## Setup complexity

1. An automated provisioning script (console or CLI) that stands up a working k3s box from nothing —
   the primary deliverable, not a one-time manual walkthrough.
2. SSH key auth + firewall baked into that script — don't expose ports to the public internet.
3. Docker and `kind`/`k3s` verified working as part of the same script.
4. A connection model that tolerates a changing IP each session (host alias update, or a floating
   IP if that proves simpler). Pick one and document it.
5. A `dev-up` / `dev-down` pair — `dev-up` provisions or restores from snapshot, `dev-down` snapshots
   and deletes. Not a shutdown script; shutdown alone would not stop billing.

## Day-to-day workflow characteristics

- Feels nearly local with VS Code Remote-SSH — terminal and IDE operate on the remote box
  transparently, once connected.
- Latency is negligible for this workload — Kubernetes API calls, Kafka throughput, `kind`/`k3s`
  cluster operations all perform fine over typical broadband.
- Requires internet connectivity — no offline infra work. A real constraint while traveling or on
  spotty connections.
- SSH key security matters — a compromised key means someone else's workload runs on this bill.

## Still open

- Which connection model (Remote-SSH / remote Docker context / direct SSH) — decide during setup,
  not here.
- Confirm real-world latency and the recreate-from-scratch time feel acceptable once it's actually
  running.
