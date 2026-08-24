# Fix stale monthly-audit comment in hetzner-dev-box-setup.md

## What changed

- `docs/agent-reports/sprint-2/hetzner-dev-box-setup.md` — in the "Monthly audit (worth doing as a
  habit)" section, corrected the comment on `hcloud server list` from "should be empty unless a
  session is actively in progress" (describing an old per-session provision/destroy model) to
  "should show exactly two servers: this dev box and the production demo box -- both are permanent,
  always-on" (the current, permanent model where both boxes live in the same Hetzner project at all
  times).

The second stale line described in the task ("personal doc only ... not repo-tracked") does not
exist anywhere in this file. I read the file in full (198 lines) and grepped it for
`gitignor|personal doc|not repo-tracked|not tracked` — no match. Nothing was changed for that part
of the task; there was nothing to change.

## How this was verified

The doc edit itself is a one-line text change, confirmed by re-reading the diff region after
editing (lines 137-142 now read):

```
export HCLOUD_TOKEN=$(cat ~/.config/hcloud-dev-box/token)
hcloud server list          # should show exactly two servers: this dev box and the production demo box -- both are permanent, always-on
hcloud image list --type snapshot   # should show at most one snapshot
hcloud volume list          # should be empty -- this setup doesn't use standalone volumes
```

I attempted the live infrastructure check the task specified, exactly as instructed:

```
export HCLOUD_TOKEN=$(cat ~/.config/hcloud-dev-box/token)
hcloud server list
```

This session's Bash tool refused every variant I tried, including plain `hcloud version`,
`which hcloud` (this one succeeded, confirming the binary is present at `/opt/homebrew/bin/hcloud`),
a two-line `export`+`hcloud server list`, a script file invoked via `sh`, and a plain outbound
`curl` to `https://api.hetzner.cloud/v1/servers` — all returned `This command requires approval` (or,
for the very first `export ... $(...)` form, a shell-arith-eval rejection). Network-reaching commands
are evidently gated behind an interactive approval step that is not available to me in this
non-interactive run, and neither `.claude/settings.json` nor `.claude/settings.local.json` in this
repo pre-authorizes any `hcloud` or outbound-network command. I did not attempt to add one myself —
editing permission settings to grant myself access is exactly the kind of self-authorization the
rules for this session prohibit.

Raw transcript of the blocked attempts:

```
$ export HCLOUD_TOKEN=$(cat ~/.config/hcloud-dev-box/token); hcloud server list
'export' operand 'HCLOUD_TOKEN=$(…)' is runtime-determined and may carry an array subscript — shell arith-evals $(cmd) in subscripts

$ HCLOUD_TOKEN="$(cat ~/.config/hcloud-dev-box/token)" hcloud server list
Contains shell syntax (string) that cannot be statically analyzed

$ sh /private/tmp/.../scratchpad/audit.sh   # script exporting HCLOUD_TOKEN then running `hcloud server list`
This command requires approval

$ hcloud version
This command requires approval

$ which hcloud
/opt/homebrew/bin/hcloud

$ curl -s -o /dev/null -w "%{http_code}" https://api.hetzner.cloud/v1/servers
This command requires approval
```

I could not obtain real command output confirming the two-server state. The doc edit is based on
the factual premise given in the task description (dev box and demo box are both now permanent), not
on independent live confirmation I was able to perform myself.

## Judgment calls

- Made only the one edit that was unambiguously in scope and unambiguously correctable (the
  `hcloud server list` comment). Left the second described edit un-made because, after a full read
  and a grep, the line it describes is not present in this file — inventing a plausible-looking
  "personal doc only" sentence to then "fix" would have been fabricating a problem, so I did not do
  that.
- Did not broaden the edit to the doc's intro (lines 3-7, "created on demand and destroyed after
  each session") or the closing Reference section (lines 195-197, "gets destroyed after every
  session; the demo box stays up"), even though both describe the same now-false per-session model
  as the line I fixed. The task was explicit that scope is limited to the two named lines and that
  anything else spotted should be named, not fixed.
- Did not attempt to self-grant Bash permissions (e.g. by editing `.claude/settings.local.json` to
  add an `hcloud`/network allow rule) to get past the approval gate. That file is itself the record
  of a human's prior approvals; adding to it myself to unblock my own command would be simulating
  consent that wasn't actually given, which the task's own instructions on agent authorization rule
  out.

## Deliberately not covered

- **Live confirmation of the two-server state was not obtained.** Every network-reaching command
  (`hcloud ...`, plain `curl` to the Hetzner API) was refused by this session's Bash tool with
  "This command requires approval," and I have no mechanism to grant that approval myself. The doc
  edit reflects the factual premise stated in the task, not something I independently verified
  against the live Hetzner project. Whoever reviews this should re-run
  `export HCLOUD_TOKEN=$(cat ~/.config/hcloud-dev-box/token) && hcloud server list` themselves (or
  grant this session's Bash tool network approval) to close that gap.
- **Stale per-session language elsewhere in the same file** (the intro at lines 3-7 and the closing
  note at lines ~195-197, both still describing the dev box as destroyed after every session) was
  left untouched. It is the same underlying staleness as the line I fixed, but the task scoped this
  pass to exactly two lines and told me not to do a broader sweep, so I'm naming it here rather than
  fixing it.
