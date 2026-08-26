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

When splitting one session's work into several logically-scoped commits, check `git diff --cached
--stat` (or `git status --short`) against what you actually intend before each commit, not just after
staging. A `git rm --cached` or broad `git add` from earlier in the same session stays staged across
later turns — it does not clear on its own — so it silently rides into the next `git commit` unless
you notice the leftover staged state, not just what you just added.

## Committing is not automatic

Commit or push only when asked.

## Branch, PR, wait for CI, merge — never push straight to `main`

`main` requires its `Required checks` status to pass before a PR can merge (see
`.github/workflows/ci.yml`'s `required-checks` job) — this is enforced by GitHub branch protection,
not just a convention. The workflow for any unit of work:

1. Branch off `main` (name it for what it does, e.g. `fix/ci-flaky-test-exclusion`).
2. Commit the work on that branch, following the Commit shape rules above.
3. Push the branch and open a PR (`gh pr create`).
4. Wait for CI to actually finish and pass — `gh pr checks <PR> --watch`, not a glance at the PR page.
   A red or still-running check is not something to merge past or ignore.
5. Merge (`gh pr merge --squash` or `--merge`, matching the existing history's shape) and delete the
   branch.

This exists because pushing straight to `main` means CI runs asynchronously after the fact with nobody
watching — that is exactly how Sprint 7's own CI failure went unnoticed until the developer caught it
manually. Branch protection makes checking CI a gate the merge step cannot skip, not a step to remember.
