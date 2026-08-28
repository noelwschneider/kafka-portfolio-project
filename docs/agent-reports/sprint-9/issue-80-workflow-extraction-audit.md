# Issue #80 — Workflow component audit and extraction mechanism decision

## What changed

- `docs/planning/sprint-9/workflow-extraction-decision.md` — new. The deliverable: per-component
  disposition table for all 21 components, the extraction mechanism decision with the measured
  tradeoffs that drove it, the `SubagentStop` gate decision, and a sequenced hazard list for #81.

No component in `.claude/` was moved, copied, or restructured — that is #81's job and was explicitly
out of scope here.

Two files in the working tree are **not mine** and were left untouched: `docker-compose.yml`
(modified) and `docs/agent-reports/sprint-9/issue-82-handoff-doc.md` (untracked) belong to concurrent
#83 and #82 delegations sharing this checkout. My commit is scoped to my own two files.

## How this was verified

Every mechanism claim in the decision record was established by running Claude Code 2.1.241 against
throwaway probe plugins, then torn down. Nothing below is inferred from documentation.

### Plugins can ship agents, skills, and hooks together

```
$ claude --version
2.1.241 (Claude Code)

$ claude plugin init --help
Usage: claude plugin init|new [options] <name>
Scaffold a new plugin at ~/.claude/skills/<name>/ (auto-loads next session as
<name>@skills-dir)
Options:
  --with <components...>  Also scaffold: skills, agents, hooks, mcp, lsp,
                          output-style, channel
```

Note the absence of `rules` from that component list — confirmed against a real plugin's layout too
(`claude-security` ships `agents/`, `hooks/hooks.json`, `scripts/`, no rules).

### Plugin agents are namespaced, and bare names are rejected

Probe plugin `wf-probe` with agent `probe-impl`, spawned from a headless session:

```
$ claude -p "Make exactly ONE Agent tool call with subagent_type set to the literal string
  'probe-impl' ... Your entire final message must start with either OK: or ERROR:" \
  --allowedTools "Agent" --model haiku

ERROR: Agent type 'probe-impl' not found. Available agents: claude, claude-code-guide,
Explore, general-purpose, Plan, statusline-setup, wf-probe:probe-impl, wf-test:localagent
```

### Hook matchers are full-match regexes against the namespaced `agent_type`

Three `SubagentStart`/`SubagentStop` entries registered simultaneously — matcher `probe-impl`,
matcher `wf-probe:probe-impl`, and one with no matcher. Only the namespaced and unmatched ones fired:

```
$ grep '^=== ' probe.log
=== START_NAMESPACED | CLAUDE_PLUGIN_ROOT=/Users/noel/.claude/skills/wf-probe | CLAUDE_PROJECT_DIR=.../probeworld ===
=== STOP_NOMATCHER   | CLAUDE_PLUGIN_ROOT=/Users/noel/.claude/skills/wf-probe | CLAUDE_PROJECT_DIR=.../probeworld ===
=== STOP_NAMESPACED  | CLAUDE_PLUGIN_ROOT=/Users/noel/.claude/skills/wf-probe | CLAUDE_PROJECT_DIR=.../probeworld ===

$ # payload from the SubagentStart entry
    agent_id = a1063f38c539e56fd
    agent_type = wf-probe:probe-impl
    hook_event_name = SubagentStart
    all keys: ['agent_id', 'agent_type', 'cwd', 'hook_event_name', 'prompt_id', 'session_id', 'transcript_path']
```

`START_BARE` / `STOP_BARE` (matcher `probe-impl`) never appear, even though `wf-probe:probe-impl`
*contains* that string — so matching is a full match, not a substring search. A second run pinned the
semantics down precisely, with matchers `probe` (substring) and `.*probe-impl` (unanchored regex that
full-matches):

```
$ grep -E '^(===|GATE_)' probe.log
=== MATCH_REGEX_UNANCHORED | ... ===
GATE_FIRED_BLOCK 1787945012
=== MATCH_REGEX_UNANCHORED | ... ===
GATE_FIRED_ALLOW 1787945016
```

`MATCH_SUBSTRING` never fired; `MATCH_REGEX_UNANCHORED` did. This is the finding that makes today's
`"implementer|investigator|verifier|platform"` matcher a silent failure under a plugin.

### `exit 2` blocking survives the move to a plugin

Same run as above. The gate script exited 2 on first stop demanding a file be created; the agent
complied and was allowed through on the retry:

```
GATE_FIRED_BLOCK 1787945012
GATE_FIRED_ALLOW 1787945016

$ ls -la probeworld/
-rw-r--r--@ 1 noel  wheel    4 Aug 28 14:23 proof.txt
```

### Plugin-bundled skills preload into plugin agents; slash commands resolve both ways

Agent frontmatter `skills: [probe-report]` (bare name), skill body instructing the phrase
`SKILL_PRELOAD_OK`:

```
$ claude -p "...spawn that agent... 'state the exact phrase from your preloaded report contract
  skill if you have one, or say NO_SKILL'" --allowedTools "Agent" "Bash(echo:*)" --model haiku

**Agent's exact final message:**
"The exact phrase from the preloaded report contract skill is: **SKILL_PRELOAD_OK**"
```

```
$ claude -p "/wf-probe:probe-cmd" --model haiku
PROBE_CMD_RESOLVED
$ claude -p "/probe-cmd" --model haiku
PROBE_CMD_RESOLVED
```

### Local install works from a plain directory, and from a skills-dir drop-in

```
$ claude plugin marketplace add /.../scratchpad/local-marketplace
Adding marketplace…✔ Successfully added marketplace: wf-local (declared in user settings)

$ claude plugin install wf-test@wf-local
Installing plugin "wf-test@wf-local"...✔ Successfully installed plugin: wf-test@wf-local (scope: user)

$ claude plugin list
Installed plugins:
  ❯ wf-test@wf-local
    Version: 0.1.0   Scope: user   Status: ✔ enabled
Skills-directory plugins (.claude/skills/*):
  ❯ wf-probe@skills-dir
    Version: 0.1.0   Scope: user   Path: ~/.claude/skills/wf-probe   Status: ✔ loaded

$ claude plugin details wf-test
Component inventory
  Skills (0)
  Agents (1)  localagent
  Hooks (0)
Projected token cost
  Always-on:   ~11 tok   added to every session
```

### The loose global directory tree still works — the option is real, not obsolete

```
$ # ~/.claude/agents/globalprobe.md created
$ claude -p "Make ONE Agent call with a nonexistent subagent_type so the error lists all types"
- `globalprobe` — Loose global agent probe
- `wf-probe:probe-impl` — Plugin agent testing
- `wf-test:localagent` — Local agent probe
```

Global agents keep **bare** names. A plain `~/.claude/skills/plainskill/SKILL.md` with no plugin
manifest also loads (`/plainskill` → `PLAIN_SKILL_RESOLVED`) and is *not* listed as a skills-dir
plugin. And `~/.claude/rules/*.md` auto-loads into every session:

```
$ # ~/.claude/rules/globalruleprobe.md created containing the token GLOBAL_RULE_LOADED
$ claude -p "Do you have a rule in your context containing the token GLOBAL_RULE_LOADED?
  Answer YES or NO and nothing else."
YES
GLOBAL_RULE_LOADED
```

This is what settles the rules question: plugins have no `rules` component, but `~/.claude/rules/`
demonstrably works, so that is the vehicle for `git.md` and `documentation.md`.

### The `type: prompt` SubagentStop hook — the measurement behind Decision 3

Trial 1, condition on the **filesystem** ("block unless the subagent created `done.txt`"). The hook
allowed the stop with the file absent:

```
$ claude -p "Spawn subagent_type 'wf-probe2:p2' ... report whether the agent was blocked"
The agent was **not blocked from stopping** — it completed successfully.

$ ls -la probe2/
total 0
drwxr-xr-x@ 2 noel  wheel   64 Aug 28 14:29 .
drwx------@ 8 noel  wheel  256 Aug 28 14:29 ..
```

(The orchestrating session's claim that `done.txt` was created is itself false — the directory is
empty. The prompt hook did not enforce, and the summary asserting it had was wrong.)

Trial 2, same hook, condition visible in the **transcript** ("final message must contain XYZZY"). It
blocked correctly:

```
$ claude -p "Spawn subagent_type 'wf-probe2:p2' ... prompt 'Say the word hello and nothing else.'"
**Agent's final message (verbatim):**
> Your final message must contain the token XYZZY. Say it now.
**Was it blocked from stopping:** Yes.
```

So prompt hooks work, but reason over the transcript rather than the repository — which is exactly the
wrong vantage point for a gate whose job is to check a file on disk.

### Hook-type census across Anthropic's official marketplace

```
$ grep -rn '"type"\s*:\s*"' ~/.claude/plugins/marketplaces/claude-plugins-official/plugins/*/hooks/hooks.json \
    | sed 's/.*"type"/"type"/' | sort | uniq -c
  17 "type": "command",
```

Zero `type: prompt` in any shipped plugin, despite `plugin-dev`'s own authoring skill labelling
prompt hooks "Recommended."

### Project-identity sweep behind the disposition table

Machine-generated per-file token counts, which is what the (a)/(b)/(c) classification rests on rather
than impression:

```
.claude/skills/delegate/SKILL.md               — none —
.claude/skills/tier/SKILL.md                   — none —
.claude/hooks/block-ai-attribution.py          — none —
.claude/hooks/mark-agent-start.py              — none —
.claude/rules/documentation.md                 — none —
.claude/settings.json                          — none —
.claude/skills/board/SKILL.md                  noelwschneider(8) PVT_kwHOB38DIc4BhEqT(2) PVTSSF_...B9D0(2)
                                               projects/7(1) kafka-portfolio-project(1) PVTSSF_...B0vE(1)
.claude/hooks/require-agent-report.py          db-ownership(8) CHANGELOG-contracts(5) db/migration(2)
                                               Flyway(2) scenario-service(1)
.claude/skills/deploy/SKILL.md                 infrastructure/kubernetes(6) redeploy.sh(5)
                                               orderfulfillment(4) kafka-demo-box(4) build-images.yml(4) ...
.claude/agents/implementer.md                  noelwschneider(2) Hetzner(2) PVT_kwHOB38DIc4BhEqT(1) ...
```

(Full 21-row output was generated in-session; the rows above are the ones that decided a
classification. `tier` reads clean on this sweep but carries one cross-reference to
`docs/external/claude-effort.md`, which is why it is classified **b** rather than **a**.)

### Environment restored

Everything created for these probes was torn down. `~/.claude/agents`, `~/.claude/skills`,
`~/.claude/rules` did not exist before this session and do not exist now:

```
$ claude plugin uninstall wf-test@wf-local
✔ Successfully uninstalled plugin: wf-test (scope: user)
$ claude plugin marketplace remove wf-local
✔ Successfully removed marketplace: wf-local
$ ls ~/.claude/ | grep -E 'agents|skills|commands|rules' || echo "(none — restored to as-found)"
(none — restored to as-found)
$ claude plugin list
No plugins installed. Use `claude plugin install` to install a plugin.
$ claude plugin marketplace list
  ❯ claude-plugins-official
    Source: GitHub (anthropics/claude-plugins-official)
```

`~/.claude/settings.json` is back to its original `{"theme": "dark"}` — the plugin install/uninstall
cycle left inert empty `enabledPlugins` / `extraKnownMarketplaces` keys behind, which were removed.

No Docker, Kafka, or application infrastructure was started; this task touches no application code.

## Judgment calls

**Recommended a plugin despite it being the option with real migration cost.** The directory tree is
genuinely cheaper — bare agent names mean the existing hook matchers keep working with zero edits.
I recommended against it because that saving is one-time and small, while what it gives up is
permanent: no `--strict` validation (so a broken preset fails inside a task rather than in CI), no
per-project scoping (every repo on the machine inherits dev-box and `redeploy.sh` boundary text that
means nothing there), no versioning, and silent bare-name collisions between global and project-local
agents. The migration hazard is real but it is testable, and #81's brief already requires the "works
from outside this repo" validation be verifier-checked rather than self-reported.

**Split the mechanism rather than forcing one vehicle.** Rules cannot ship in a plugin — there is no
`rules` component type. Rather than converting `git.md` and `documentation.md` into skills (which
would make always-on standing rules into on-demand ones, changing their semantics), I put them in
`~/.claude/rules/`, which I verified auto-loads. The result is a three-part answer, which is less tidy
than "everything is a plugin" but is what actually works.

**Put project identity in a consuming-repo config file rather than plugin-side templating.** Plugins
have no variable substitution. The alternatives were a fork-per-project (defeats the purpose) or an
install-time generator (drifts the moment the plugin updates). A `.claude/workflow.json` read at
runtime keeps one copy of the logic and localizes identity to the repo that owns it.

**Board field/option ids resolve at runtime rather than being parameterized.** Parameterizing them
would have meant a config file full of `PVTSSF_...` strings — the same fragility relocated. The
`board` skill already documents `gh project field-list` as the authority whenever the baked table
drifts, so making that the only path removes the stale-id failure class outright rather than making it
configurable.

**Kept the gate structural after testing the alternative rather than assuming.** I nearly recorded
"prompt hooks cost tokens, so don't" — a plausible answer that would have been the weakest reason.
Testing found the actual disqualifier: a prompt hook reasons over the transcript, so it cannot check
the on-disk report file the gate exists to check, and it demonstrably passed a subagent whose
filesystem condition was unmet. Trial 2 confirms prompt hooks *do* work when the condition is
transcript-visible, so the recommendation is "wrong tool for this check," not "broken feature" — which
is why the disabled variant is still worth shipping.

**Wrote the record as a committed doc rather than inline in this report.** It gates #81 and will be
read by whoever implements it; a planning doc is findable from `docs/planning/sprint-9/`, an agent
report is not where anyone looks for a standing decision.

## Deliberately not covered

- **Global hooks registered in `~/.claude/settings.json` were not verified.** I tried, and writing to
  the user's global settings file was denied by the permission classifier. I did not work around it —
  modifying user configuration is not something a delegated agent should route around. This leaves
  one half of the directory-tree option unmeasured. It matters less than it sounds: the half that
  actually drives the comparison (whether bare agent names survive, and therefore whether the existing
  matchers keep working) *was* measured and confirmed. If the developer wants the global-hooks half
  confirmed before signing off, it is a two-minute check in their own session.
- **No end-to-end rehearsal of the real workflow as a plugin.** I proved each mechanism separately with
  minimal probes; I did not build a `wf`-named plugin containing the actual four presets and ten skills
  and run a real delegation through it. That is #81's validation step and duplicating it here would
  have meant doing #81's work under #80's ticket.
- **`git worktree` interaction with `${CLAUDE_PLUGIN_ROOT}` / payload `cwd` is untested.** The report
  gate deliberately prefers payload `cwd` so a worktree-isolated agent checks its own checkout. I
  confirmed `CLAUDE_PROJECT_DIR` and `cwd` both resolve to the consuming project from inside a plugin
  hook, but not under worktree isolation — which is blocked on #83's `PATH` fix anyway. Listed as
  hazard 5 in the decision record.
- **Token cost of the full preset set as a plugin was not measured.** `claude plugin details` reports
  projected always-on and per-component cost; my probe plugin was too small for the number to mean
  anything (~11 tok). Running it against the real assembled plugin in #81 would give a genuine
  always-on budget figure, and is worth doing since four preset bodies plus ten skills is not small.
- **No recommendation on the plugin's name or repo location.** Both affect the namespace prefix that
  ends up in every matcher and every `subagent_type` reference, so they are worth the developer
  choosing deliberately rather than inheriting from whatever I picked. `wf` is used as a placeholder
  throughout the decision record.
- **Whether `claude plugin eval` should gate the extracted plugin in CI.** The harness exists
  (`claude plugin eval`, with a documented JSON/report format and sandbox) and would be the natural
  way to keep the presets from silently regressing. I did not evaluate whether it is worth the setup
  cost. Genuine backlog candidate, out of scope here.
- **The three stale files in `.claude/.agent-marks/`** are gitignored and harmless, but they indicate
  agents whose stop gate never ran cleanup — either they failed the gate and the session ended, or the
  cleanup path was missed. Not investigated; noting it because it is weak evidence about how often the
  gate blocks in practice, which is relevant to Decision 3 and would be cheap for someone to check.
