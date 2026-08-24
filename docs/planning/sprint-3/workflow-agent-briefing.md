# Agentic Workflow Refinement — Agent Briefing

Standalone context for whoever picks up this task. Written for a fresh Claude Code session with no
access to the conversation that produced it.

**Suggested model/effort:** Opus, high effort. The value here is judgment — what's actually worth
adopting versus what's process for its own sake — not mechanical execution.

## Project

`kafka-portfolio-project` — an event-driven order-fulfillment portfolio system. This task is about
*how the developer uses Claude Code to work on it*, not about the application itself. You don't need
deep familiarity with the codebase for this task; you need deep familiarity with how this project's
agentic work has actually been orchestrated so far, and with what Claude Code / the Claude Agent SDK /
the Claude API actually offer.

## Why this exists

Tasks on this project have grown more complex — multi-agent parallel work, live production incidents
requiring real-time judgment, a growing GitHub Project board tracking work across sprints. The
developer's own workflow for directing this (mostly ad hoc so far, refined in the moment rather than
deliberately) hasn't kept pace. The goal is a more efficient, more deliberate personal workflow for
future work on this project — not a portfolio artifact, and not about the app.

## Required reading, in this order

1. `.claude/CLAUDE.md` — repo norms, and two specific "known gotcha" call-outs already written down
   from real experience (subagents ending their turn on unfinished background work; subagents
   delegating their own assigned work to a further subagent). These are real, not hypothetical.
2. `docs/planning/engineering-rules.md` — the Sprint workflow section, the 20 Agent Rules, the per-service
   agent ownership breakdown from the original MVP plan, and the GitHub Project board section (added
   this sprint — Status/Priority/Sprint fields, the Initiative/Task hierarchy using native GitHub
   sub-issues, draft-vs-real-issue policy).
3. **`docs/planning/sprint-3/orchestration-retrospective.md` — read this in full, it's the most
   important input to this task.** It's a from-memory synthesis of how this project's agentic work has
   actually been orchestrated across two sprints: the delegation mechanisms used and when each was
   chosen, the model/effort tiering pattern as actually practiced (never formally written down until
   that document), what makes a delegation prompt work, verification discipline, commit discipline, a
   real incident-response sequence from a production outage, and — most directly relevant to this
   task's goal — an honest list of what hasn't been tried at all (no reusable subagent presets, no
   hooks, no custom slash commands). This document exists specifically because that history lives only
   in a conversation you don't have access to; treat it as the closest thing to ground truth this task
   has for "how work actually happens here."

## Then: go learn what's actually available

Fetch and read *current* Claude Code, Claude Agent SDK, and Claude API documentation directly (search
for and browse docs.claude.com and related official sources) — do not rely on built-in training
knowledge for this, since these tools and their documentation evolve and being current is the actual
point of this exercise. Cover at minimum:

- Subagent orchestration options — the general-purpose `Agent`/Task-style delegation this project has
  used exclusively so far, versus custom agent-type presets (this session's own tool listing shows
  custom types can be defined via `.claude/agents/*.md` frontmatter — the orchestration retrospective
  flags this as never having been tried, despite a clearly recurring delegation shape in this
  project's history).
- Hooks (what events can be hooked, what they're good for).
- The memory system (if this project's orchestrating session has one — check for a memory directory
  and how it's structured) and how it does or doesn't carry across sessions.
- Slash commands / custom commands for recurring rituals.
- Model/effort selection and tiering options, and how they're actually meant to be chosen (versus the
  ad hoc rule of thumb the retrospective describes).
- Background task execution, session/usage-limit interruption and resumption patterns — the
  retrospective describes what worked in practice; check whether there's a more deliberate mechanism
  than "resume the same agent by name and tell it to re-orient."
- Anything else that surfaces as clearly relevant while reading — this list is a floor, not a ceiling.

## This is not a delegate-and-report task

There is no fixed completion state you determine on your own, and no final report to hand back
unprompted. The developer wants to be substantially more involved in this task than in prior
delegated work on this project — the actual shape is:

1. **You prepare thoroughly** (the required reading, then the capability research above) *before* the
   developer engages, so their time goes into judgment and decisions, not waiting on you to look
   things up mid-conversation.
2. **You brief them.** Once prepared, summarize clearly what's actually available — subagent presets,
   hooks, memory, slash commands, model/effort tiering, whatever else surfaced — in a way that invites
   questions rather than assuming they already know the landscape. Expect real back-and-forth here;
   they will ask clarifying questions about capabilities before moving on.
3. **Then hold an open-ended working conversation**, comparing what the orchestration retrospective
   says has actually happened against what you now know is possible, looking for concrete places to
   tighten instructions, adopt a tool, or change a habit. This is the actual work of the task, and it
   happens *with* the developer, not for them.

**There is no checklist that marks this done.** The developer described this task themselves as
"scrum-master kryptonite" — open-ended by nature, finished when they're satisfied with the resulting
workflow, not when a list of subtasks is checked off. Don't manufacture a false completion signal by
wrapping up early with a summary; let the conversation actually run its course.

Within that conversation, still aim to make concrete, low-risk changes directly rather than only
proposing them — a subagent preset, a slash command, an `engineering-rules.md` update — when something
becomes clearly worth doing. For anything that would meaningfully change how the *primary
orchestrating session* (the one the developer talks to day-to-day) behaves, that's exactly the kind of
thing to raise as a discussion point in the conversation itself, not decide unilaterally and report
afterward.

Don't adopt process for its own sake. This project's own engineering culture (`CLAUDE.md`: "smallest
coherent system," don't over-engineer, don't design for hypotheticals) applies here too — a technique
that's impressive but doesn't address a real friction point observed in the retrospective isn't worth
raising just because it exists. If something from "what hasn't been tried" turns out not to be worth
adopting once you understand it properly, that's a legitimate, useful thing to say plainly in the
conversation — not something to omit because it wasn't a win.

## Starter prompt

The message used to start this session (included for reference, not as an additional instruction —
everything it refers to is covered above):

> This project has an external planning doc at
> `docs/planning/sprint-3/workflow-agent-briefing.md` — read it first, it has everything you need
> (why, the required reading, what to research). This is about how Claude Code gets used on this
> project, not about the application code itself. Work through the required reading and the
> capability research on your own first, then brief me on what you found — I'll have questions. This
> is meant to be a real conversation, not a task you complete and hand back a report on.
