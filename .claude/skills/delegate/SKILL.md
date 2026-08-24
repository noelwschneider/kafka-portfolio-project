---
name: delegate
description: Compose a delegation brief for a subagent. Use before spawning any agent for non-trivial work, to make sure the prompt carries what the agent cannot get any other way.
argument-hint: [what needs doing]
---

# Writing a delegation brief

A subagent has no conversation history. Anything not in the prompt does not exist for it. But the
standing instructions already live in the preset — the brief carries only what is task-specific.

Pick the preset and tier first; apply the `tier` skill's procedure if it is not obvious.

## Include

- **Why this task matters**, in a sentence. Agents make better scope decisions when they know what the
  work is for.
- **Exact locations.** File paths with line numbers, not descriptions. `InventoryItemEntity.java:44`,
  not "the reset endpoint."
- **What has already been tried or ruled out**, and by whom. This is the single most common omission
  and it causes the agent to redo settled work.
- **Explicit scope boundaries** — especially the adjacent thing it should *not* do. Name the temptation
  directly: do not implement the option that was rejected, do not touch the live box, do not do a
  polish pass when only a factual fix was asked for.
- **Which exit criteria decide success**, if any are already written down.

## Leave out

These are in the preset. Restating them wastes context and invites the agent to treat them as
task-specific rather than standing:

- verify against a real running system
- run long commands in the foreground and wait for them
- do the work yourself rather than delegating onward
- file a report, and what the report must contain
- tear down infrastructure you started

## Before spawning

- Would someone with no context be able to start? If not, what is missing is usually the ruled-out list.
- Is the scope one coherent deliverable? Two unrelated goals in one brief produce a report that
  verifies neither well.
- Does anything in it need a human decision first? Ask now rather than letting the agent guess.
