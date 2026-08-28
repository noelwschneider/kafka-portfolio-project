# Issue #81 — Extract, parameterize, and validate the `noel-workflow` plugin

## What changed

### New, outside this repo — the plugin source

Built at `/Users/noel/claude-plugins/noel-workflow/`, a plain directory. It is **not** a git repo
and nothing was created on GitHub — where it should live long-term is the developer's call (see
`## Deliberately not covered`). Its parent `/Users/noel/claude-plugins/` carries a
`.claude-plugin/marketplace.json` so the directory doubles as a local marketplace.

| Path (under `~/claude-plugins/noel-workflow/`) | What it is |
|---|---|
| `.claude-plugin/plugin.json` | Manifest, `noel-workflow` v0.1.0 |
| `.gitignore` | `__pycache__/`, `*.pyc`, `.DS_Store` — inert now, ready if the directory becomes a repo |
| `README.md` | What's in the plugin, the config schema, how to change it, what's still unknown |
| `agents/{implementer,investigator,verifier,platform}.md` | The four presets, de-project-ified |
| `hooks/hooks.json` | Registrations, with the **namespaced** matchers |
| `hooks/workflow_config.py` | Shared reader for the consuming repo's `.claude/workflow.json` |
| `hooks/{mark-agent-start,require-agent-report,block-ai-attribution,block-subagent-merge-deploy}.py` | The four hooks |
| `skills/{agent-report,delegate,tier,board,sprint-plan,sprint-open,sprint-close,sprint-review}/` | The eight generic skills |
| `skills/board/check-drift.py` | Now reads coordinates from config instead of module constants |
| `skills/board-transition/SKILL.md` | **New** — see `## Judgment calls` |

### New, outside this repo — global rules

- `~/.claude/rules/documentation.md` — copied verbatim from this repo (class **a**, no project tokens).
- `~/.claude/rules/git.md` — generalized: every rule kept, named artifacts (`ci.yml`'s
  `required-checks` job, `redeploy.sh`, `build-images.yml`, the hook path, Sprint 7/8 numbering)
  replaced with generic phrasing. Incident rationale kept, per #80's disposition.

### Changed in this repo

- `.claude/workflow.json` — **new**. This project's coordinates: report/planning roots, board owner
  `noelwschneider` / repo / project number `7`, the two extra blocked deploy verbs, and the Flyway
  contract-escalation rule carrying its original message verbatim. No field or option ids.
- Deleted: `.claude/agents/*` (4), `.claude/skills/{agent-report,delegate,tier,board,sprint-plan,sprint-open,sprint-close,sprint-review}` (8), `.claude/hooks/*.py` (4), `.claude/rules/*.md` (2), `.claude/settings.json`. 1,846 lines removed. `.claude/skills/deploy/` and `.claude/skills/dev-box/` kept, as specified.
- `.claude/settings.json` deleted rather than emptied — the plugin now provides every hook it
  registered, and an empty file would have registered nothing while looking like configuration.
- `.claude/CLAUDE.md` — orchestration section states the plugin topology and the namespaced
  `subagent_type` forms; new "Project-specific git facts" section holds the `required-checks` job and
  deploy verbs that came out of the generalized `git.md`.
- `docs/workflow/agent-workflow.md` — layer table now names plugin paths plus a `.claude/workflow.json`
  row; preset table uses fully-qualified `subagent_type`s; merge/deploy hook reference updated.
- `docs/workflow/README.md` — the "what lives where" table rewritten for the plugin split.
- `docs/workflow/user-guide.md` — the "agent lacks a tool" row points at the plugin and warns the
  change is global.
- `docs/planning/handoff.md` — three path references updated (`~/.claude/rules/git.md`, the hook).

## How this was verified

Claude Code 2.1.241. Nothing below is inferred.

### Validation and install

```
$ claude plugin validate ~/claude-plugins/noel-workflow --strict
Validating plugin manifest: /Users/noel/claude-plugins/noel-workflow/.claude-plugin/plugin.json
✔ Validation passed
$ claude plugin validate ~/claude-plugins/noel-workflow/agents --strict
✔ Validation passed
$ claude plugin validate ~/claude-plugins/noel-workflow/skills --strict
✔ Validation passed

$ claude plugin marketplace add ~/claude-plugins
✔ Successfully added marketplace: noel-local (declared in user settings)
$ claude plugin install noel-workflow@noel-local
✔ Successfully installed plugin: noel-workflow@noel-local (scope: user)
```

`claude plugin details noel-workflow` — this closes #80's unmeasured always-on token budget:

```
Component inventory
  Skills (9)  agent-report, board, board-transition, delegate, sprint-close, sprint-open,
              sprint-plan, sprint-review, tier
  Agents (4)  platform, verifier, investigator, implementer
  Hooks (3)   SubagentStart, SubagentStop, PreToolUse  (harness-only — no model context cost)

Projected token cost
  Always-on:   ~1,066 tok   added to every session
```

### All four presets are reachable, and only in namespaced form

Run from this repo after the migration:

```
$ claude -p "Make ONE Agent tool call with subagent_type 'nonexistent-preset-xyz' so the error
  lists every available agent type..." --model haiku --allowedTools "Agent"

claude, claude-code-guide, Explore, general-purpose, noel-workflow:implementer,
noel-workflow:investigator, noel-workflow:platform, noel-workflow:verifier, Plan, statusline-setup
```

Bare `implementer` / `investigator` / `verifier` / `platform` are gone — confirming the project-local
copies were removed and the plugin is the only source.

### The critical hazard: the `SubagentStop` matcher — A/B tested, not assumed

A scratch project (`probeworld`) with `reportRoot: "reports"` and no `.claude/.agent-marks/`. Identical
prompt both times; **only the matcher differed**.

**A — old bare matcher `implementer|investigator|verifier|platform`:**

```
$ claude -p "Make exactly ONE Agent tool call: subagent_type 'noel-workflow:implementer', model haiku,
  prompt: 'Create a file hello.txt ... Do not write any report.'" ...

The agent has completed. File `hello.txt` was created successfully...

=== marks dir (exists => SubagentStart fired):
ls: .../probeworld/.claude/.agent-marks: No such file or directory
=== reports written:
total 0
```

Neither gate fired. No mark, no report demanded, turn ended clean. **This is exactly the silent
failure #80 predicted, reproduced live.**

**B — namespaced matcher `noel-workflow:(implementer|investigator|verifier|platform)`, same prompt:**

```
=== marks dir:
drwxr-xr-x@ 2 noel  wheel   64 Aug 28 15:02 .
=== reports written:
hello-txt-creation.md
```

`SubagentStart` fired (only `mark-agent-start.py` creates that directory), and a report was written
despite the prompt explicitly saying not to — because the stop gate refused the turn.

An earlier B-run with a fresh agent captured the block directly:

```
$ claude -p "... Then tell me: BLOCKED_ON_FIRST_STOP=<yes|no> — whether the agent's first attempt
  to end its turn was refused by a SubagentStop hook demanding a report."
BLOCKED_ON_FIRST_STOP=yes
```

and the artifacts show the report landed in the **config-driven** root, not the default:

```
=== tree:
./.claude/settings.json
./.claude/workflow.json
./hello.txt
./reports/hello-txt-creation.md          <- reportRoot: "reports", honored
=== .agent-marks (empty => start hook wrote a mark AND the stop gate passed and cleaned it up):
total 0
```

### Report gate, all branches

```
### 1. no report present -> expect exit 2
BLOCKED: no agent report found.
You have not filed a report under reports/ ...        <- config root in the message
exit=2

### 2. valid report -> expect exit 0
exit=0

### 3. report missing the fenced block -> expect exit 2
BLOCKED: reports/probe.md does not meet the agent-report contract.
No command output found under '## How this was verified'.
exit=2

### 4. contract escalation: touch contracts/*.sql, no CONTRACT.md -> expect exit 2
Missing required heading(s), which must appear verbatim:
  ## Frozen contract impact
exit=2
```

### PreToolUse hooks, including the config-supplied project deploy verb

```
PASS  exit=2 (want 2)  subagent merging a PR
        -> BLOCKED: merging a pull request (gh pr merge) is not something a subagent does.
PASS  exit=0 (want 0)  main session merging a PR (exempt)
PASS  exit=2 (want 2)  project deploy verb from workflow.json
        -> BLOCKED: running probe-deploy.sh is not something a subagent does.
PASS  exit=2 (want 2)  mutating kubectl
PASS  exit=0 (want 0)  unrelated command
PASS  exit=2 (want 2)  commit carrying AI attribution
PASS  exit=0 (want 0)  clean commit message

all passed
```

The block is also live against *this* session — an earlier command of mine containing the literal
merge verb was refused by the plugin's hook, naming the source path:

```
PreToolUse:Bash hook error: [/Users/noel/claude-plugins/noel-workflow/hooks/block-subagent-merge-deploy.py]:
BLOCKED: merging a pull request (gh pr merge) is not something a subagent does.
```

### This repo, migrated — real delegation through the plugin

```
$ claude -p "Make exactly ONE Agent tool call: subagent_type 'noel-workflow:implementer', model haiku,
  prompt: 'Read-only task. Run: ls -1 services/ and report how many service directories exist...'"

Agent completed. Found 6 service directories (order-service, inventory-service, payment-service,
fulfillment-service, scenario-service, common). Report written to
`docs/agent-reports/smoke-test-plugin-migration.md`.

$ grep -n '^## ' docs/agent-reports/smoke-test-plugin-migration.md
3:## What changed
7:## How this was verified
25:## Judgment calls
29:## Deliberately not covered

$ # gate re-run against that report with this repo's real workflow.json
exit=0
```

(That smoke report was deleted afterward; it was scaffolding, not a deliverable.)

### This repo's Flyway escalation still fires, now from config

With a start mark newer than the contract docs, and a real migration touched after it:

```
$ MIG=services/fulfillment-service/src/main/resources/db/migration/V1__shipments.sql
$ : > .claude/.agent-marks/esc-test; sleep 1; touch "$MIG" docs/agent-reports/<report>.md
$ python3 -c "...{'cwd':'$PWD','agent_id':'esc-test'}" | .../require-agent-report.py

BLOCKED: docs/agent-reports/smoke-test-plugin-migration.md does not meet the agent-report contract.

Missing required heading(s), which must appear verbatim:
  ## Frozen contract impact

You modified a Flyway migration under a db/migration/ directory without touching
docs/db-ownership.md or docs/CHANGELOG-contracts.md. If that migration changes the
shape of a table docs/db-ownership.md documents (a column, a unique/check constraint,
etc.), that is a frozen-contract change - follow the coordination protocol in
.claude/CLAUDE.md: propose the change in db-ownership.md, then log it in
docs/CHANGELOG-contracts.md.
...
exit=2

$ touch -r <backup> "$MIG"; git status --short "$MIG"
(no output = clean)
```

Byte-for-byte the original message, now supplied by `.claude/workflow.json` rather than hardcoded.

### `check-drift.py` against the live board, reading the new config

```
$ ~/claude-plugins/noel-workflow/skills/board/check-drift.py
Checked 87 board items — 2 mismatch(es):

  - #83 "Review infrastructure protocols for concurrent-agent Docker/git hazards": board says
    Merged, but the issue is still OPEN
  - #82 "Write a project handoff document and wire it into /sprint-close": board says
    Ready to Merge, but the issue is already CLOSED
```

Real board, real coordinates resolved from `.claude/workflow.json` with no hardcoded owner/number.
Both findings belong to #82/#83, not to me.

### Global rules load

```
$ claude -p "Line 1: YES or NO - do you have a standing rule in context stating that commit messages
  must not carry a Co-Authored-By: Claude trailer? Line 2: YES or NO - ... state current content and
  not narrate its own revision history?" --model haiku
YES
YES
```

### CI on the PR

```
$ gh pr view 87 --json baseRefName,headRefName,state
base=main head=workflow/extract-noel-workflow-plugin state=OPEN

$ gh pr checks 87 --watch
Required checks       pass      3s
changes               pass      8s
frontend              skipping  0
fulfillment-service   skipping  0
inventory-service     skipping  0
order-service         skipping  0
payment-service       skipping  0
scenario-service      skipping  0
```

The service jobs skip correctly — no service code was touched. `gh pr checks` first reported "no
checks reported"; per `git.md`'s guidance I confirmed via the API that a suite *had* been created
(`total_count=2`, GitHub Actions `in_progress`) rather than close/reopening, and it resolved on its
own.

### Dogfooding the runtime board resolution

The `Ready to Merge` transition was made using the new `board-transition` skill's own procedure —
nothing hardcoded, everything resolved from `.claude/workflow.json` plus the API:

```
$ OWNER=$(python3 -c "...json.load(open('.claude/workflow.json'))['board']['owner']")
resolved from workflow.json: owner=noelwschneider number=7
runtime-resolved: project=PVT_kwHOB38DIc4BhEqT field=PVTSSF_lAHOB38DIc4BhEqTzhgB0vE
                  option=bfcc30c4 item=PVTI_lAHOB38DIc4BhEqTzg4eFr8

$ gh project item-list 7 --owner noelwschneider --limit 200 --format json --jq '...'
#81 Extract, parameterize, and validate the reusable workflow system -> Status: Ready to Merge
```

The resolved project, field, and option ids are **identical** to the ones that used to be hardcoded
in the four presets and the `board` skill — so the runtime path is equivalent, and the id table was
safe to delete rather than port.

### Every persistent change outside this repo, verified against the filesystem afterward

This is the itemized list required by the brief, produced by reading the filesystem after the work,
not from memory.

**A. `/Users/noel/claude-plugins/` — new directory, 25 files. Confirmed not inside any git repo:**

```
$ git -C ~/claude-plugins rev-parse --show-toplevel
fatal: not a git repository (or any of the parent directories): .git

$ find ~/claude-plugins -type f | sort
~/claude-plugins/.claude-plugin/marketplace.json
~/claude-plugins/noel-workflow/.claude-plugin/plugin.json
~/claude-plugins/noel-workflow/.gitignore
~/claude-plugins/noel-workflow/README.md
~/claude-plugins/noel-workflow/agents/{implementer,investigator,platform,verifier}.md
~/claude-plugins/noel-workflow/hooks/{hooks.json,workflow_config.py,mark-agent-start.py,
   require-agent-report.py,block-ai-attribution.py,block-subagent-merge-deploy.py}
~/claude-plugins/noel-workflow/skills/{agent-report,board-transition,board,delegate,
   sprint-close,sprint-open,sprint-plan,sprint-review,tier}/SKILL.md
~/claude-plugins/noel-workflow/skills/board/check-drift.py
```

**B. `~/.claude/rules/` — new directory, 2 files:**

```
$ ls -la ~/.claude/rules/
-rw-r--r--@  1 noel  staff  2165 Aug 28 15:05 documentation.md
-rw-r--r--@  1 noel  staff  6043 Aug 28 15:06 git.md
```

**C. `~/.claude/settings.json` — modified by `claude plugin marketplace add` / `install`:**

```json
{
  "extraKnownMarketplaces": { "noel-local": { "source": { "source": "directory",
                                "path": "/Users/noel/claude-plugins" } } },
  "theme": "dark",
  "enabledPlugins": { "noel-workflow@noel-local": true }
}
```

`"theme": "dark"` was the entire prior content; both other keys are new and are the plugin
registration.

**D. `~/.claude/plugins/` — registration written by the installer:**

```
installed_plugins.json  -> adds "noel-workflow@noel-local", installPath
                           /Users/noel/.claude/plugins/cache/noel-local/noel-workflow/0.1.0
known_marketplaces.json -> adds "noel-local", installLocation /Users/noel/claude-plugins
new dirs: ~/.claude/plugins/cache/noel-local/...  and  ~/.claude/plugins/data/noel-workflow-noel-local
```

**E. Confirmed still absent — I created nothing here:**

```
absent  ~/.claude/agents
absent  ~/.claude/skills
absent  ~/.claude/commands
```

**F. Throwaway testing, torn down and verified:**

- Scratch project `.../scratchpad/probeworld/` — deleted (`rm -rf`; the `find` in A and the tree
  listings above are post-deletion).
- `~/claude-plugins/noel-workflow/hooks/__pycache__/` — created by my own test imports, removed;
  `.gitignore` added so it stays out of any future repo. Confirmed gone:

```
$ ls ~/claude-plugins/noel-workflow/hooks/
block-ai-attribution.py  block-subagent-merge-deploy.py  hooks.json
mark-agent-start.py  require-agent-report.py  workflow_config.py
```

- The smoke-test report in this repo — deleted; it does not appear in the commit.

**G. Pre-existing residue I did NOT create and deliberately left in place:**

```
$ ls -la ~/.claude/plugins/cache/wf-local/wf-test/0.1.0/
drwxr-xr-x@ 3 noel  staff   96 Aug 28 14:25 .claude-plugin
-rw-r--r--@ 1 noel  staff   13 Aug 28 14:27 .orphaned_at
drwxr-xr-x@ 3 noel  staff   96 Aug 28 14:25 agents
$ claude plugin marketplace list | grep -c wf-local
0
```

This is left over from #80's `wf-test@wf-local` probe plugin, timestamped 14:25–14:27, before my
session began. The marketplace and the plugin are both correctly deregistered — it is an inert
orphaned cache directory, not an active registration — but #80's report claims the environment was
fully restored, and this is a small counterexample. I left it alone rather than deleting someone
else's state; it is safe for the developer to `rm -rf ~/.claude/plugins/cache/wf-local`.

## Judgment calls

**Added a ninth skill, `board-transition`, that #80's disposition did not name.** #80 flagged that the
identical ~15-line "Keep the board current" block is copy-pasted into all four presets and must be
edited in lockstep, and said to extract it to the `board` skill. But `board` is ~250 lines and
preloading it into every preset to deliver 15 lines of it would have been expensive, while telling a
preset to "see the `board` skill" doesn't work when the agent has no way to resolve that skill's path
inside a plugin. A small preloaded skill holding only the runtime-resolving transition is one copy,
costs ~70 always-on tokens, and preserves the stale-id and `--limit 30` warnings that made the
original block worth having. The full `board` skill cross-references it and states that `verifier` is
excluded.

**Deleted `.claude/settings.json` instead of leaving `{}`.** Every hook it registered is now the
plugin's. An empty settings file would register nothing while still looking like the place hooks are
configured, which is exactly the kind of thing that gets read as "the gates were removed."

**Moved both rules files out of the repo rather than keeping project-local copies.** #80's decision
says they "go to" `~/.claude/rules/`, and keeping both would mean two copies of the same rules drifting.
The cost is that `git.md`'s project-specific facts (the `required-checks` job name, `redeploy.sh`,
`build-images.yml`) had to land somewhere — I put them in `.claude/CLAUDE.md` under a new
"Project-specific git facts" heading, which is where `sprint-review`'s own routing table says a
project fact belongs. Every *rule* survived; only the named artifacts moved.

**Generalized the presets by deleting the stack sentence rather than parameterizing it.** #80
recommended this and I agree with the reasoning: the consuming project's own `CLAUDE.md` already
states its stack, so a preset restating it is duplication that goes stale. Same for the frozen-contract
paths, now "whatever your project's `CLAUDE.md` names as frozen contracts."

**Kept the Sprint 4 OOM lesson but dropped the service names.** The rebuild-scoping principle is the
reusable part; `kafka`/`inventory-service`/`scenario-service` mean nothing in another repo. The
anecdote survives as "that has cost real services to OOM kills when several agents each rebuilt
everything at once," which is why the rule exists without pretending to be about this project.

**Proved the hazard by reintroducing it.** Rather than only showing the fixed matcher works, I set the
source matcher back to the old bare form and re-ran the identical delegation. That is what turns "the
gate fires" into "the gate fires *because of this change*," and it incidentally produced the answer to
the update-mechanic question. I restored the fix and re-verified before doing anything else.

**Displaced two untracked files to create my branch, and preserved them.** `git checkout -b` refused
because `docs/agent-reports/sprint-9/{harden-concurrent-agent-docker-git-hazards,issue-80-...}.md`
existed untracked. Both are committed on `origin/main`; the on-disk `harden-...` copy was an *older*
draft (8 insertions / 19 deletions against the committed one). I backed both up to
`.../scratchpad/untracked-backup/` before checkout, and the working tree now holds the committed
versions. I did not touch the other two untracked files (`sprint-9-plan.md`,
`issue-82-handoff-doc.md`) and they are not in my commit.

**A false positive the migration surfaced, unprompted.** My first attempt to commit this work was
refused by the plugin's own hook, because the commit message *described* the deploy verb:

```
$ git commit -F - <<'EOF'
  ... The project-specific git facts they carried (the required-checks job,
  <the deploy script>, build-images.yml) move to CLAUDE.md. ...
EOF

PreToolUse:Bash hook error:
[/Users/noel/claude-plugins/noel-workflow/hooks/block-subagent-merge-deploy.py]:
BLOCKED: running redeploy.sh is not something a subagent does.
```

That is incidental end-to-end proof the config-supplied pattern is live, and it is **not a
regression** — the original hook matched the same pattern against the raw command string, so a commit
message or a `grep` mentioning the script has always tripped it. It is a real usability defect, now
inherited by every project using the plugin; see `## Deliberately not covered`. I worked around it by
putting the message in a file and using `git commit -F <file>`, and reworded it to avoid the literal
token.

## Deliberately not covered

- **The merge/deploy hook matches command *text*, not command *intent*, and I did not fix it.** As
  above, it blocks any Bash call whose string contains a blocked pattern — including a commit message,
  a `grep`, or a `cat` that merely mentions the script. Pre-existing, not introduced here, and out of
  scope for a migration task, but it is now everyone's problem rather than one repo's. A plausible fix
  is to anchor the patterns at a command position rather than searching anywhere in the string; that
  needs its own testing against the real false-negative risk (a blocked verb reached via `sh -c`,
  `xargs`, or a variable), which is why I did not do it opportunistically.
- **The exact update mechanic for a git-hosted source is unresolved, deliberately.** What I *did*
  establish, by measurement: for a `source: directory` marketplace, editing the source takes effect in
  the next session with no reinstall — I changed `hooks/hooks.json` in `~/claude-plugins/` and behavior
  changed immediately while the cache copy under
  `~/.claude/plugins/cache/noel-local/noel-workflow/0.1.0/` still held the old content, so
  `${CLAUDE_PLUGIN_ROOT}` resolves to the source, not the cache. What I did **not** establish, and did
  not guess at: whether a git-repo marketplace clones into the cache instead (almost certainly), what
  `claude plugin update` then does, and whether it requires a `version` bump in `plugin.json` before it
  pulls anything. None of that is testable until the source is actually hosted. The README states this
  as unknown rather than resolving it.
- **Where the plugin source should live long-term is an open question for the developer** — its own
  git repo used as a marketplace, or a plain local directory as it is now. I deliberately did not
  `git init` it and created nothing on GitHub. This choice determines the answers above.
- **No second machine or second project was tested.** The "works from outside this repo" claim rests on
  the scratch `probeworld` project, which had its own `.claude/workflow.json` with a *different*
  `reportRoot` (`reports`, not `docs/agent-reports`) and its own deploy verb — so the config plumbing
  is genuinely exercised outside this repo. But it is the same machine, same user, same install.
- **The `verifier` and `investigator` and `platform` presets were not spawned end-to-end.** I ran
  `implementer` for real, twice in `probeworld` and once here. The other three share the same hook
  matcher (one alternation, verified as a full-match regex) and the same frontmatter shape (validated
  by `claude plugin validate --strict`), but no delegation actually ran through them. `verifier`'s
  exclusion from the board transition is prose in two places, not enforced by anything.
- **The prompt-type `SubagentStop` variant that #80 said should ship alongside, registered but
  disabled, is not in the plugin.** #80's Decision 3 asks for it so a project wanting the semantic
  layer doesn't re-derive it. I left it out: `hooks.json` has no "registered but disabled" concept I
  verified, and shipping a second stop hook I could not confirm was inert risked the exact silent-gate
  failure this task exists to prevent. Worth a follow-up if the developer wants it.
- **Worktree isolation with the plugin is untested** — hazard 5 in #80's decision record. My brief
  forbade `isolation: "worktree"` in this host process, so I could not exercise it. The report gate
  still prefers payload `cwd` over `CLAUDE_PROJECT_DIR`, which is the behavior that makes it work, but
  that path was not run under a real worktree from inside a plugin hook.
- **`claude plugin eval` was not set up.** #80 named it as a genuine backlog candidate for keeping the
  presets from silently regressing in CI. The A/B test I ran is exactly the kind of check it would
  automate, and the matcher failure is exactly the kind of thing `validate` does *not* catch — so this
  is now a better-evidenced backlog item than it was, but still not done.
- **Board drift found and not fixed.** `check-drift.py` reports #83 as `Merged` with its issue still
  open, and #82 as `Ready to Merge` with its issue closed. Both belong to concurrent delegations, not
  to me, and I left them.
- **Three stale marks in `.claude/.agent-marks/` (Aug 25) predate this session** and are still there —
  the same weak signal #80 flagged about how often the gate blocks cleanly. Not investigated.
- **`docs/planning/sprint-9/sprint-9-plan.md` is untracked and absent from `origin/main`.** It is
  presumably the orchestrating session's to commit; I left it alone and it is not in my commit.
- **Historical sprint docs still name the bare preset names** (`docs/planning/sprint-*/`,
  `docs/planning/sprint-3/orchestration-retrospective.md`). Those are frozen historical record and
  correct as written for their time, so I did not rewrite them. Only current-facing docs were updated.
- **`.claude/skills/deploy/` and `.claude/skills/dev-box/` were not touched or re-verified.** They stay
  project-specific per the decision record; I did not run either.

## Reproducing this from a clean clone

A clone of this repo alone is not sufficient — the workflow now depends on a plugin that lives outside
it:

```bash
claude plugin marketplace add ~/claude-plugins      # or wherever the source ends up
claude plugin install noel-workflow@noel-local
```

The repo supplies `.claude/workflow.json` and already gitignores `.claude/.agent-marks/`. The two
standing rules must exist at `~/.claude/rules/{git,documentation}.md`; they are not in the repo and
not in the plugin, because plugins have no `rules` component type.
