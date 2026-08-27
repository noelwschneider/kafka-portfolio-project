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
5. **Ask the developer, in chat, before running `gh pr merge`.** State what's about to merge and why;
   wait for an explicit go-ahead. CI passing is a precondition for merging, not permission to merge —
   don't treat a green check as implied consent to proceed on your own judgment.
6. Merge (`gh pr merge --squash` or `--merge`, matching the existing history's shape) and delete the
   branch.

This exists because pushing straight to `main` means CI runs asynchronously after the fact with nobody
watching — that is exactly how Sprint 7's own CI failure went unnoticed until the developer caught it
manually. Branch protection makes checking CI a gate the merge step cannot skip, not a step to remember.

## Before merging, confirm the PR's base is actually the intended target

`gh pr merge` merges a PR into *its own base branch* — not necessarily `main`, and it reports success
whether or not that base was what you meant. Before running `gh pr merge`, check `gh pr view <n>
--json baseRefName` and confirm it names the branch you actually intend the work to land on. This
matters most for a PR that was ever part of a stacked chain (one PR based on another's branch instead
of `main`) — if an earlier link in the chain gets closed, retargeted, or its branch deleted, a later
PR's base can silently stay pointed at a branch that no longer serves its original purpose, and
merging it there instead of `main` "succeeds" without anyone noticing.

**After merging, verify the merged content is actually reachable from the target** — don't trust
`gh pr merge`'s reported success, `gh pr view --json state` showing `MERGED`, or a board status update
as proof. A cheap direct check: `git cat-file -e origin/<target>:<a file the PR added>`. This is not
paranoia for its own sake — Sprint 8 closed a PR whose base had drifted to a since-deleted
intermediate branch this way, discovered only because the merge commit turned out to be unreachable
from `main` when checked directly; every other merge that session was re-verified the same way after
the fact specifically because this one wasn't caught until after.

## Merges and deployments are the developer-facing session's job, never a subagent's

A `PreToolUse` hook (`.claude/hooks/block-subagent-merge-deploy.py`) mechanically blocks any subagent
from running `gh pr merge`, `gh workflow run build-images.yml`, `redeploy.sh`, or a mutating `kubectl`
command — the same way `implementer`'s tool allowlist makes onward delegation mechanically unavailable
rather than merely discouraged. This landed after Sprint 8's issue #46: a `platform` subagent diagnosed
a live production bug, wrote a good fix, and pushed, opened a PR, and merged it to `main` in one
delegation, before the developer had seen the diff. The fix held up, but that was luck holding up a gap,
not the process working.

A subagent that finishes real work should commit, push a branch, and open a PR if one doesn't exist
yet, then stop and say so in its report — that is a complete, reviewable handoff. The merge itself, and
any deploy, happens only in the session the developer is directly talking to, and only after the
developer has explicitly confirmed — see the step above. This applies even when the fix is good and
even under incident pressure; "the diff turned out to be right" is not the same as the process having
worked, and the point of the rule is to not need to find that out after the fact.

**If `gh pr checks` reports no checks at all after a genuine push** (not just still-running — actually
absent), don't wait it out indefinitely. Confirm via `gh api repos/.../commits/<sha>/check-suites` that
no `GitHub Actions` suite was even created (this has happened — a real GitHub-side delivery miss, not a
misconfiguration), then `gh pr close <n>` immediately followed by `gh pr reopen <n>` to force a fresh
`pull_request` event. That has reliably re-triggered it.
