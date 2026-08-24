# Dev Box Cost Management Plan

Cost breakdown for the Sprint 2 remote dev environment (`docs/planning/sprint-2/vps-agent-briefing.md`),
verified against Hetzner's live pricing API rather than any doc's stated figures. Covers what this
task itself costs to set up, and what it costs to keep using afterward.

**Account currency note:** this Hetzner account bills in **USD, not EUR**. Every figure below is USD.
Other docs in this repo (the briefing, `~/Documents/local-vs-cloud-dev-infra.md`) quote euro amounts —
treat the numeral as correct and the currency symbol as wrong; Hetzner does not convert prices between
currencies, it substitutes the symbol for the account's billing region.

## 1. What's actually being provisioned

- **Plan: CPX32** — 4 vCPU / 8 GB RAM / 160 GB disk, shared x86.
- **Location: `nbg1`** (Nuremberg). Chosen over `hel1` (same price) on general connectivity; latency
  from Minnesota is not meaningfully different between the two.
- **Model: created per session, deleted after.** Not a standing server — `dev-up` provisions (or
  restores from the last snapshot), `dev-down` snapshots the disk and deletes the server. This is the
  entire basis for the cost model below: almost all the savings come from the box not existing most of
  the time, not from picking a cheap plan.

Live-verified pricing (Hetzner API, checked 2026-08-20):

| Item | Rate |
| --- | --- |
| CPX32 hourly | $0.0673/hour |
| CPX32 monthly cap (if run 24/7 for a full month) | $41.99 |
| Fallback: CPX42 (8 vCPU/16 GB) hourly | $0.1314/hour |
| Fallback: CPX42 monthly cap | $81.99 |
| Snapshot storage | $0.0199/GB-month (compressed usage, not disk size) |
| Primary IPv4 | $0.60/month, prorated hourly — only billed while the server exists |
| Floating IP (not used — see §4) | $3.50/month if it existed |

**Correction to existing docs:** `vps-agent-briefing.md` and `local-vs-cloud-dev-infra.md` both state a
€35.49 monthly cap for CPX32. The live figure is $41.99 — about 18% higher. Doesn't change the model
(this box is not meant to run a full month), but the per-session unit cost below uses the correct
number.

## 2. What this task itself costs (setup and verification)

Provisioning, SSH/firewall setup, installing Docker + k3s, and verifying the project's Kubernetes
workload runs — realistically a few hours of the box existing while work happens directly on it.

| Scenario | Hours | Cost |
| --- | --- | --- |
| Efficient setup session | 2 hours | $0.13 |
| Setup + troubleshooting | 6 hours | $0.40 |
| Setup spread across a full working day | 10 hours | $0.67 |

Effectively negligible — under a dollar regardless of how long setup takes, because CPX32's hourly
rate is small enough that even a bad day of debugging costs pocket change. The only way this phase gets
expensive is leaving the box running unattended afterward (see §5).

## 3. Ongoing per-month cost, by usage pattern

Assumes the box is deleted at the end of each session (`dev-down`) and one snapshot is kept between
sessions.

| Usage pattern | Compute cost | Snapshot cost (~15 GB used, typical) | Total/month |
| --- | --- | --- | --- |
| Light — 2 sessions/week, 3 hrs each (~24 hrs/mo) | $1.62 | ~$0.30 | **~$1.92/month** |
| Moderate — 4 sessions/week, 4 hrs each (~64 hrs/mo) | $4.31 | ~$0.30 | **~$4.61/month** |
| Heavy — near-daily use, 6 hrs/day (~180 hrs/mo) | $12.11 | ~$0.30 | **~$12.41/month** |
| Worst case — box accidentally left running all month | $41.99 (hits the cap) | ~$0.30 | **~$42.29/month** |

The realistic range for how you described this box's purpose — occasional bug-hunt and autoscaler
sessions, not daily-driver development — is the **light-to-moderate band, roughly $2–5/month.** The
worst case is bounded by the monthly cap itself ($41.99), which is Hetzner's built-in ceiling — there's
no scenario, short of running two boxes at once, where this exceeds ~$42/month.

## 4. Why the connection model matters to cost, not just convenience

The briefing calls for a host-alias update on each recreation rather than a floating IP, specifically
because a floating IP bills **$3.50/month for as long as it exists — including while the server it
points to is deleted.** That would silently turn a $2/month box into a $2 + $3.50 = $5.50/month box,
undermining the exact thing the per-session model is for. No floating IP will be created as part of
this task for that reason.

## 5. What makes this hard to pin down precisely

- **Your actual usage cadence is the biggest unknown.** Everything above is a projection from assumed
  session counts, not a measurement. The first month of real use is the only way to know which row of
  the table you're actually in.
- **The realistic failure mode is forgetting the box exists, not a usage spike.** There is no
  metered/variable component here that can spike on its own — Hetzner charges per resource that
  *exists*, capped monthly per server. The only way this plan costs meaningfully more than projected is
  a `dev-down` that didn't run (box left on) or a snapshot never cleaned up. Both are visible in a
  30-second `hcloud server list` / `hcloud image list --type snapshot` check.
- **CPX32 stock is not guaranteed to stay available.** Hetzner's CX line (the cheaper option this task
  originally targeted) is completely sold out across every datacenter as of this check — not just
  constrained, zero available anywhere. If CPX32 also goes out of stock mid-sprint, the documented
  fallback is CPX42 at roughly double the hourly rate ($0.1314 vs $0.0673) — still cheap in the
  light/moderate usage bands (~$4–9/month) but worth knowing the number could move.
- **Snapshot size grows with what's left on the disk when `dev-down` runs** (pulled container images,
  build caches). The $0.30/month figure above assumes a modest ~15 GB snapshot; a snapshot taken right
  after pulling every image fresh could be larger. This is a small dollar amount either way (even 80 GB
  compressed is under $1.60/month) but is the one number in this report that depends on session habits
  rather than the pricing itself.
- **Hetzner's per-line pricing has moved sharply before** (the CPX line was repriced 2.2–2.7× in June
  2026, which is *why* the CX line was originally preferred and *why* this task ended up on CPX32 at
  all). A repeat of that kind of change is a real, if unpredictable, risk to the numbers in this
  report — not something to build a script around, but worth another live-price check if this plan is
  revisited months from now.

## 6. Separate from this task: the demo box

Sprint 2 also has a second, unrelated Hetzner server (`dev-vs-demo-host-separation.md`) — an always-on
public demo box, not covered by this report or its per-session model. Worth flagging here since it
affects your total combined Hetzner bill even though it's a different task's deliverable: that box's
plan was last settled as **CX23, which this session's live check also found completely out of stock**
(same CX-line shortage described in §5). Whoever is working that task will need to re-verify pricing
and availability the same way this report did — the number they're currently planning around may no
longer be purchasable at all, let alone at the price documented.

## 7. Staying on top of this going forward

1. Trust `dev-down` to delete, not stop, the server — that's the only action that actually stops
   billing.
2. A monthly two-minute check (`hcloud server list`, `hcloud image list --type snapshot`,
   `hcloud volume list`) catches anything left running or orphaned. Nothing on this account should ever
   show a server that isn't actively in use.
3. Re-verify live pricing before any long gap in usage (e.g., returning to this after a month away) —
   the numbers in this report are a snapshot of 2026-08-20 pricing and stock, not a permanent contract.
