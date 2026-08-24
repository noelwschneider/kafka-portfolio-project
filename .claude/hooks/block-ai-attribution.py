#!/usr/bin/env python3
"""PreToolUse gate: keep AI attribution out of commit messages and pull request bodies.

Blocks `git commit` and `gh pr create` / `gh pr edit` invocations carrying a
Co-Authored-By: Claude trailer or a "Generated with Claude Code" footer.

Covers attribution passed on the command line, which is how it is normally added.
A message supplied through an editor or a file this hook cannot see is not caught,
so this reduces the failure rate rather than eliminating it.

Exit 0 allows the call. Exit 2 blocks it and sends stderr back to Claude.
Anything unexpected fails OPEN.
"""
import json
import re
import sys

PATTERNS = [
    (re.compile(r"co-authored-by:\s*claude", re.I), "a Co-Authored-By: Claude trailer"),
    (re.compile(r"generated with \[?claude code", re.I), 'a "Generated with Claude Code" footer'),
    (re.compile(r"\U0001F916\s*Generated with", re.I), "a robot-emoji generation footer"),
]

TARGETS = [
    re.compile(r"\bgit\s+(?:-[^\s]+\s+)*commit\b"),
    re.compile(r"\bgh\s+pr\s+(?:create|edit)\b"),
]


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0

    if payload.get("tool_name") != "Bash":
        return 0

    command = payload.get("tool_input", {}).get("command")
    if not isinstance(command, str) or not command:
        return 0

    if not any(t.search(command) for t in TARGETS):
        return 0

    hits = [label for pattern, label in PATTERNS if pattern.search(command)]
    if not hits:
        return 0

    print(
        "BLOCKED: this command carries "
        + ", ".join(hits)
        + ".\n\n"
        "This project does not put AI attribution in commit messages or pull request\n"
        "bodies - see .claude/rules/git.md. Remove the trailer or footer and run the\n"
        "command again. The message itself is fine; only the attribution line is not.",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    sys.exit(main())
