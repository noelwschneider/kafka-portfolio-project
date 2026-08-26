#!/usr/bin/env python3
"""PreToolUse gate: subagents may not merge or deploy. Only the session the developer is
directly talking to may do that, and only after the developer has explicitly said go ahead.

Landed after Sprint 8's issue #46 incident response: a `platform` subagent diagnosed a live
production bug, wrote a good fix, and then pushed, opened a PR, and merged it to `main` itself,
all inside one delegation, before the developer had seen the diff. The fix held up, but that
was luck holding up a process gap, not the process working. This is the fix: not a preset
sentence hoping it's followed, but a mechanical block, the same way `implementer`'s tool
allowlist makes onward delegation impossible rather than merely discouraged.

Scope is deliberately narrow: a subagent can still branch, commit, push, and open a PR — that
work is real and reviewable, and stopping there is a good handoff. What it cannot do is take the
irreversible-feeling step (merge) or the step that touches a system other people can reach
(deploy). Those come back through the developer's own session.

Exit 0 allows the call. Exit 2 blocks it and sends stderr back to the agent. Anything unexpected
fails OPEN, so a broken gate never wedges a session.
"""
import json
import re
import sys

# (pattern, human label) - matched against the raw command string.
BLOCKED = [
    (re.compile(r"\bgh\s+pr\s+merge\b"), "merging a pull request (gh pr merge)"),
    (re.compile(r"\bgh\s+workflow\s+run\s+build-images\.yml\b"), "publishing images (build-images.yml)"),
    (re.compile(r"redeploy\.sh\b"), "running redeploy.sh"),
    (re.compile(r"\bkubectl\s+(?:[a-z-]+\s+)*(apply|create|delete|patch|replace|scale|edit)\b"),
     "a mutating kubectl command"),
    (re.compile(r"\bkubectl\s+rollout\b"), "kubectl rollout"),
]

MESSAGE = """BLOCKED: {label} is not something a subagent does.

Merges and deployments are handled only by the session the developer is directly talking to, and
only after the developer has explicitly confirmed - this is an enforced rule, not a preference to
weigh (see .claude/rules/git.md and docs/workflow/agent-workflow.md's "Land" section).

If your work is ready: commit it, push a branch, and open a PR if one doesn't already exist, then
stop and say so in your report. Do not merge it. Do not run a deploy. Report back and let the
orchestrating session take it from there."""


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0

    # Only subagents are gated. The main session (no agent_id) is the developer's own session and
    # is exempt - this hook exists to keep delegated work from finishing the job unsupervised, not
    # to slow down the person actually being asked to confirm.
    if not str(payload.get("agent_id") or "").strip():
        return 0

    if payload.get("tool_name") != "Bash":
        return 0

    command = payload.get("tool_input", {}).get("command")
    if not isinstance(command, str) or not command:
        return 0

    for pattern, label in BLOCKED:
        if pattern.search(command):
            print(MESSAGE.format(label=label), file=sys.stderr)
            return 2

    return 0


if __name__ == "__main__":
    sys.exit(main())
