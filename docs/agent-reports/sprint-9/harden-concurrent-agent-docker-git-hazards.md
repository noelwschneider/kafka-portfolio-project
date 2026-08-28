# Harden concurrent-agent Docker/git hazards (issue #83)

## What changed

- `docker-compose.yml` — added a top-level `name: kafka-portfolio-project` field, pinning the
  Compose project name so it no longer depends on the invoking directory's basename. This is the
  fix for the stale-image hazard: previously, a `build` run from a differently-named checkout (a
  git worktree, for example) tagged images under that directory's basename by default, while an
  `up` run from the main checkout resolved a different default name, and `up` without `--build`
  would silently reuse whatever image already existed under its own target name rather than the
  one just built.
- `docs/workflow/agent-workflow.md`'s "Open decisions" section, the "Worktree isolation for
  `implementer`" paragraph — rewritten to state the current, tested finding: `isolation: "worktree"`
  still fails end-to-end, and the reason is not the git-version ceiling originally suspected.

## How this was verified

**Compose project-name pin, live against a real build/up cycle.**

Before the fix, from the main checkout:

```
$ docker compose config --format json | python3 -c "import json,sys; print(json.load(sys.stdin)['name'])"
kafka-portfolio-project
```

(Already matched here only because the main checkout's directory happens to be named
`kafka-portfolio-project` — the hazard is invisible until a *different* directory is involved.)

Created a worktree with a deliberately different basename and copied the pinned compose file into
it (the worktree's own HEAD predates the fix, so this simulates what the file will look like once
merged):

```
$ /opt/homebrew/bin/git worktree add /private/tmp/claude-501/verify-compose-fix -b verify/compose-project-name-fix
Preparing worktree (new branch 'verify/compose-project-name-fix')
HEAD is now at fb9f285 docs: add project handoff doc, wire into sprint-close
$ cd /private/tmp/claude-501/verify-compose-fix
$ docker compose config --format json | python3 -c "..."
verify-compose-fix          # <- before copying in the fix: basename-derived, diverges from main checkout

# after cp'ing the pinned docker-compose.yml into the worktree:
$ docker compose config --format json | python3 -c "..."
kafka-portfolio-project     # <- now matches the main checkout, no -p needed
```

Injected a one-line comment marker into `OutboxStatus.java` in the worktree only (main checkout
untouched), then built `order-service` from the worktree directory with no `-p` flag:

```
$ docker compose build order-service
...
#16 naming to docker.io/library/kafka-portfolio-project-order-service:latest done
 Image kafka-portfolio-project-order-service Built
```

From the **main checkout**, brought the stack up with no `--build` and no `-p` — exactly how the
Sprint 8 incident's `up` step ran:

```
$ docker compose up -d postgres kafka order-service
 Container orderfulfillment-order-service Started
```

Confirmed the running container is the image just built in the worktree, by digest (not by
grepping for the comment marker, which doesn't survive compilation — a methodological correction
made mid-verification):

```
$ docker inspect orderfulfillment-order-service --format '{{.Image}}'
sha256:f1594ab4b9c9c50f0591916c5d9bfc571e2361313f48cc54f2d7b9ff83cc65b2
$ docker inspect kafka-portfolio-project-order-service:latest --format '{{.Id}}'
sha256:f1594ab4b9c9c50f0591916c5d9bfc571e2361313f48cc54f2d7b9ff83cc65b2
$ docker images kafka-portfolio-project-order-service --format '{{.ID}}\t{{.CreatedSince}}\t{{.CreatedAt}}'
f1594ab4b9c9    41 seconds ago    2026-08-28 14:28:37 -0500 CDT
$ date
Fri Aug 28 14:29:18 CDT 2026
```

Digest match plus a 41-second-old creation timestamp confirms the main checkout's `up` picked up
exactly the image built moments earlier in the differently-named worktree — no stale image reuse,
no `-p` flag anywhere. This directly reproduces and closes the Sprint 8 incident described in
`docs/agent-reports/sprint-8/issue-50-issue-52-live-stack-verification.md` (lines 66-78: `build`
defaulted to the worktree's basename `verify-sprint8`, `up -p kafka-portfolio-project` silently
reused pre-existing stale `kafka-portfolio-project-*` images).

Torn down cleanly afterward:

```
$ docker compose down
 Container orderfulfillment-order-service Removed
 Container orderfulfillment-postgres Removed
 Container orderfulfillment-kafka Removed
 Network kafka-portfolio-project_default Removed
$ /opt/homebrew/bin/git worktree remove /private/tmp/claude-501/verify-compose-fix --force
$ /opt/homebrew/bin/git branch -D verify/compose-project-name-fix
Deleted branch verify/compose-project-name-fix (was fb9f285).
$ docker ps -a --format 'table {{.Names}}\t{{.Status}}'
NAMES     STATUS
```

**Orphaned containers (scope item 3).** Checked the current state of the host before doing anything
else:

```
$ docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Labels}}'
NAMES     IMAGE     STATUS    LABELS
```

No containers running at all, orphaned or otherwise — nothing to sweep. The one-time cleanup this
task's scope allowed for wasn't needed at the time of this session; nothing was removed because
nothing was there.

**Worktree isolation, tested for real via the Agent tool.** Spawned a real subagent with
`isolation: "worktree"` (a `general-purpose` agent, given a harmless probe task: report git state,
write one marker file, commit it, no push). The spawn itself failed before the agent ever started:

```
Failed to create worktree: error: unknown option `no-track'
usage: git worktree add [<options>] <path> [<branch>]
   or: git worktree list [<options>]
   or: git worktree lock [<options>] <path>
   or: git worktree prune [<options>]
   or: git worktree unlock <path>

    -f, --force           checkout <branch> even if already checked out in other worktree
    -b <branch>           create a new branch
    -B <branch>           create or reset a branch
    --detach              detach HEAD at named commit
    --checkout            populate the new working tree
    --lock                keep the new working tree locked
```

`--no-track` was added to `git worktree add` in git 2.19; this usage text is git 2.15's, confirming
the Agent tool's own worktree-creation subprocess resolved plain `git` to the old
`/usr/local/bin/git` 2.15.0, not Homebrew's 2.50.1 — despite the developer's PATH fix. Checked
whether this was a real version ceiling or a resolution problem, using this session's own Bash
tool as a second, independent probe of the same host:

```
$ git --version
git version 2.15.0
$ which -a git
/usr/local/bin/git
/usr/bin/git
/opt/homebrew/bin/git
$ echo $PATH
/Users/noel/.local/bin:/Users/noel/.nvm/versions/node/v22.16.0/bin:/usr/local/bin:/usr/bin:/bin:...
   (/opt/homebrew/bin appears much later in PATH)
```

Confirmed manually that the *binaries themselves* are fine — `git worktree add` and, critically,
`git worktree remove` (missing entirely from 2.15.0, which only lists `add|list|lock|prune|unlock`
in its own usage text) both work correctly when invoked as `/opt/homebrew/bin/git` directly:

```
$ /opt/homebrew/bin/git worktree add /private/tmp/claude-501/wt-probe-test -b probe/worktree-path-test
Preparing /private/tmp/claude-501/wt-probe-test (identifier wt-probe-test)
HEAD is now at fb9f285 docs: add project handoff doc, wire into sprint-close
$ /opt/homebrew/bin/git worktree remove /private/tmp/claude-501/wt-probe-test --force
$ /opt/homebrew/bin/git branch -D probe/worktree-path-test
Deleted branch probe/worktree-path-test (was fb9f285).
```

So the git 2.50.1 binary is present, correct, and fully capable of the whole worktree lifecycle.
The gap is that neither this session's Bash tool nor the Agent tool's own worktree-creation
subprocess resolves `git` to it — both land on `/usr/local/bin/git` ahead of
`/opt/homebrew/bin/git`, the exact shadowing this sprint's planning believed was already fixed.
`~/.zshrc` and `~/.zprofile` were read directly and do put `eval "$(/opt/homebrew/bin/brew
shellenv)"` early in `.zprofile`, which should put Homebrew ahead — but the live `$PATH` observed
in this session doesn't match what sourcing those files fresh would produce, which points at the
process hosting this session (and therefore its subagents) having been started before the PATH fix
landed and never having picked it up since, rather than the rc files themselves being wrong.

## Judgment calls

- **Did not attempt to fix the PATH/process issue myself.** Restarting the developer's Claude Code
  process (or however this session's host process is launched) isn't something available to me
  from inside a Bash tool call, and guessing at the launch mechanism (Terminal alias, LaunchAgent,
  Dock icon, IDE integration) without evidence would be exactly the kind of unverified hypothesis
  the original PATH misdiagnosis already cost this project once. Documenting the precise, reproduced
  failure and the specific fact that stops it from being a version ceiling (manual invocation of the
  correct binary works fully) is the actionable handoff; deciding how the developer's session gets
  restarted is theirs to do.
- **Used `/opt/homebrew/bin/git` explicitly for my own verification worktree work** (creating and
  tearing down `verify-compose-fix` and, later, the real fix branch's worktree) rather than plain
  `git`, once I'd confirmed plain `git` in this shell shadows to 2.15.0. This kept my own compose-fix
  verification uncontaminated by the exact bug I was working around, without needing the PATH itself
  fixed first.
- **Did not touch the shared checkout's existing branch state.** Mid-task, `git status` on the main
  checkout showed it was on `docs/handoff-doc-sprint-close` (not `main`) with untracked
  `docs/planning/sprint-9/` and `docs/agent-reports/sprint-9/` directories that weren't mine —
  evidence another session may be using this same physical checkout concurrently, which is itself an
  instance of the shared-clone hazard this issue exists to reduce. Rather than committing there, I
  reverted my own edit back to that branch's committed state (confirmed identical to `origin/main`
  first) and did all real work — the fix, the doc update, this report, the commit, and the push — in
  a fresh worktree off `origin/main`, leaving the shared checkout exactly as found.
- **Grepped compiled bytecode for a comment, which doesn't survive `javac`, and got a true negative
  I initially had to second-guess.** Caught it immediately by falling back to image digest and
  creation-timestamp comparison, which is strictly more conclusive anyway (exact content-addressed
  match, not a string heuristic) and is recorded as the actual evidence above.
- **Chose `name:` (the Compose Specification's project-name field) over a `.env`-file
  `COMPOSE_PROJECT_NAME`.** Both close the gap identically in Compose's precedence order (`-p` flag >
  `COMPOSE_PROJECT_NAME` env > `name:` in the file > directory basename) for the case that actually
  caused the incident (nobody passing an explicit conflicting `-p`). `name:` was chosen because it
  travels with the compose file itself in every checkout including worktrees with no separate file to
  keep in sync, whereas a `.env` file would need `.gitignore`/`.worktreeinclude` handling to reach a
  fresh worktree checkout at all — the exact unresolved `.worktreeinclude` gap noted in
  `docs/workflow/agent-workflow.md`'s Open Decisions. Did not add explicit `image:` tags on top of
  this; the project-name pin alone closes the specific divergence that caused the incident, and
  Compose's default `<project>-<service>:latest` tagging is sufficient once the project name is
  stable.
- **Left the pre-existing `kafka-portfolio-project-flow-review-*` and `order-service:local`-style
  images on disk untouched.** These are leftover evidence of the pre-fix problem pattern (a
  previously-used worktree named `flow-review` producing its own diverged image namespace), but
  they're disk usage, not the running-memory hazard ADR-010/ADR-011 document, and issue #83's scope
  is explicitly about *containers* consuming memory, not stale images on disk. Removing someone's
  local Docker image cache wasn't asked for and isn't reversible-for-free if a rebuild is wanted
  later, so I left them as-is.

## Deliberately not covered

- **Did not confirm worktree isolation works after a Claude Code process restart.** That restart
  isn't something I can trigger from inside this session, and per this task's own scope, designing a
  fallback isolation strategy if it still doesn't work is explicitly out of scope for this task — the
  finding here is the honest "still fails, here's exactly why" the task asked for, not a fix.
  Whoever restarts the host process next should re-run the same probe (spawn any subagent with
  `isolation: "worktree"` and a trivial task) as the actual confirmation step.
- **Did not investigate why this session's own Bash-tool shell resolves the shadowed git** beyond
  reading `~/.zshrc`/`~/.zprofile` and observing that the live `$PATH` doesn't match what sourcing
  them fresh would produce. Diagnosing exactly which process in the launch chain is stale (Claude
  Code's own long-lived host process vs. some other cached shell) would need information about how
  that process was started that isn't visible from inside a sandboxed Bash call.
- **Did not open a PR for the worktree-isolation documentation change separately from the
  compose-project-name fix.** Both are commits on the same `platform/pin-compose-project-name`
  branch/PR (#85) since both close findings under the same issue #83, but they're separate commits,
  each independently revertable.
- **Did not re-test the two findings ff9a7b6 already closed** (SubagentStop report-path check,
  pre/post-merge base-branch verification) — out of scope per this task's brief, and nothing in this
  session's work touched either mechanism.
- **Did not investigate or clean up the pre-existing `kafka-portfolio-project-flow-review-*` /
  `*-local` images sitting in the local Docker image cache** — disk space, not the memory hazard this
  issue's cleanup scope covers; see Judgment calls above.
