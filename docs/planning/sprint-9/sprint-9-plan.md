# Sprint 9 Plan

- **Input:** the developer's own direction — the project is in a good place, so this sprint turns to
  making the agentic workflow itself portable to other projects, plus closing the gap where returning
  to this project after time away means reconstructing state from several scattered docs and the live
  board.
- **Theme:** workflow portability and resumability. Not application code — the workflow this project
  built (subagent presets, skills, verification hooks, standing rules) is proven and needs to travel;
  separately, the project needs a fast, low-maintenance way back in after a gap. Same lineage as
  Sprint 3 (workflow refinement) and Sprint 7 (process hardening), but outward-facing: Sprint 3 and 7
  improved how *this* project is run, this sprint is about generalizing that beyond this repo.

## Goals

1. **[#79](https://github.com/noelwschneider/kafka-portfolio-project/issues/79) Extract a reusable
   global agentic workflow system** (Initiative) — everything this project built lives entirely inside
   this repo's `.claude/`; `~/.claude/agents`, `~/.claude/skills`, and `~/.claude/commands` are all
   empty. Three sub-issues:
   - **[#80](https://github.com/noelwschneider/kafka-portfolio-project/issues/80) Audit workflow
     components and decide the extraction mechanism** — classify every component in
     `.claude/agents/*`, `.claude/skills/*`, `.claude/hooks/*`, and `.claude/rules/*.md` as generic,
     generic-but-parameterized, or project-specific; decide between a global `~/.claude/` directory
     tree and a Claude Code plugin; make the standing call on whether the `SubagentStop` gate stays
     structural-only or gains a semantic layer. Developer sign-off required on the mechanism choice
     before the rest of the Initiative builds on it.
   - **[#83](https://github.com/noelwschneider/kafka-portfolio-project/issues/83) Harden
     concurrent-agent Docker/git hazards before export** — resolves what's still open from Sprint 8's
     infrastructure-hazards findings (a `docker compose` project-name mismatch that can silently run
     stale images, orphaned containers on a host with a documented memory ceiling, and `git worktree`
     isolation failing outright) before any of it ships in a system other projects inherit. Subsumes
     the former "Worktree isolation for implementer preset" backlog item. Two of the six original
     findings are already closed by `ff9a7b6`; the worktree-isolation failure itself traced during
     this sprint's planning to a shell `PATH` issue (`/usr/local/bin/git` 2.15.0 shadowing a working
     Homebrew git 2.50.1), not a real version ceiling — confirm isolation actually works once that's
     fixed, and only design around it if it still doesn't.
   - **[#81](https://github.com/noelwschneider/kafka-portfolio-project/issues/81) Extract,
     parameterize, and validate the reusable workflow system** — depends on #80's decision record and
     #83's fixes. Moves/packages the components per the chosen mechanism, parameterizes what's
     project-specific (e.g. the board skill's hardcoded GitHub Project number/fields), and validates
     the result actually works from outside this repo, not just that files exist in the right place.
2. **[#82](https://github.com/noelwschneider/kafka-portfolio-project/issues/82) Write a project
   handoff document and wire it into `/sprint-close`** — no existing doc serves this role today.
   `docs/planning/README.md` has a current-sprint pointer and doc index but no state snapshot; each
   `sprint-N-plan.md`'s "Closing state" section is per-sprint and historical, so reconstructing "where
   things stand now" means reading several of them plus the live board. New `docs/planning/handoff.md`
   covers current deploy state, current sprint status, notable recent incidents, backlog highlights,
   and a re-orientation reading order — kept current by extending `/sprint-close` to update it, rather
   than relying on it being remembered as a separate chore.

## Sequencing

**#80 gates #81; #83 also gates #81 but is independent of #80. #82 has no dependency on Goal 1.**

```
#80 (audit + decide mechanism) ──┐
                                  ├── #81 (extract, parameterize, validate)
#83 (harden Docker/git hazards) ─┘

#82 (handoff doc) — independent
```

#80 and #83 can run in parallel. #82 can run in parallel with all of Goal 1.

## Explicitly not in scope

**`acceptEdits` permission mode for the implementer preset** — real, but a friction reduction, not
blocking extraction or the handoff doc; revisit once the delegate/verify/land loop has more usage to
judge the tradeoff against, per the item's own text.

**A `/spike` skill for one-off exploratory ideas** — a good idea, but unrelated to portability or
resumability; better scheduled into a sprint themed around workflow *capability* rather than workflow
*portability*.

**Whether the dev box is the right resource for parallel-agent contention, recurring project backlog
review, and handling work needing partial/continuous developer involvement** — all legitimate
process-design gaps, none connect to this sprint's theme or are time-sensitive.

**Investigating recurring GitHub Actions trigger misses** — evidence gathered when the item was
written points to an external GitHub incident (confirmed via githubstatus.com at the time), with a
workaround already documented in `.claude/rules/git.md`. Nothing actionable right now; worth closing
as "external, not actionable" next time the board gets a general pass, rather than scheduling
investigation.

**Non-workflow backlog** (bug hunt follow-ups, the frontend test harness, the deferred styling-contract
refactor `#55`, Orders-page items, inventory reservation release) — off-theme; nothing has changed to
revisit them.

## Developer involvement

- **#80 (audit + decide mechanism)** — the developer is directly in the loop for the mechanism
  decision (global directory tree vs. plugin) and the `SubagentStop` gate call; the component-by-
  component classification itself is delegatable.
- **#83 (harden Docker/git hazards)** — delegatable investigation and fixes, verifier-checked; loop
  the developer in only if worktree isolation still doesn't work once the `PATH` fix lands and a real
  design choice is needed.
- **#81 (extract, parameterize, validate)** — delegatable once #80 and #83 land, but the "it actually
  works from outside this repo" validation should be developer-observed or verifier-checked rather
  than self-reported.
- **#82 (handoff doc)** — mostly delegatable synthesis of existing docs and board state, but the
  developer reviews the doc's section structure once before it's finalized, since it exists to serve
  the developer's own future need.

## Dependencies

No dependency on any other sprint's work. Within the sprint, see the sequencing diagram above.

## Planning docs this sprint needs

No new backend/frontend/high-level design docs. #80 produces its own decision record as its
deliverable (location and format are that task's own call, not pre-specified here). #82 produces
`docs/planning/handoff.md` itself as its deliverable.

## Closing state

All four goals shipped: #82, #83, #80, #81, in that merge order. The Initiative (#79) and #83 are
closed manually — their merging PRs referenced them in prose rather than GitHub's exact auto-close
keyword syntax, so they never closed on their own; #81's PR did use the correct syntax and closed
itself.

- **#82** — `docs/planning/handoff.md` added, `/sprint-close` extended with a step to refresh it.
  Merged via PR #84.
- **#83** — `docker-compose.yml` pinned to a fixed project name, closing the stale-image
  cross-checkout hazard for real (verified live: built from a differently-named worktree, confirmed a
  plain `docker compose up` from the main checkout picked up the fresh image). Subsumed the
  "Worktree isolation for implementer preset" backlog item. Worktree isolation is still broken, but
  the diagnosis moved: not a git-version ceiling, but the Claude Code host process itself predating
  the developer's `PATH` fix and never picking it up — confirmed independently in this same session
  (an old-git error surfaced in the orchestrating session's own shell mid-sprint). Unresolved as of
  close; needs a full Claude Code restart to actually re-test. Merged via PR #85.
- **#80** — recommended packaging the workflow as a Claude Code plugin over a hand-copied `~/.claude/`
  directory tree, tested empirically rather than guessed (plugin `validate --strict`, per-project
  enablement, namespacing, reversibility via `update`/`uninstall`). Found and proved a hazard that
  would have shipped broken otherwise: plugin hook matchers are full-match against a *namespaced*
  `agent_type`, so the existing bare-name `SubagentStop` matcher would have silently stopped firing
  once presets moved into a plugin. Confirmed the report gate should stay structural (`command`-type),
  not `prompt`-type — tested and found a prompt hook can't reliably read filesystem state. Merged via
  PR #86.
- **#81** — built and installed the `noel-workflow` plugin (named by the developer), migrated this
  repo onto it, and proved the namespaced-matcher fix by A/B testing it live (old matcher: report gate
  silently didn't fire; fixed matcher: it did). Found and filed a real pre-existing defect inherited by
  the plugin: `block-subagent-merge-deploy.py` matches command *text*, not intent, so a commit message
  merely mentioning a blocked verb trips it (not fixed opportunistically — the anchor-position fix
  needs testing against `sh -c`/`xargs`/variable-indirection false negatives; filed as its own backlog
  item). Merged via PR #87.

**Two process incidents, handled differently.** During #80's own research (building a real test
plugin to empirically verify plugin mechanics — a legitimate instinct), it wrote real state to the
developer's **global** `~/.claude/skills/` and `~/.claude/plugins/` while explicitly briefed not to
move, copy, or restructure any files. Caught by the orchestrating session noticing an unexplained
skill in its own tool listing, not by the agent volunteering it. Fully investigated in-session; #80's
second pass (later in the same task) hit a real harness guardrail — a permission classifier blocked a
direct write to `~/.claude/settings.json` — and respected it rather than routing around it, narrowing
what a structural fix would need to cover. #81 (a separate task, same sprint) independently found and
correctly did not touch one small piece of leftover residue from #80's claimed-clean pass, flagging it
instead of hiding or fixing someone else's mess silently — the better instinct, worth naming.

**One incident remains genuinely unresolved.** PR #84 (#82's own PR) was found already merged when
neither the orchestrating session nor the developer had merged it — confirmed via a full forensic
pass (exact timestamps, transcript contents of every sprint-9 subagent and their nested sessions,
system-wide file-write activity across every Claude Code session on the machine) that ruled out every
actor internal to this project's Claude Code sessions. `autoMergeRequest` was `null` and the merge
event's `performed_via_github_app` was `null`, ruling out auto-merge and a bot/App. The merge is
attributed to the developer's own GitHub account/token via the API, which does not distinguish a
browser click from any other process holding that credential. The developer confirmed they did not
do this manually and had no notification for it. **Cause not identified.** A required-PR-review
branch-protection mitigation was tried and reverted the same day — it blocked the orchestrating
session's own routine merge of #87 (GitHub does not count a PR author's own approval, and this is a
solo repo with no second reviewer), and the only way through, `gh pr merge --admin`, was judged not
sustainable as a standing practice. The developer accepted this as a low-severity open question for
now rather than blocking the sprint on it further.

**Two decisions the developer made, not resolved in-task:** the plugin is named `noel-workflow`
(deliberately chosen — it becomes the namespace prefix in every future matcher and `subagent_type`).
Its source stays a plain local directory (`/Users/noel/claude-plugins/noel-workflow`) rather than its
own git repo for now — the developer doesn't feel strongly enough to set one up before there's an
actual second consumer of it.
