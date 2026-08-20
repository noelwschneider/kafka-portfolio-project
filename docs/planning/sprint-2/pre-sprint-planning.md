# Next Steps

MVP is reached (`docs/adr/ADR-009-out-of-order-status-transitions.md` and Phase 11 polish both
landed, `81bfe26`). This is the planning doc for the next work cycle: every idea that's been
brainstormed for after MVP, ordered by the sequence I'd actually recommend tackling them in, each
with an honest read on portfolio benefit versus time cost. A few ideas from the original brainstorm
turned out to bundle several very differently-sized things under one heading (`Open Items`
especially) — those are split out below so each gets its own cost/benefit call instead of being
assessed as a blob.

I've also added a small number of ideas that weren't in the original list (marked **(new)**) where
I think they're worth considering alongside what's already here.

---

## Tier 1 — Do next

The highest-leverage items. Security/hygiene comes first because it should happen before the repo
gets any more public exposure (pushes, deployment); deployment then gates almost everything else's
value; the rest of the tier is either cheap enough to knock out immediately or protects the
credibility of what's already built.

### 1. Security & Repo Hygiene Pass **(new)**

Moved to the top of the list: this should happen *before* pushing further work to a public GitHub
repo or standing up a public deployment, not as a pre-deployment afterthought. Two things bundled
because they're both quick, one-pass checks rather than ongoing work: (a) a security-specific look —
dependency vulnerability scan (`mvn dependency-check` / `npm audit` / GitHub's own Dependabot
alerts), a scan for anything resembling a committed secret, a sanity check on `/demo` endpoint
isolation; (b) baseline repo hygiene — a `LICENSE` file, confirming the README's badges/links all
resolve, no dead references to gitignored `docs/agent-reports/*.md` files (Phase 11 already fixed
the ones it found, but this is the kind of thing that creeps back).

- **Benefit:** Medium, but time-sensitive rather than optional — a public repo with an exposed
  secret or a known-vulnerable dependency is a much worse look than a missing feature, and the
  earlier this runs, the less exposure window there is before deployment makes the repo more
  visible.
- **Cost:** Low. Mostly running existing tools and reading their output, not new engineering.

### 2. Deployment Spike

Nothing else on this list pays off until this happens — a recruiter cannot evaluate a project that
only runs on your laptop. Also the one item that gates a few others directly: the "Optimize"
throughput-pushing idea and a truly representative "Bug Hunt" both benefit from testing against a
real deployed environment rather than `kind` on a laptop.

- **Benefit:** Very high, and multiplicative — every other portfolio-facing item on this list
  (demo video, cheat sheets, resume bullets) is worth more once there's a live URL to point at. This
  is the difference between "a repo" and "a project."
- **Cost:** Medium. Real decisions to make (host, TLS, secrets management, cost ceiling for an
  always-on demo vs. spin-up-on-demand), but it's a spike, not new feature work — most of the hard
  design decisions (event contracts, state machine, reliability patterns) are already made and
  documented. The `local-vs-cloud-dev-infra.md` research from this session is a head start, though
  that was scoped for *development*, not production hosting, so some of it won't transfer directly.

### 3. CI/CD Pipeline (GitHub Actions)

Pulled out of the original "Open Items" bullet list because it deserves its own weighing: this was
an explicit pinned-stack decision from day one (`docs/planning/project-overview.md` §0) that never
got built, and it's one of the first things a technical recruiter looks for — a green checkmark and
an Actions tab is a very cheap trust signal.

- **Benefit:** High relative to cost. Signals engineering maturity independent of what the app
  actually does, and the project's own path-filtered multi-module Maven structure was explicitly
  designed with per-service CI in mind, so this isn't fighting the architecture.
- **Cost:** Low–Medium. Mostly wiring, not design: build+test per service on the existing path
  filters, maybe a lint/format check. Doesn't need to include CD (auto-deploy) in the first pass —
  that can follow once the Deployment Spike picks a target.

### 4. README Demo Walkthrough **(new)**

Not in the original brainstorm. A short screen recording or a handful of annotated GIFs (triggering
a scenario, watching the SSE status stream update live, showing a Grafana panel move) embedded near
the top of the README. Most reviewers of a portfolio repo skim for 30–90 seconds; a repo that
*shows* the thing working in that window converts far better than one that only *describes* it,
however well-written the description is.

- **Benefit:** High for the time it takes. This is the single cheapest way to raise the odds someone
  actually understands what the project does before they decide whether to keep reading.
- **Cost:** Low. Best done once the Deployment Spike lands (recording against a real deployed
  instance is more convincing than localhost), but can be done locally first as a placeholder if
  deployment slips.

### 5. Bug Hunt

Kept near the top deliberately: this project has already found two real, non-obvious defects late
(the Phase 10 cross-topic race, the SSE-under-concurrency issue) purely by running things under load
rather than by inspection. The cost of finding a third one now — while the reliability-pattern
mental model is still loaded — is much lower than finding it during a live interview demo.

- **Benefit:** Medium-high. Doesn't add anything new to show off, but protects the credibility of
  everything else — a portfolio project's worst outcome is breaking on camera.
- **Cost:** Medium. Genuinely open-ended by nature ("every way a butterfly might flap its wings"),
  so it needs a time box rather than a completion criterion. A half-day to a day pass focused on
  concurrency and partial-failure paths (the two categories that have produced real bugs so far) is
  more productive than an unbounded search.

---

## Tier 2 — Do after Tier 1

A more varied set: some enable further work (VPS, autoscaler), some close out documented gaps
(correctness cleanup), one is squarely about improving how future cycles get done (workflow
refinement), and one — the Study Guide — is placed here on reconsideration. The portfolio itself
doesn't land interviews; the ability to speak fluently about it in one does. Framed that way, the
Study Guide isn't a nice-to-have polish item, it's the step that converts everything above it into
an actual outcome — arguably the second-most valuable thing on this whole list after the deployment
spike itself. It's sequenced within Tier 2 rather than Tier 1 purely because it doesn't need to
happen *before* anything else here; it can run in parallel with or after the more mechanical items
above.

### 6. Study Guide

- **Benefit:** High, and arguably underrated by cost/benefit framing alone — a portfolio that can't
  be spoken to fluently in an interview converts poorly regardless of how good the underlying work
  is. This is the step that turns "built a project" into "can defend every design decision in it,"
  which is the actual thing being evaluated once a conversation starts.
- **Cost:** High. Already flagged in the original brainstorm as "a massive document" requiring real
  structural planning up front — the biggest single time investment on this list. Worth deciding the
  structure (build-along vs. app-by-app vs. feature-by-feature) deliberately before starting, since
  getting the foundation wrong here is expensive to redo across "a massive document."

### 7. VPS / Remote Development Workflow

Explicitly the user's own top pick for addressing the laptop hardware ceiling long-term, and the
`local-vs-cloud-dev-infra.md` research already recommends a path (paid VPS, e.g. Hetzner, as
primary). Sequenced here rather than in Tier 1 because nothing in Tier 1 actually needs it — Tier 1
items are either infra-light (README, CI config, hygiene) or already fit on the T9-SSD-backed local
setup. It earns its place before Tier 2's more infra-hungry items, and before anything in the
shelved tier, since several of those are exactly the kind of "more local development" this would be
insurance against.

- **Benefit:** Medium-high, but indirect — it doesn't make the app better, it makes every subsequent
  session faster and removes a recurring multi-hour failure mode (this cycle alone burned a full
  session on Docker/HDD diagnosis before the SSD fix). The benefit compounds the more post-MVP work
  follows.
- **Cost:** Medium. Real setup work (provisioning, remote Docker context or SSH-based dev flow,
  probably a Tailscale/VPN layer for security), plus a recurring monthly cost (~€24/mo Hetzner CX33
  per the research doc — reverify current pricing before committing). Not large in absolute terms,
  but the first VPS setup of this kind for the user, so budget for a learning curve.

### 8. Autoscaler (HorizontalPodAutoscaler)

Split out of "Open Items." A natural, low-risk extension of work already done — Phase 10 built the
manual-scaling story and measured its ceiling; an HPA formalizes "scale to demand" instead of
`kubectl scale` by hand, and ties a bow on the scaling narrative for anyone reading
`docs/architecture-diagram.md`. Placed after the VPS item because meaningfully exercising an
autoscaler (watching it actually add/remove pods under real load) is much more convincing with more
headroom than this laptop's 8GB Docker Desktop VM cap allows.

- **Benefit:** Medium. Nice depth-of-Kubernetes-knowledge signal, and directly continues a story
  (Phase 10 scaling) the project already tells. Lower ceiling than deployment or CI/CD in isolation
  since it's an enhancement to an existing capability rather than a new one.
- **Cost:** Low-Medium. HPA config itself is small; most of the cost is in getting metrics-server (or
  reusing the existing Prometheus setup) wired to feed it, plus generating enough load to trigger a
  visible scale event.

### 9. Correctness & Reliability Cleanup (remaining Open Items)

The rest of the original "Open Items" bucket, bundled here because they're all small, independent,
already-diagnosed fixes rather than new design work — each has an existing doc pointer:

- Transactional outbox in Inventory/Payment/Fulfillment Services (currently Order Service only;
  gap documented in ADR-006 and the architecture docs).
- Order state machine's `FAILED` transition (transition 9), left unimplemented per ADR-009's
  "Accepted costs."
- SSE-under-concurrency defect in Order Service (`HttpMessageNotWritableException` under many
  concurrent stream connections, documented in `OrderStatusWatcher`'s Javadoc).
- Retention policy for `processed_events` and `deferred_transitions` (both flagged as growing
  forever, in `reliability-pattern.md` and ADR-009 respectively).

- **Benefit:** Low-Medium individually, but collectively they close every honestly-documented gap
  this project currently admits to — valuable if the plan is to eventually say "no known open
  issues" rather than "here are our known open issues." Someone reading the ADRs closely (the kind
  of reviewer this project is built to survive scrutiny from) will find these.
- **Cost:** Low-Medium each, since the design work is already done — ADR-009 in particular already
  explains exactly what the `FAILED` transition and retention gaps require. Bundled as one line item
  here, but treat as 4 separate small PRs, not one PR.

### 10. Agentic Workflow Refinement

Reframed from the original brainstorm: this is about *your own* Claude Code usage, not a portfolio
artifact. The goal is a more efficient personal workflow for future cycles — digging into existing
Claude Code documentation and features (subagent orchestration options, hooks, memory, slash
commands, model/effort tiering) to better understand what's actually available, rather than
continuing to work from whatever subset has come up organically so far. Placed just above the shelve
line: real compounding value across every future cycle, but nothing above it depends on it, and it's
reasonable to shelve for one cycle if time is tight — it doesn't decay.

- **Benefit:** Medium-high, entirely indirect and compounding — every future session gets cheaper
  and better-orchestrated the earlier this happens, in the same spirit as the VPS item but for
  workflow rather than hardware. Doesn't touch the app or the portfolio at all.
- **Cost:** Medium. Primarily reading/research time (documentation, not code), plus some trial time
  to actually validate that a technique works the way the docs describe before relying on it.

---

## Shelve line

Below this point, these are good ideas — genuinely worth doing eventually — but not worth starting
until everything above is done and a new planning cycle begins. Revisit this section fresh next
cycle rather than assuming the ordering below still holds — priorities may shift once Tier 1/2 are
actually done and deployed, and it's entirely plausible something more worthwhile gets identified in
the meantime (Mobile UI especially — see below).

### Mobile UI

Moved below the shelve line on reconsideration: by the time everything above is done, there's a
decent chance something more worthwhile will have surfaced, and there's no cost to waiting — nothing
above depends on this being done first.

- **Benefit:** Medium. This project's actual audience (recruiters, technical interviewers) is very
  likely reviewing on desktop, so this is polish rather than a gap that costs credibility if skipped
  — unlike, say, a broken deploy or a stale architecture page. Still worth doing eventually since a
  demo link shared casually (e.g. in a text message) may well get opened on a phone first.
- **Cost:** Medium. Depends entirely on the "responsive styles vs. dedicated mobile page" decision
  the brainstorm already raised — responsive is much cheaper and is almost certainly sufficient here
  given the app's actual complexity (a few dashboards and a scenario trigger UI, not a dense data
  app).

### Maintainability Audit

- **Benefit:** Medium, but back-loaded — its entire value is realized at some unknown future date
  when the user returns to the project after a long gap. No cost to deferring it now.
- **Cost:** Medium. A full audit pass (dependency freshness, README accuracy, whether the local dev
  setup docs still work on a clean machine) takes real time and is more useful done as a single pass
  right before a long gap than incrementally now.

### Optimize (push throughput)

- **Benefit:** Medium-high if it lands well (a concrete "handles N orders/sec" number is a great
  resume bullet), but it's meaningfully gated on the VPS/deployment work above — the current
  numbers are explicitly documented as hardware-ceiling-limited (Phase 10), not application-ceiling-
  limited, so pushing further on this laptop mostly just re-measures the same ceiling.
- **Cost:** Medium-high, and open-ended — "how far can it go" doesn't have a natural stopping point
  the way a bug fix does.

### Reusability Analysis

- **Benefit:** Low-Medium for portfolio purposes specifically — recruiters care much more about a
  working, well-documented system than an extracted library, unless the extraction itself is
  polished into its own shareable artifact (which is a much bigger undertaking than "look for
  patterns").
- **Cost:** Medium. Mostly time; low risk of breaking anything since it's additive/documentation-
  first.

### Cheat Sheets

- **Benefit:** Low-Medium. Genuinely useful as personal reference, mild portfolio value at best (a
  cheat sheet series reads more as "notes" than "project work" to most reviewers).
- **Cost:** Low per sheet, but the list of candidate technologies is long enough (20+) that the
  cumulative cost is high if treated as one project rather than an ongoing, unbounded personal wiki.

### Plan Improvements

- **Benefit:** Variable — this is really a recurring practice ("keep brainstorming and re-prioritizing
  feature ideas") rather than a single deliverable, so it doesn't have a fixed benefit to weigh once.
- **Cost:** Low per session, but by nature never "done." Best treated as the process this very
  document is an instance of, revisited each planning cycle, rather than a one-time item to schedule.
