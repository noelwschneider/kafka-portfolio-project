# Issue #38: dev-box vs. local-stack policy for delegated agents

Investigation and policy proposal only, per the delegation prompt's instruction not to unilaterally
finalize. No preset or workflow-doc edits are included in this change — see `## Judgment calls` for
what needs developer confirmation before anything here gets written into `.claude/agents/*.md` or
`docs/workflow/agent-workflow.md`.

## What changed

No source, preset, or doc files were modified. This report is the only new file:
`docs/agent-reports/sprint-7/issue-38-dev-box-vs-local-policy.md`.

This is deliberate, not an oversight: the delegation prompt is explicit that the developer wants to be
looped in before any policy here is finalized into the presets, even though the technical investigation
itself is trusted. The concrete draft wording that *would* go into `.claude/agents/*.md` and
`docs/workflow/agent-workflow.md` is included below as a proposal, not applied.

## Investigation

### What the dev box actually is and requires

Read `infrastructure/dev-box/dev-up.sh`, `dev-down.sh`, and
`docs/agent-reports/sprint-2/hetzner-dev-box-setup.md` in full. Key operational facts:

- **Create-per-session, not persistent.** `dev-up.sh` provisions (or restores from the last snapshot)
  a Hetzner `cpx32` (4 vCPU / 8GB RAM), and `dev-down.sh` snapshots the disk then **deletes** the
  server — deletion, not power-off, is what stops billing. There is no auto-shutdown; a forgotten box
  bills continuously up to $41.99/month.
- **Manual, multi-step lifecycle**: `dev-up.sh` → `rsync` the working tree over (the box doesn't
  auto-sync) → `ssh kafka-dev-box` → run `docker compose` or `kind` commands identical to local → run
  `dev-down.sh` when done. Nothing in this chain happens automatically.
- **Single shared resource by design.** `dev-up.sh` refuses to create a second server under the same
  name, and the README explicitly warns against a second, differently-named box "just to test
  something." It is not built to host N agents' independent sessions concurrently — it's built to be
  one box, used, then torn down.
- **Requires a local credential**: `~/.config/hcloud-dev-box/token` (an `hcloud` API token, chmod 600,
  not in the repo) plus a dedicated SSH keypair at `~/.ssh/kafka-portfolio-dev-box`.
- **Stated purpose is narrower than "resource-heavy work" in general**: the README's own rationale is
  the M1 Docker Desktop VM's ~3.8GB cap breaking *Kubernetes/`kind`* workloads at higher replica counts
  — chaos/load testing, not routine `docker compose` verification, which is called out as unmodified
  and just as runnable locally.

### Quantifying the actual memory pressure (not assumed — measured)

The local stack was already running when this session started (started before my turn; I did not
start it and left it running, per "leave the environment as you found it" — see `## How this was
verified`). That let me measure real, not assumed, numbers instead of arguing from the OOM incident
reports alone.

```
$ docker info --format '{{.MemTotal}} bytes total, {{.NCPU}} CPUs'
4106604544 bytes total, 8 CPUs        # = 3.825 GiB, matching the dev-box doc's "~3.8GB" claim

$ docker stats --no-stream --format '{{.Name}}: {{.MemUsage}}'
orderfulfillment-scenario-service: 347.8MiB / 3.825GiB
orderfulfillment-frontend: 7.906MiB / 3.825GiB
orderfulfillment-payment-service: 326.3MiB / 3.825GiB
orderfulfillment-fulfillment-service: 340MiB / 3.825GiB
orderfulfillment-inventory-service: 333.7MiB / 3.825GiB
orderfulfillment-order-service: 361.1MiB / 3.825GiB
orderfulfillment-grafana: 71.27MiB / 3.825GiB
orderfulfillment-prometheus: 45.41MiB / 3.825GiB
orderfulfillment-postgres: 77.29MiB / 3.825GiB
orderfulfillment-kafka: 753.9MiB / 3.825GiB
```

Sum ≈ 2,664 MiB (2.60 GiB) — **~68% of the VM's total cap already consumed at idle, by a single
stack, before any build activity starts.** That leaves ~1.16 GiB of headroom. A `docker compose up
--build` doesn't just add a new container's steady-state footprint — it runs Maven/npm compilation
processes *while the old containers are still up* during the rolling replace, which is exactly the
kind of transient spike that tips a host already at 68% over the edge. This is consistent with, and
gives a concrete mechanism for, both recorded incidents:

- **Sprint 4**: 3 agents each ran a full-stack `docker compose up --build -d` concurrently → OOM-killed
  `kafka`, `inventory-service`, `scenario-service`. At the time, `kafka:` had no persistent volume
  (confirmed via `docs/agent-reports/sprint-4/issue-25-duplicate-event-and-event-explorer-diagnosis.md`),
  so the OOM-triggered recreation reset all topic offsets, which combined with
  `EventProjectionConsumer`'s offset-based dedup silently corrupted projected event history — real data
  loss, not just a wasted rebuild.
- **Sprint 6**: 5 parallel agents, 2 OOM incidents, both self-recovered with zero data loss. The
  difference from Sprint 4 is `orderfulfillment-kafka-data`, a named volume added between the two
  sprints specifically so container recreation doesn't wipe broker state (see the volume's comment
  block in `docker-compose.yml:27-40`) — plus the `implementer` preset's existing "rebuild only what
  your change touches" rule, which reduces (but, per Sprint 6, does not eliminate) how often full
  rebuilds collide.

One full-stack rebuild alone is already consuming most of the available headroom at idle; a second
concurrent full-stack rebuild is not a marginal risk, it's close to guaranteed to exceed the cap.

### The gap is worse than "the presets don't mention the dev box"

The delegation prompt frames this as the presets not *referencing* the dev box as an option. Direct
testing shows the actual exposure is broader: **nothing today mechanically prevents an unattended
subagent from finding and using the dev box on its own initiative.**

```
$ ls -la ~/.config/hcloud-dev-box/token
-rw-------@ 1 noel  staff  65 Aug 20 18:45 /Users/noel/.config/hcloud-dev-box/token

$ which hcloud
/opt/homebrew/bin/hcloud

$ HCLOUD_TOKEN=$(cat ~/.config/hcloud-dev-box/token) hcloud server list
ID          NAME                       STATUS    IPV4              IPV6                      PRIVATE NET   LOCATION   AGE
162971884   kafka-portfolio-demo-box   running   178.104.236.176   2a01:4f8:1c16:289d::/64   -             nbg1       5d
```

(Confirms the per-session dev box, `kafka-portfolio-dev-box`, is not currently provisioned — only the
always-on production demo box is up. No cost is accruing from the dev box right now, and I created or
deleted nothing with this check.)

The token is readable, `hcloud` is installed, and none of the four preset tool-allowlists restrict
`Bash` in a way that would stop `ssh kafka-dev-box`, `rsync`, or `./infrastructure/dev-box/dev-up.sh`
itself — `implementer`, `investigator`, and `platform` all grant unrestricted `Bash`; `verifier` does
too. So a subagent given an under-specified prompt ("the local stack keeps running out of memory,
try somewhere else") could today provision real, billable infrastructure without any human ever
deciding that should happen. This reframes the problem: it isn't only "tell agents the dev box
exists," it's "explicitly close off using it as an unattended, autonomous decision."

### Preset review

Read all four presets (`implementer.md`, `investigator.md`, `platform.md`, `verifier.md`) in full.
None mention `infrastructure/dev-box/` or Hetzner. `platform.md` is the only one that already carries
the shape of rule this needs — "Ask before anything risky or irreversible," "Cost is part of
correctness" — but it's scoped to the production demo box (it cites the two demo-box ADRs by name) and
says nothing about the dev box. `implementer.md` already carries the Sprint-4-derived "rebuild only
what your change touches" mitigation; that rule is unchanged by anything proposed here and remains the
correct default advice regardless of host.

## Proposal

### 1. When should a delegated agent use the dev box vs. local?

**Local `docker-compose` by default.** It's free, needs no credentials, and per
`docs/workflow/agent-workflow.md` ("Delegate") the overwhelming majority of delegated work across 31
agent reports has run this way without incident when scoped-rebuild discipline is followed.

**Route to the dev box only when either:**
- the task genuinely needs `kind`/Kubernetes at a scale (replica counts, workload size) the local
  ~3.8GB cap can't hold — this is the dev box's own stated purpose, unchanged by this proposal; or
- the orchestrator is about to run **more than one** full-stack-rebuild-shaped delegation
  (`docker compose up --build -d` with no service arguments) in the same time window, and can't just
  serialize them instead. Given one stack alone idles at ~68% of the cap, a second concurrent full
  rebuild is the scenario that has actually caused both recorded OOM incidents.

**This is always the orchestrator's decision, made when writing the delegation brief — never the
subagent's own judgment call mid-task.** Given the finding above that nothing stops a subagent from
reaching for `hcloud`/`ssh kafka-dev-box` on its own, and that doing so is a billable, credentialed,
real-world action, this needs to be an explicit boundary, not an implicit gap. Draft addition (one
line, same shape as `platform.md`'s existing "ask before anything risky" rule, generalized to all four
presets since Bash is unrestricted in all of them):

> Provisioning or connecting to the Hetzner dev box (`infrastructure/dev-box/dev-up.sh`, `hcloud`, or
> `ssh kafka-dev-box`) is off-limits on your own initiative. If local resource exhaustion blocks you and
> scoping the rebuild to the touched service doesn't fix it, stop and report rather than reaching for
> Hetzner yourself — using it is a decision the orchestrator makes when writing the delegation brief.

### 2. Does concurrent `docker-compose --build` need an explicit cap?

**Recommendation: document a cap of 1 concurrent full-stack rebuild per host; don't build mechanical
enforcement.** Reasoning:
- The existing resource-scoped-rebuild mitigation already measurably helped: Sprint 6's 5 parallel
  agents produced 2 OOM incidents, not 5, and both self-recovered with zero data loss because of the
  kafka volume fix landed after Sprint 4.
- A mechanical lock (e.g., a `flock`-based wrapper all builds go through, or a `PreToolUse` hook
  pattern-matching `docker compose.*--build`, mirroring `block-ai-attribution.py`'s shape) is real,
  buildable infrastructure — but it's new machinery to maintain (stale locks, false positives on
  legitimate concurrent scoped rebuilds) for a failure mode that, twice, has cost zero data and only
  wasted one rebuild's wall-clock time. That cost/benefit is exactly the tradeoff the task asked me to
  flag rather than decide — see `## Judgment calls`.
- If the developer prefers enforcement over documentation, the natural spot is a `PreToolUse` hook on
  `Bash` for the no-args `docker compose up --build` shape; I did not build this, since the
  investigation didn't come out in favor of it by default.

### 3. How would a background subagent know to reach for the dev box's manual lifecycle?

It wouldn't, and per the finding above it shouldn't try to decide this on its own — see point 1's
"always the orchestrator's decision." That reframes point 3 into two separate, smaller problems:

**(a) Keeping the subagent from freelancing it** — the one-line boundary rule proposed in point 1,
added to all four presets.

**(b) Giving the orchestrator a reliable place to put the mechanics when it *does* decide to use the
dev box** — proposed as a new skill, `.claude/skills/dev-box/SKILL.md`, modeled directly on the
existing `redeploy` skill (`.claude/skills/redeploy/SKILL.md`), which already solves the same shape of
problem for the production box: a live-state preamble, an explicit gate before doing anything, then
the exact commands.

Draft sketch (not created — this is what would be created if the developer confirms the skill-vs-
inline-text call in `## Judgment calls`):

```markdown
---
name: dev-box
description: Stand up the Hetzner dev box for resource-heavy verification (kind/K8s at scale, or a
  second full-stack rebuild that can't be serialized with one already running locally), and tear it
  down afterward. Orchestrator-invoked only — never delegated to a subagent to decide on its own.
disable-model-invocation: true
---

# Dev box for resource-heavy verification

## Live state at invocation
- Existing dev-box server: !`HCLOUD_TOKEN=$(cat ~/.config/hcloud-dev-box/token) hcloud server list 2>&1`

If `kafka-portfolio-dev-box` already appears, someone else's session may still be using it — don't
tear it down out from under them; check before running `dev-down.sh`.

## Before anything else
Confirm the task actually needs this (see docs/workflow/agent-workflow.md's dev-box policy) rather
than defaulting to it because local looked slow.

## Lifecycle
1. `infrastructure/dev-box/dev-up.sh`
2. `rsync -az --delete --exclude '.git' --exclude 'node_modules' --exclude '**/target' \
   --exclude '**/dist' -e "ssh -o StrictHostKeyChecking=accept-new" \
   ~/Documents/HelloWorld/kafka-portfolio-project/ kafka-dev-box:~/kafka-portfolio-project/`
3. `ssh kafka-dev-box` and run the same `docker compose` / `kind` commands used locally.
4. `infrastructure/dev-box/dev-down.sh` — run this even if the task fails partway. Do not leave this
   for a later turn to remember.
```

## Judgment calls

These are the parts of this proposal that need developer confirmation before landing in
`.claude/agents/*.md` or `docs/workflow/agent-workflow.md` — flagged per the delegation prompt's
explicit instruction, not decided unilaterally here.

1. **The dev-box-vs-local threshold itself** ("more than one concurrent full-stack rebuild, or a
   kind/K8s workload beyond local headroom"). I derived "more than one" from the measured 68%-idle
   figure, not from a stated preference — the developer might reasonably set it higher (e.g., "two is
   fine, three isn't") if they're comfortable relying on the kafka-volume self-recovery that's now
   worked twice with zero data loss, which would favor documentation over any threshold at all.
2. **Whether the concurrency cap should be mechanically enforced** (a lock script or `PreToolUse`
   hook) versus documentation-only. I'm recommending documentation-only given the track record (zero
   data loss across both incidents since the volume fix), but this is explicitly called out in the
   task prompt as a call to confirm, and I did not build either option pending that.
3. **Generalizing `platform.md`'s "ask before anything risky/irreversible" framing to all four
   presets**, specifically for dev-box access. This is a change in shape, not just content — today
   only `platform` carries this class of rule, on the theory that only `platform` normally touches
   billable infra. The verified finding that `implementer`/`investigator`/`verifier` all have
   unrestricted `Bash` and can technically already reach the dev box's credentials means the rule
   needs to live in all four if the goal is to actually close the gap, not just document intent for
   the preset that was already careful.
4. **A new `dev-box` skill vs. inline text in the presets or `agent-workflow.md`.** I lean toward a
   skill (parallel to `redeploy`) so the mechanics stay out of every subagent's always-loaded context
   and there's one canonical place to point a delegation brief at, per this project's own stated
   principle ("Prefer a skill to an agent when only the knowledge needs deferring" —
   `docs/workflow/agent-workflow.md`, "The four layers"). But a skill is `disable-model-invocation:
   true` and orchestrator-facing by design here, which is a slightly different usage pattern than the
   existing skills list — worth the developer's sign-off before adding a fifth skill file for a
   lifecycle that gets exercised rarely.
5. **Whether repurposing the dev box this way is even the right frame.** Its own README describes it
   as built for solo chaos/load-testing sessions, not as a shared overflow valve for parallel agent
   contention. Using it for the latter is a reasonable extension but is a scope expansion of what the
   box was designed for, and it's still a single exclusive resource — two delegations that both want
   it at once would just relocate the contention problem to Hetzner instead of removing it. I did not
   design a queuing/reservation mechanism for that case; flagging it as something the developer may
   want addressed if dev-box usage becomes routine rather than occasional.

## Deliberately not covered

- **No files were edited to implement this policy.** Per the task's explicit instruction, this is a
  proposal for confirmation, not a landed change. The draft preset-rule wording and the sketch skill
  above are ready to apply once the judgment calls above are resolved.
- **Did not provision the dev box** to rehearse the proposed lifecycle end-to-end. This was an
  explicit call: the task is a usage-policy question, out-of-scope explicitly excludes provisioning
  new infrastructure, and spinning up a billable session to test a *documentation* proposal seemed like
  the wrong tradeoff without the developer first agreeing the proposal's shape is right. `hcloud server
  list` (read-only, confirmed no server created or deleted) is the only Hetzner-API interaction this
  investigation performed.
- **Did not measure a real concurrent full-stack build's peak memory**, only the idle/steady-state
  footprint (~2.60 GiB of 3.825 GiB). The idle number is a lower bound on the actual risk — Maven/npm
  compilation running alongside the still-live old containers during a rolling `--build` almost
  certainly spikes higher, which is consistent with the OOM incidents but wasn't independently
  reproduced by me triggering a second concurrent rebuild here (doing so risked repeating the exact
  incident this task investigates, against a stack another process — not started by me — was actively
  using).
- **Did not touch worktree isolation or `acceptEdits`.** Both are explicitly out of scope per the
  delegation prompt; `docs/workflow/agent-workflow.md`'s "Open decisions" section already tracks them
  separately and nothing here changes either.
- **Did not investigate issue #42** (`/deploy` command design), despite the sprint plan noting it's
  related (both are "how agents and infra interact"). Explicitly a separate issue with its own scope.
- **Did not build the optional `PreToolUse` concurrency-cap hook** floated in point 2 — recommended
  against by default, available on request if the developer's judgment call comes out the other way.
- **Left the already-running local stack alone.** It was up (10 containers, 43-47 minutes uptime)
  before this session started; I did not start it, made no changes to it, and did not tear it down,
  per "leave the environment as you found it" — the `docker stats`/`docker ps` output above is the
  verification that it was pre-existing, not something spun up for this investigation.
