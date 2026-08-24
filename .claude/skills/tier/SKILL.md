---
name: tier
description: Decide the right model and effort - for a subagent about to be delegated, or for the current interactive session. Use before delegating work, or when asked whether the current session is over- or under-powered for what it's doing.
argument-hint: [task description, or "this session"]
---

# Choosing a preset and tier

Two different questions share this reference: which preset to delegate to, and what your own
interactive session should be running at. Answer the one that was actually asked.

## Your own session

The four sprint skills set their own model and effort automatically — `/sprint-plan`,
`/sprint-close`, and `/sprint-review` run Sonnet/high; `/sprint-open` runs Sonnet/medium. Nothing to
choose there.

For everything else — day-to-day conversation, reviewing diffs, discussing the workflow itself — you
set it, because the shape of the work varies conversation to conversation in a way no skill can see
in advance:

| The session is mostly | Suggests |
|---|---|
| Reviewing diffs, running commands, routine back-and-forth | Sonnet, medium |
| Writing delegation briefs, reading reports, deciding what to do next | Sonnet, high |
| Working through a genuinely open design question — several plausible shapes, real tradeoffs | Sonnet, high, or Opus if the decision is expensive to unwind |

`/effort` changes it for the rest of the session; the `ultrathink` keyword asks for deeper reasoning on
one turn without changing the setting. If a conversation shifts from routine to architectural
mid-stream — which is common — say so and bump it there rather than waiting for the whole session to
feel underpowered.

## Delegating to a subagent

## Which preset

Match the task's shape, not the code it touches.

| The task is | Preset |
|---|---|
| Build something whose design is already decided | `implementer` |
| Find out what is actually wrong or which option is right | `investigator` |
| Independently confirm a claim someone else made | `verifier` |
| Infrastructure, deployment, or a live system | `platform` |

## Which model

The presets default to Sonnet. Escalate a single task by passing `model: opus` at spawn time — do not
edit the preset, because the escalation is task-specific.

Escalate when **both** hold:

1. The right answer is genuinely undetermined — several plausible options, not one known shape, and
2. Being wrong is expensive to discover later — recurring cost, hard to reverse, silent failure, or a
   mistake that cascades into other work.

Concretely: a contract every downstream workstream builds against, concurrency correctness where a
subtle bug silently oversells inventory, or a platform decision carrying monthly billing. Not: a
diagnosed fix, a pattern applied across services, or config work with visible failure modes.

If a Sonnet task turns out harder than assumed mid-flight, escalate that task rather than the
workstream around it.

## Which effort

Effort comes from the preset frontmatter and does not need to be passed per task. It matters that it
lives there: **effort reverts to `high` whenever it is not set explicitly**, so a Medium-tier task
briefed without a preset quietly costs High.

Current defaults: `implementer` runs `medium`; `investigator`, `verifier`, and `platform` run `high`.
See `docs/external/claude-effort.md` for what each level changes.

Do not lower effort on a verification task. A verifier exists to catch what an implementer missed, and
the work is bounded and cheap regardless of tier, so there is no real saving in under-resourcing it.
