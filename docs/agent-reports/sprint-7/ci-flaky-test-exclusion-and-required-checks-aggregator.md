# CI: exclude known-flaky scenario tests, add required-checks aggregator

## What changed

- `.github/workflows/ci.yml` — two changes to the `scenario-service` job and one new job:
  1. The `mvn test` command in the `scenario-service` job (previously line 140) now excludes
     `HighVolumeScenarioIntegrationTest` and `InventoryContentionScenarioIntegrationTest` via
     `-Dtest='!HighVolumeScenarioIntegrationTest,!InventoryContentionScenarioIntegrationTest'
     -Dsurefire.failIfNoSpecifiedTests=false`, matching the exclusion already used in local
     verification runs (confirmed identical to the string in
     `docs/agent-reports/sprint-7/issue-41-retry-classification.md` line 222). A comment above
     the step explains why and notes the underlying flake is tracked separately on the project
     board.
  2. A new `required-checks` job was added after `frontend`. It `needs` all six path-filtered
     jobs plus `changes`, runs with `if: always()`, prints each dependency's `result`, then fails
     (`exit 1`) if any of the seven is `'failure'`. `success` and `skipped` both fall through
     without tripping the failure step, so the job succeeds. A comment explains why this is the
     one job branch protection should require, rather than any of the six directly.

No other files were changed. `.github/workflows/build-images.yml` was not touched, per the task's
explicit exclusion. Nothing was committed — per the task, the user will branch, commit, and open
the PR themselves.

## How this was verified

YAML well-formedness:

```
$ python3 -c "import yaml; d = yaml.safe_load(open('.github/workflows/ci.yml')); print('YAML OK'); print('jobs:', list(d['jobs'].keys()))"
YAML OK
jobs: ['changes', 'order-service', 'inventory-service', 'payment-service', 'fulfillment-service', 'scenario-service', 'frontend', 'required-checks']
```

`actionlint` (available locally at `/opt/homebrew/bin/actionlint`, v1.7.12), which additionally
checks GitHub Actions expression syntax, `needs`/`if` references, and job dependency graphs — not
just YAML syntax:

```
$ actionlint .github/workflows/ci.yml; echo "exit code: $?"
exit code: 0
```

No findings from actionlint — in particular, it did not flag the bracket-notation `needs['order-service'].result`
expressions (required because job IDs contain hyphens, which are not valid bare identifier
characters in GitHub Actions expression syntax) or the `if: |` multiline boolean conditions as
invalid.

Confirmed the flaky-test exclusion string is byte-for-byte identical to the one already used in
local verification, so this isn't introducing a new/different exclusion:

```
$ grep -n "Dtest=" docs/agent-reports/sprint-7/issue-41-retry-classification.md
222:    -Dtest='!HighVolumeScenarioIntegrationTest,!InventoryContentionScenarioIntegrationTest' \
```

Manual walkthrough of the `required-checks` job logic for the three scenarios named in the task
(I could not trigger an actual GitHub Actions run to exercise this end-to-end — see "Deliberately
not covered"):

1. **All six path-filtered jobs run and pass** (e.g., a PR touching `pom.xml`, so `common` fans
   out to everything). Every `needs.<job>.result` is `success`. The `if` condition on "Fail if any
   required job failed" evaluates to `false || false || ... || false` = `false`, so that step is
   skipped (counts as a passing step, not a failure). No step in `required-checks` fails, so the
   job's overall conclusion is `success`. Correct.

2. **One job legitimately skipped by path filters** (e.g., a PR touching only
   `frontend/src/App.tsx` — `order-service`, `inventory-service`, `payment-service`,
   `fulfillment-service`, `scenario-service` never satisfy their `if` condition and GitHub reports
   their `needs.<job>.result` as `skipped`; `changes` and `frontend` run and pass). The failure
   condition only matches the literal string `'failure'`; `skipped` doesn't equal `'failure'`, so
   every clause is still `false`, the failure step is skipped, and the job succeeds. This is
   exactly the gap the task describes (a permanently-skipped named job would block branch
   protection forever) — `required-checks` sidesteps it because it itself always runs
   (`if: always()`) and treats its dependencies' `skipped` as fine, rather than being a
   pass-through of one specific service's skip state.

3. **One genuine test failure** (e.g., `order-service`'s `mvn test` step fails). GitHub sets
   `needs.order-service.result` to `failure`. `required-checks` still runs because of
   `if: always()` (a `needs` failure would otherwise skip a normal job). The clause
   `needs['order-service'].result == 'failure'` evaluates `true`, so the whole OR condition is
   `true`, the "Fail if any required job failed" step runs `exit 1`, and the job's conclusion is
   `failure`. Correct.

I also checked the `changes` job specifically: it carries no `if` condition, so it always runs and
its result is always `success` or `failure`, never `skipped` — meaning including it in the
`needs.changes.result == 'failure'` check is meaningful (it would trip if, e.g., the
`dorny/paths-filter` step itself errored) rather than dead code.

## Judgment calls

- **Explicit per-job conditions instead of `needs.*.result` wildcard or a third-party aggregator
  action.** GitHub Actions expressions do support an object-filter wildcard (`needs.*.result`)
  that would shorten the condition, and there's a well-known off-the-shelf action
  (`re-actors/alls-green`) built for exactly this pattern. I chose to spell out each of the seven
  `needs.<job>.result == 'failure'` clauses explicitly instead, because: (a) it's easier for a
  reviewer to statically verify against the exact 7-job list the task specified without trusting
  wildcard-on-object semantics I couldn't fully confirm were correct pre-actionlint, and (b) it
  avoids adding a new third-party action dependency (and its supply-chain/pinning considerations)
  for a ~10-line check the task described as something to build directly, not to source. Bracket
  notation (`needs['order-service']`) was necessary, not optional, wherever a job ID contains a
  hyphen — dot notation on a hyphenated property is invalid GitHub Actions expression syntax.
- **`cancelled` is not treated as a failure.** The task specified only two outcomes to distinguish
  ("success and skipped are fine, failure is not") and didn't mention `cancelled`. I left it
  falling through as non-failing, matching the literal spec, rather than guessing the user wanted
  cancellations treated as blocking too. In practice a `cancelled` result on one of these jobs
  would almost always mean the whole workflow run was superseded by the `concurrency:
  cancel-in-progress` group at the top of the file, in which case `required-checks` itself would
  also be cancelled rather than reporting a false pass — so this shouldn't be exploitable as a way
  to force a merge past a real failure.
- **Job name `required-checks`** — the task suggested this name or similar; used verbatim since
  nothing else was more descriptive.

## Deliberately not covered

- **No real GitHub Actions run exercised any of this.** Everything above is static verification
  (YAML parse, actionlint's expression/dependency-graph checks, and manual logic tracing). The
  three scenarios were reasoned through against GitHub's documented `if`/`needs`/`always()`
  semantics, not observed. The task explicitly acknowledged this would be the case ("you cannot
  literally trigger a GitHub Actions run to prove this end-to-end"). This should be confirmed once
  a real PR runs against the new workflow — in particular, watch the first PR that touches only
  one service's path to confirm the other five service jobs really do show as `skipped` (not some
  other state like `neutral`) in the `needs` context, since that's the crux of the fix.
  - After that, the aggregator's own status on GitHub should be checked before branch protection
    is switched on to require it, in case anything about how GitHub renders a job with only
    conditional steps (as opposed to a job whose own `if` gates the whole job) looks different in
    the UI than expected.
- **Did not investigate or fix the root cause of either flaky test's timing sensitivity** — out of
  scope per the task, and separately tracked.
- **Did not configure GitHub branch protection** — out of scope per the task; the user handles
  that themselves once `required-checks` exists on `main`.
- **Did not commit, push, or open a PR** — out of scope per the task; the file is left modified in
  the working tree for the user to branch/commit/PR themselves.
- **Did not audit whether `dorny/paths-filter`'s `skipped`-vs-other-result semantics could ever
  produce something other than `success`/`failure`/`skipped`/`cancelled`** (e.g., `neutral` is a
  GitHub Checks API concept, not a workflow job `result` value, so I don't believe it's reachable
  here, but I haven't seen it occur in practice in this repo's Actions history to confirm).
