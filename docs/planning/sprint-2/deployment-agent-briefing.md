# Deployment Spike — Agent Briefing

Standalone context for whoever picks up this task. Written for a fresh Claude Code session with no
access to the conversation that produced it.

**Suggested model/effort:** Opus, high effort. This decision carries the same profile the project has
reserved its highest tier for so far (see ADR-009): genuinely novel tradeoffs to weigh, costly and
hard-to-reverse consequences (recurring money, platform lock-in), and judgment quality here matters
more than execution speed.

## Project

`kafka-portfolio-project` — an event-driven order-fulfillment portfolio system (Java/Spring Boot,
Kafka, PostgreSQL, Kubernetes, React/TypeScript). Read `.claude/CLAUDE.md` first for repo norms.
`docs/architecture-diagram.md` and `docs/adr/ADR-007-kubernetes-only-after-local-boundaries-stabilize.md`
explain why Kubernetes was chosen at all — useful for judging whether a given hosting option still
tells that story honestly.

## Why this exists

Sprint 2 goal 4, "Deployment Spike": get the application running somewhere a link can be shared. No
recruiter is going to run this locally. This is a spike — a time-boxed investigation ending in a
working decision — not an open-ended infra project.

## Why this gets its own conversation, separate from the rest of Sprint 2

Most Sprint 2 work is mechanical once scoped (fix a known bug, wire up a scan). This one isn't:

- **Recurring cost.** Depending on platform, this ranges from close to $0 (a tightly-limited free
  tier) to a real monthly bill. Any option with an ongoing dollar cost needs the user's explicit
  sign-off on that specific number before anything is provisioned.
- **Platform lock-in / long-term project shape.** Committing to AWS-specific managed services
  (ECS/EKS/RDS/MSK, etc.) versus staying on portable, provider-agnostic Docker/Kubernetes changes how
  much of this project's "boring, portable stack" story holds up, and how much future work becomes
  platform-specific. That's a real design decision, not a deployment detail.
- **Marketability tradeoffs.** Always-on vs. spin-up-on-demand affects whether a recruiter clicking a
  link at an odd hour actually sees something running.

**Do not commit to a specific paid platform, provision anything that costs money, or create
third-party accounts without the user explicitly approving that specific choice first.** This
briefing exists to gather and present real options clearly, not to execute a predetermined plan.

## Constraints

- Single monorepo, no additional repos, no submodules.
- A deployment approach should be judged on whether it can still exercise the actual architecture
  (multiple services, Kafka, Postgres, Kubernetes or an honest equivalent) — not just "any process
  running somewhere." If a platform forces simplifying the demo down to something that no longer
  shows the real system, say so explicitly rather than quietly shipping a lesser version.
- Account creation and payment cannot be performed by an AI agent. The user does that step directly,
  once a specific platform and price are approved.

## Decision points to bring back to the user before committing money or infrastructure

1. **Platform category** — compare, don't just pick one:
   - A managed Kubernetes service (a small DigitalOcean/Linode/Hetzner K8s offering, or AWS EKS) —
     closest to what's already built; has a real monthly floor and the most operational complexity.
   - A single VPS running `kind`/`k3s` — cheapest, reuses in-house Kubernetes knowledge, but raises
     the box-sharing question below if it's the same machine as the dev VPS.
   - A PaaS platform (Render, Railway, Fly.io) — fastest to get something live, often has a workable
     free/cheap tier, but may not cleanly support the full multi-service Kafka topology — flag it
     explicitly if getting this running means simplifying the architecture.
   - AWS or another major cloud provider's individual services — most resume-recognizable, but the
     most complex and the easiest to accidentally rack up unexpected cost on.
2. **Always-on vs. spin-up-on-demand** — always-on is simpler to demo casually but costs money every
   month regardless of traffic; spin-up-on-demand is closer to $0 baseline but adds real complexity
   (something has to trigger the spin-up) and risks a cold visitor seeing nothing running.
3. **Budget ceiling** — what's an acceptable monthly number before this needs to come back for
   another conversation?
4. **Relationship to the Sprint 2 VPS task** — does production hosting reuse that Hetzner box, a
   sibling box on the same provider, or a fully separate platform? Reusing the same machine means a
   box meant to stay stable for public demo purposes also runs deliberately-induced chaos/failure
   scenarios during dev/test work — worth deciding explicitly, not defaulting into.

## Reference material

- `docs/architecture-diagram.md`, `docs/adr/ADR-007-kubernetes-only-after-local-boundaries-stabilize.md`
- `docs/planning/sprint-2/vps-agent-briefing.md` — the parallel dev-environment task. Read it so this
  doesn't duplicate or silently conflict with what's being decided there.
- `~/Documents/local-vs-cloud-dev-infra.md` — cost research for the *dev* environment specifically.
  Useful background on this user's cost sensitivity, not a substitute for separate production-hosting
  research.

## When done

Report back with the options actually compared, their real current pricing (verified directly, not
estimated), a recommendation, and the four decision points above answered or explicitly punted back
to the user — not silently resolved.

## Starter prompt

The message used to start this session (included for reference, not as an additional instruction —
everything it refers to is covered above):

> This project has an external planning doc at `docs/planning/sprint-2/deployment-agent-briefing.md`
> — read it first; it has the full context (why this exists, the constraints, and four decision
> points I need to weigh in on before anything gets provisioned). We're figuring out how to deploy a
> multi-service Kafka/Kubernetes portfolio project somewhere shareable. Don't provision anything or
> create any accounts yet — start by walking me through the realistic platform options and their
> actual current pricing, so we can decide together before you touch anything. Write your findings
> to a markdown file in `docs/agent-reports/sprint-2`.
