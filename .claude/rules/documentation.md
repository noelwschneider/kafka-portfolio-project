# Documentation rules

Applies to every document written for this project: planning docs, ADRs, agent reports, READMEs,
and workflow docs.

## State the current content, nothing about how it got there

A document describes what is true now. It does not narrate its own history.

- No "an earlier draft said X, this was revised to Y."
- No references to conversations, prompts, or decisions-in-chat that produced the document.
- No meta-commentary explaining why a section was added, removed, or reworded.

When a decision changes, rewrite the affected content to state the new decision. That kind of
narrative belongs in conversation or in a changelog file that exists for the purpose, not inside the
document whose job is to state the current plan, design, or fact.

Referencing project history as evidence is different and is fine: "this failed in Phase 7" is a fact
about the system, not commentary about the document.

## Keep rejected options brief and in context

Options that were considered and not taken rarely deserve their own section. They belong inside the
reasoning or tradeoff discussion for the choice that *was* made, in a sentence or two.

A standalone "alternatives considered" section pulls weight away from the work actually done. Reserve
that shape for ADRs, where comparing options is the document's entire purpose.

**This applies to closed decisions, not to open gaps.** Unfinished work, untested paths, and known
limitations are inventory, not narrative — they describe what still needs doing rather than
re-arguing something already settled. Record them in full, specifically enough to act on later.

The `## Deliberately not covered` section required in every agent report is exactly this kind of
inventory and is never trimmed for brevity. It is the single richest source of backlog items at
sprint review, and an honest gap named there is a successful outcome, not clutter.

## Write for the reader who needs to act

Tight focus on the document's purpose. Cut anything a reader would skip. Cross-reference other docs
by filename and section title rather than section number — numbers are not unique across the split
planning docs.
