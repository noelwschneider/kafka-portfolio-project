# Issue #42: design a guided `/deploy` command

## What changed

- `.claude/skills/deploy/SKILL.md` (new) — a `/deploy` skill defining a four-stage guided flow (build
  & push, pre-redeploy checkpoint, redeploy, verify) over the existing, disconnected
  `gh workflow run build-images.yml` and `infrastructure/kubernetes/production/redeploy.sh`. Each
  mutating stage states the exact plan (which images, which tag, which services restart in what
  order) before an explicit confirmation gate, and a `--dry-run` mode prints the same plan and exact
  commands without executing anything that touches GHCR or the cluster. Stages 3-4 explicitly defer to
  `.claude/skills/redeploy/SKILL.md`'s existing "Deploy" and "Verify" sections rather than duplicating
  them, so the two skills cannot drift apart on what "redeploy" or "verify" means.
- No other files changed. `build-images.yml` and `redeploy.sh` were read but not modified — nothing
  found in either required a change to make this design coherent (see Judgment calls, tag pinning, for
  the one adjacent gap that was *not* trivial enough to fix inline).

## How this was verified

This is a design task; the deliverable is the skill file, not a code change, and the task explicitly
rules out a live end-to-end run against production. What could reasonably be verified without touching
the box: that the artifact is well-formed, that its command lines are syntactically valid, and that
every path and flag it references actually exists as described.

Frontmatter parses as valid YAML with the fields Claude Code skills expect:

```
$ python3 -c "
import yaml, re
text = open('.claude/skills/deploy/SKILL.md').read()
m = re.match(r'^---\n(.*?)\n---\n', text, re.S)
fm = yaml.safe_load(m.group(1))
for k, v in fm.items(): print(f'  {k}: {v}')
"
  name: deploy
  description: Guided production deploy - build and push the six images, then redeploy and verify the demo box, with explicit checkpoints before every irreversible step. Use for a full build-to-live deploy; use /redeploy instead if no new image is involved (e.g. a Secret rotation).
  argument-hint: [tag; defaults to latest] [--dry-run]
  disable-model-invocation: True
  allowed-tools: Read, Bash(gh workflow *), Bash(gh run *), Bash(kubectl *), Bash(curl *), Bash(git rev-parse *), Bash(infrastructure/kubernetes/production/redeploy.sh*)
```

Every referenced file actually exists:

```
$ for f in infrastructure/kubernetes/production/redeploy.sh .claude/skills/redeploy/SKILL.md \
    infrastructure/kubernetes/production/ghcr/kustomization.yaml \
    docs/adr/ADR-010-k3s-on-a-dedicated-hetzner-vps-for-the-public-demo.md \
    docs/adr/ADR-011-sequential-production-rollouts-to-avoid-memory-exhaustion.md \
    .github/workflows/build-images.yml; do [ -f "$f" ] && echo "OK: $f" || echo "MISSING: $f"; done
OK: infrastructure/kubernetes/production/redeploy.sh
OK: .claude/skills/redeploy/SKILL.md
OK: infrastructure/kubernetes/production/ghcr/kustomization.yaml
OK: docs/adr/ADR-010-k3s-on-a-dedicated-hetzner-vps-for-the-public-demo.md
OK: docs/adr/ADR-011-sequential-production-rollouts-to-avoid-memory-exhaustion.md
OK: .github/workflows/build-images.yml
```

All five embedded `bash` code blocks are syntactically valid (placeholders substituted with dummy
values so `bash -n` has something to parse):

```
$ sed -e 's/<resolved-tag>/latest/' -e 's/<databaseId>/12345/' extracted_blocks.sh > check_sub.sh
$ bash -n check_sub.sh && echo "syntax OK"
syntax OK
```

The `gh` invocations match the installed CLI's real flags, not a remembered/assumed syntax:

```
$ gh --version
gh version 2.49.0 (2024-04-30)
$ gh run watch --help | head -8
Watch a run until it completes, showing its progress.
...
FLAGS
      --exit-status    Exit with non-zero status if run fails
  -i, --interval int   Refresh interval in seconds (default 3)
$ gh workflow run --help | head -8
Create a `workflow_dispatch` event for a given workflow.
...
```

`--exit-status` on `gh run watch` and `-f key=value` on `gh workflow run` are both real, current flags
on the CLI actually installed in this environment.

I did not run `/deploy` itself, dry-run or real, against the live cluster or GHCR — see Deliberately
not covered.

## Judgment calls

- **One skill, not two separate commands, and build auto-chains into the redeploy checkpoint within
  one session.** The task asked for "build -> push -> redeploy -> verify as one guided flow." I
  interpreted that as one `/deploy` invocation walking through all four stages, gated by a
  confirmation at each mutating step, rather than requiring the operator to type a second slash
  command between build and redeploy. The alternative — splitting `/deploy` into `/deploy build` and a
  separate, later `/deploy rollout` that must be invoked as a new command — is more conservative (it
  forces a real break, possibly a new session, between "images are public" and "production changes")
  but is also closer to what already exists today (two disconnected steps) and duplicates work the
  existing `/redeploy` skill already does. I kept one command with per-stage checkpoints, but this is
  exactly the "should one merged image push ever trigger the redeploy step automatically" fork named
  in the task — see Open decisions below, since I'm not certain this is the shape the developer wants.
- **Stages 3-4 defer to the existing `/redeploy` skill by reference (`Read` it and follow its
  sections) rather than re-stating its commands.** `.claude/skills/redeploy/SKILL.md` already encodes
  the pre-flight capacity check, the exact `redeploy.sh` invocation, and what "verify" means today
  (curl + pod status + optionally a real order). Copying that prose into `/deploy` would create two
  copies of the same procedure that could silently drift — the project's own `agent-workflow.md`
  documents exactly this failure mode for practices that live in more than one place. Referencing it
  means a future edit to the redeploy procedure (e.g. tightening the verify bar) only has to happen
  once.
- **Verification depth is a checkpoint question, not a fixed policy, for the "submit a real order"
  step.** The task names "exactly what verify should check" as a plausible fork. I resolved the
  *minimum* bar (pods Ready + one curl, matching the existing `/redeploy` skill) as fixed, but left
  whether to also submit a real order through the live system as something the flow asks the operator
  at stage 4 rather than something the skill decides unconditionally — submitting a real order writes
  real, visible state into the shared public demo (ADR-010's "shared public sandbox" consequence), so
  whether that cost is worth paying on a given deploy seemed like a per-run call, not a standing
  policy. I did not make the deeper check (comparing running image digests against what was just
  pushed) part of the flow at all, because the overlay currently pins `:latest` rather than an
  immutable tag, so a digest comparison needs a registry API call this design doesn't specify or
  test — flagged below rather than built speculatively.
- **No automatic rollback on a mid-rollout failure.** `redeploy.sh` already stops the sequential loop
  and prints diagnostics on the first stuck rollout; I designed stage 3 to surface exactly that and
  then stop, handing control back to a human, rather than having the guided flow attempt
  `kubectl rollout undo` on the failing Deployment itself. The task's framing throughout ("guided,"
  "not full unattended automation," "middle ground... don't design toward removing the human
  checkpoints") argues against adding new automation at exactly the moment something has already gone
  wrong — that is the worst moment to remove a human from the loop, not the best one to automate. This
  is also one of the task's named example forks; see Open decisions.
- **Did not modify `ghcr/kustomization.yaml` to pin an immutable per-commit tag**, even though reading
  it surfaced a real, load-bearing fact for this design: all six images currently use mutable
  `newTag: latest`, and the file's own comment already flags pinning the immutable `github.sha` tag as
  a known-but-undone improvement ("Pin an immutable tag... and this can go back to IfNotPresent").
  This is a real code/config change beyond the skill design itself, so per the task's scope boundary I
  named it rather than fixing it inline — see Contract/config gap below.
- **Report filename**: `deploy-command-design-42.md` rather than extending an existing sprint-7
  report, since no other sprint-7 report yet covers issue #42 and the task said to use a new file
  unless told otherwise.

## Open decisions (for the developer, not decided here)

These are the genuine forks the task asked to be surfaced rather than resolved unilaterally:

1. **Should a successful build stage auto-continue (with a checkpoint) into the redeploy stage within
   one `/deploy` invocation, or should build and redeploy always require two separate, explicitly
   re-invoked commands?** I designed the former (one command, per-stage checkpoints — see Judgment
   calls above). The latter is more conservative and closer to today's already-disconnected process,
   at the cost of re-deriving state (which tag was just pushed, whether the build fully succeeded)
   across a session boundary.
2. **On a mid-rollout failure in stage 3, should `/deploy` ever attempt an automatic remediation
   (e.g. `kubectl rollout undo` on the stuck Deployment), or should it always stop and hand every
   subsequent action to the human, exactly as designed?** I defaulted to "always stop," reasoning that
   the task explicitly wants the human kept in the loop and that automating exactly the failure path
   is the highest-risk place to do it. An automatic rollback would need its own design and verification
   pass regardless (rollout-undo semantics under `maxSurge: 0` / `maxUnavailable: 1` are untested here).
3. **How deep should stage 4's verification go by default?** I made the floor "pods Ready + one curl"
   (matching `/redeploy` today) and made "submit a real order and watch it reach a terminal state" an
   in-flow question to the operator rather than a default, because it writes real state into the
   shared public demo. A stronger check that was deliberately *not* built — comparing the running
   image digest per Deployment against the digest GHCR just published — would give real confidence
   that new code is actually live (since the tag itself, `:latest`, does not change and cannot be
   diffed) but needs a registry API call this design doesn't specify; worth deciding whether that is
   worth building given item 4 below.
4. **Whether to pin an immutable per-commit-SHA tag in `ghcr/kustomization.yaml` instead of the
   current mutable `:latest`.** Not a `/deploy`-skill decision at all — it is a real change to
   `ghcr/kustomization.yaml` (and probably back to `IfNotPresent` per that file's own comment) that
   would change what "redeploy" mechanically does (edit-and-apply the pinned tag, not just
   `rollout restart`) and would make stage-4 digest verification straightforward instead of needing a
   registry call. Left entirely to the developer since it's a production config change outside this
   task's scope, not a design-artifact choice.

## Contract/config gap found

Not a `docs/openapi/`, `docs/events/`, `docs/order-state-machine.md`, or `docs/db-ownership.md` gap
(those weren't touched by this task), but a real gap adjacent to this design worth naming explicitly:
`infrastructure/kubernetes/production/ghcr/kustomization.yaml` already documents, in its own comment,
that it should eventually pin the immutable commit-SHA tag `build-images.yml` publishes and drop back
to `imagePullPolicy: IfNotPresent`, but still uses `newTag: latest` on all six images today. This
matters for `/deploy`'s design because it means the redeploy step can never be verified by tag alone
(`:latest` never visibly changes) — see open decision 4 above. Not fixed here per the task's explicit
scope boundary ("don't modify... unless trivially required"); this isn't trivial, since it changes the
mechanics of how a redeploy actually picks up a new image.

## Deliberately not covered

- **No live exercise of `/deploy`, dry-run or real, against the live cluster or GHCR.** The task
  explicitly said not to attempt a live end-to-end run as part of this design task. Verification here
  is limited to the artifact's own well-formedness (valid frontmatter, real paths, syntactically valid
  command lines, real `gh` flags) — it does not prove the guided flow actually reads well or behaves
  correctly when a human runs it turn-by-turn in a real Claude Code session. The first real invocation
  should be treated as the first test of this design, not a formality.
- **No registry-digest comparison was designed or tested**, for the reason in open decision 3 — it
  would need a concrete GHCR API call (auth, endpoint, response shape) that this task did not attempt
  to work out, since it depends on open decision 4 (whether to pin an immutable tag at all) landing
  first.
- **Did not touch `build-images.yml` or `redeploy.sh`.** Both were read in full and neither needed a
  change to make this design coherent, per the task's scope boundary.
- **Did not address issue #38 (dev-box vs. local-stack policy for delegated agents)**, a separate,
  related-but-not-blocking sprint-7 goal per the sprint-7 plan's sequencing — out of scope for this
  task.
- **No board update performed.** Per this project's workflow, moving #42 to Done is a call for the
  orchestrating session/developer once the design is reviewed, not something to do unilaterally from
  inside the delegated task itself.
