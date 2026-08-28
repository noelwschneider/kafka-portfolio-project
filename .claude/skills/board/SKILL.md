---
name: board
description: Add to or update the GitHub Project board - create backlog items, move status through its full lifecycle, set sprint, convert drafts to real issues, and log unplanned work. Use whenever work is identified, started, finished, merged, or deployed.
argument-hint: what to add or change
allowed-tools: Bash(gh project *), Bash(gh issue *), Bash(gh api *)
---

# Kafka Portfolio Project board

Board: https://github.com/users/noelwschneider/projects/7 — owner `noelwschneider`, number `7`,
project id `PVT_kwHOB38DIc4BhEqT`.

The board tracks live status. `docs/planning/sprint-N/sprint-N-plan.md` owns goals, dependencies, and
rationale. Do not duplicate rationale into board items, and do not track live status in the markdown.

There is no Priority field. It existed as Tier 1/Tier 2/Shelved through Sprint 8 and was removed in
Sprint 8's review pass — it never actually drove a sequencing decision (sprint selection went by the
criteria in the `sprint-plan` skill, not by scanning tier values), and its one genuinely useful
distinction, "deprioritized indefinitely" vs. "just not yet scheduled," is now a Status value
(`Shelved`) instead of a second field.

## Fields

| Field | Field id | Values (option id) |
|---|---|---|
| Status | `PVTSSF_lAHOB38DIc4BhEqTzhgB0vE` | Backlog `f75ad846`, Shelved `082c8d65`, Planned `9935e4cd`, In Progress `47fc9ee4`, Ready to Merge `bfcc30c4`, Merged `0fdb8e6a`, Deployed `d546d412` |
| Sprint | `PVTSSF_lAHOB38DIc4BhEqTzhgB9D0` | Sprint 2 `97a0e823`, Sprint 3 `e4a85fe9`, Sprint 4 `ae9c5ee9`, Sprint 5 `04b1e3dd` |

This table can still drift stale if a field is edited by hand (GitHub's UI, or a mutation that omits
existing ids) — re-read before trusting it whenever a write fails:

```bash
gh project field-list 7 --owner noelwschneider --format json
```

## Status lifecycle

Seven states, in the order work actually moves through them:

`Backlog` → `Planned` → `In Progress` → `Ready to Merge` → `Merged` → `Deployed`

`Shelved` sits outside that line — deprioritized indefinitely, not on a path to anywhere until
something changes. An item moves *into* Shelved from wherever it currently sits, and back to
`Backlog` (never straight to `Planned`) if it's revived.

**Who advances which transition** — this is what keeps the board from going stale, not a suggestion:

- **Backlog → Planned → In Progress**: the orchestrating session, during `/sprint-plan` and
  `/sprint-open` — see those skills.
- **In Progress → Ready to Merge**: whoever is doing the work. A delegated subagent (`implementer`,
  `investigator`, `verifier`, `platform`) moves its own tracked item to `Ready to Merge` once it has
  pushed a branch and opened a PR — see each preset's "Keep the board current" section. This is the
  transition most likely to get missed, because it happens inside a subagent's own turn, not the
  orchestrating session's.
- **Ready to Merge → Merged**: the orchestrating session, immediately after actually running
  `gh pr merge` — merging is never a subagent's call (`.claude/rules/git.md`), so this transition only
  ever happens in the developer-facing session.
- **Merged → Deployed**: the orchestrating session, after a `/deploy` run's Stage 4 verification
  confirms the merged commit is actually live. Not automatic — this project's deploys are manual and
  often bundle several sprints' merged-but-undeployed work into one production push (Sprint 8 did
  exactly this), so "merged" and "deployed" can sit apart for a real stretch of time on purpose.

**Validation.** `check-drift.py` in this skill's directory cross-checks every board item with a linked
GitHub Issue against that issue's actual open/closed state, and reports anything that contradicts
(e.g. Status says `Deployed` but the issue is still open — a real signal something was never advanced,
or never closed). It cannot tell `Merged` apart from `Deployed` — that needs knowing what's actually
live, which is a deploy-verification fact, not something derivable from issue state alone. Run it by
hand any time; `/sprint-close`'s board reconciliation step runs it as part of Step 1.

```bash
.claude/skills/board/check-drift.py
```

## Adding a new Sprint value

Needed once per sprint, the first time anything gets tagged with a sprint that doesn't exist yet as
an option. `gh project field-create` only creates whole new fields — adding an option to an existing
single-select field needs a GraphQL mutation, and the mutation replaces the *entire* option list. Pass
every existing option's `id` through **unchanged** alongside the new one (which gets no `id`, since it
doesn't exist yet) — verified live: doing this preserves every existing id exactly, so this is safe to
run without re-tagging anything afterward. Omitting an existing id is what regenerates it.

Fetch current options first:

```bash
gh api graphql -f query='
query {
  user(login: "noelwschneider") {
    projectV2(number: 7) {
      field(name: "Sprint") {
        ... on ProjectV2SingleSelectField { id options { id name color description } }
      }
    }
  }
}'
```

Then add the new one, keeping every existing option's `id` field exactly as returned:

```bash
gh api graphql -f query='
mutation {
  updateProjectV2Field(input: {
    fieldId: "PVTSSF_lAHOB38DIc4BhEqTzhgB9D0"
    singleSelectOptions: [
      { id: "97a0e823", name: "Sprint 2", color: GRAY, description: "" }
      { id: "e4a85fe9", name: "Sprint 3", color: GRAY, description: "" }
      { id: "ae9c5ee9", name: "Sprint 4", color: GRAY, description: "" }
      { name: "Sprint 5", color: GRAY, description: "" }
    ]
  }) { projectV2Field { ... on ProjectV2SingleSelectField { options { id name } } } }
}'
```

The response echoes back every option's id — confirm the existing ones match what you sent before
trusting them for a subsequent write.

## Converting a draft item to a real Issue

Needed when a draft backlog item gets scheduled into a sprint (per the Rules section below). `gh`
has no native subcommand for this — it needs a GraphQL mutation. Get the repository's node id once:

```bash
gh api graphql -f query='query { repository(owner: "noelwschneider", name: "kafka-portfolio-project") { id } }'
```

Then convert, passing the draft's project **item id** (not the draft's own `DI_...` content id):

```bash
gh api graphql -f query='
mutation {
  convertProjectV2DraftIssueItemToIssue(input: {
    itemId: "<PVTI_... item id, from gh project item-list>"
    repositoryId: "<repository id from above>"
  }) {
    item { id content { ... on Issue { number title url } } }
  }
}'
```

The project item id stays the same across the conversion — every field already set on it (Status,
Sprint) carries over untouched. Only the `content` changes from a `DraftIssue` to a real `Issue` with
a number and a repo URL. The draft's title and body become the new issue's title and body
automatically.

## Editing a draft item's title or body

`gh project item-edit` only sets fields (Status, Sprint) — it has no option for a draft's
title or body, and its `--field-id` flag will happily point at the Title field and silently overwrite
it with whatever `--text` you pass, with no confirmation and no diff. This has actually happened:
testing an edit against the wrong field replaced a draft's title with placeholder text before the
mistake was caught. Use the dedicated mutation instead, addressed by the draft's own content id (the
`DI_...` id, not the `PVTI_...` project item id):

```bash
gh api graphql -f query='
mutation {
  updateProjectV2DraftIssue(input: {
    draftIssueId: "<DI_... id, from the item'"'"'s content.id in item-list output>"
    body: "<full new body text>"
  }) { draftIssue { id title } }
}'
```

Omit `title` from the input to leave it untouched — pass both only when you actually mean to change
both. When escaping a multi-line body inside the shell-quoted GraphQL string, watch for embedded
apostrophes breaking out of the surrounding single-quoted `-f query='...'` — a stray one is exactly
what leads to fumbling with `--field-id` instead as a workaround, which is how the title got
overwritten in the first place.

## Common operations

List items, with their item ids:

```bash
gh project item-list 7 --owner noelwschneider --limit 200 --format json
```

**The default limit is 30 and silently truncates — it returned an incomplete list against this real
board once already.** The JSON has a `totalCount` field; if `len(items) < totalCount`, the limit was
too low and the list is incomplete. Raise `--limit` and re-run rather than trusting a partial list, especially before a duplicate-check.

Add a backlog entry as a draft item:

```bash
gh project item-create 7 --owner noelwschneider --title "<title>" --body "<one-line scope>"
```

Set a single-select field on an item:

```bash
gh project item-edit --project-id PVT_kwHOB38DIc4BhEqT --id <item-id> \
  --field-id <field-id> --single-select-option-id <option-id>
```

When editing several items in one pass, fetch each item's id fresh (`gh project item-list`) in the
same command/script that uses it, rather than reusing an id copy-pasted from an earlier tool call's
output. Two different issues can look alike at a glance across a long session, and a mismatched id
silently edits the wrong item with no error — this has actually happened.

## Before creating anything: check for an existing item

List the full backlog and read titles *and bodies* for overlap — not just exact title matches. A
gap phrased differently from an existing item is still the same item.

```bash
gh project item-list 7 --owner noelwschneider --limit 200 --format json
```

- **Same scope as an existing item:** do not create a duplicate. Report that it already exists and
  where.
- **Genuinely overlaps but adds something the existing item doesn't cover:** say so rather than
  deciding unilaterally. Creating a second item and letting them silently drift apart is worse than
  asking which one should hold the additional scope.
- **Related but distinct** (shares a component or a root cause, not the actual work): create it, and
  reference the related item in the body so the connection isn't lost.

When genuinely unsure whether something overlaps, say so and let the developer decide rather than
guessing either way.

## Is this worth a card?

Not everything worth mentioning is worth tracking. Before creating an item, check:

- **Would it be lost otherwise?** If it is already written down somewhere durable — a contract gap in
  `docs/CHANGELOG-contracts.md`, a rule in `.claude/rules/`, a decision in an ADR — a board item is a
  second, driftable copy of the same fact. Don't create one.
- **Is it Task-sized?** A concrete, independently-completable unit someone could pick up without
  first having to figure out what it even means. If it's still a vague direction rather than a
  scoped task, say so instead of creating a stub item that will need to be redefined before anyone
  can act on it.
- **Is it small enough to just fix?** A typo, a one-line stale note, a broken link. Fixing it costs
  less than tracking it. Do that instead, and mention it under `## What changed` in whatever report
  you're already filing.

A defect or gap named honestly in a report's `## Deliberately not covered` is the common source for
this — and naming it there was already the right call. Converting it to a board item only when it
clears these three checks is what keeps the backlog signal instead of noise.

## Rules

**Drafts until scheduled.** Backlog entries start as draft items — cheap, no repo noise. Convert to a
real repo Issue only when something is pulled into a sprint, so it can be referenced from commits
(`Closes #12`) and the Issues tab shows only work actually being done.

**Two levels, not three.** An *Initiative* is a big-picture objective; a *Task* is one concrete,
independently-completable unit sized to a single delegation. Most items are already Task-sized and the
Issue is the Task. Decompose an Initiative into sub-issues, using GitHub's native parent/child links
rather than a label, only when it genuinely has multiple separable deliverables — and decide that at
sprint-selection time when the real seams are visible, not upfront.

**Log unplanned work too, retroactively if needed.** A board that reflects only planned work becomes
inaccurate the first time something urgent jumps the queue.

**A new item needs a title and a Status.** Set Sprint only when it is actually scheduled.

**Advance Status as work actually moves, not in a batch afterward.** A board that only gets updated
during `/sprint-close` has been silently wrong for the entire sprint in between — see the Status
lifecycle section above for who owns which transition.
