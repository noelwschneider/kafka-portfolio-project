# Git rules

## No AI attribution in commits or pull requests

Commit messages end with the last line of the message. Do not append:

- a `Co-Authored-By: Claude ...` trailer
- a "Generated with Claude Code" footer, or any equivalent tool attribution

The same applies to pull request bodies.

This is a standing preference about how the contribution graph and history read, not a claim that AI
involvement is hidden. A `PreToolUse` hook blocks commits and pull requests carrying these markers.

## Commit shape

One commit per logically coherent unit of work, not one per delegation. Two delegations that produced
the same conceptual change belong in one commit; unrelated goals never share one.

Write messages as a human would: terse, matching the style already in the history, describing the
change and why it was needed rather than restating the diff. Go longer only when the underlying change
is genuinely messy enough to need it.

## Committing is not automatic

Commit or push only when asked. If the current branch is the default branch, branch first.
