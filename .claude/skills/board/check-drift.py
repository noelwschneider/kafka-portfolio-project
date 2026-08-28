#!/usr/bin/env python3
"""Board-vs-reality drift check.

Not a hook — there is no tool-call event that means "the board might be stale."
Board drift comes from an orchestrating session (or a human) forgetting to advance
a Status field, not from a single mechanical action a hook could intercept. This is
a standalone check instead: run it by hand, or as part of /sprint-close's board
reconciliation step.

What it catches: a board item's Status implying something about its linked GitHub
Issue's open/closed state that contradicts the issue's actual state right now.

  - Status in {Merged, Deployed} but the issue is still OPEN — shipped-sounding
    status on unshipped work, or the issue was never closed when it should have been.
  - Status in {Backlog, Shelved, Planned, In Progress, Ready to Merge} but the issue
    is already CLOSED — the work finished (or was closed for some other reason) and
    the board was never advanced to match.

What it cannot catch: whether "Merged" should actually be "Deployed" (that needs
knowing what commit is live in production, which this script has no access to —
that check stays manual, e.g. via /deploy's own verification stage), or drift on
draft items with no linked Issue at all (nothing to check them against).

Usage: .claude/skills/board/check-drift.py
Requires: gh CLI authenticated, run from anywhere (uses absolute repo/owner below).
Exit code: 0 if clean, 1 if any drift found (so it can gate a script if wanted;
/sprint-close treats a non-zero exit as "investigate before reconciling," not as
a hard block).
"""
import json
import subprocess
import sys

OWNER = "noelwschneider"
REPO = "kafka-portfolio-project"
PROJECT_NUMBER = "7"

SHIPPED_STATUSES = {"Merged", "Deployed"}
UNSHIPPED_STATUSES = {"Backlog", "Shelved", "Planned", "In Progress", "Ready to Merge"}


def run_json(cmd: list[str]):
    result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def main() -> int:
    items = run_json(
        [
            "gh", "project", "item-list", PROJECT_NUMBER, "--owner", OWNER,
            "--limit", "200", "--format", "json",
        ]
    )["items"]

    issues = run_json(
        [
            "gh", "issue", "list", "--repo", f"{OWNER}/{REPO}",
            "--state", "all", "--limit", "500", "--json", "number,state",
        ]
    )
    issue_state = {i["number"]: i["state"] for i in issues}

    mismatches = []
    for item in items:
        content = item.get("content") or {}
        if content.get("type") != "Issue":
            continue  # draft items and PR-typed items have nothing to check against
        number = content.get("number")
        status = item.get("status")
        state = issue_state.get(number)
        if state is None or status is None:
            continue

        if status in SHIPPED_STATUSES and state == "OPEN":
            mismatches.append(
                f"#{number} \"{item.get('title')}\": board says {status}, "
                f"but the issue is still OPEN — {content.get('url')}"
            )
        elif status in UNSHIPPED_STATUSES and state == "CLOSED":
            mismatches.append(
                f"#{number} \"{item.get('title')}\": board says {status}, "
                f"but the issue is already CLOSED — {content.get('url')}"
            )

    if not mismatches:
        print(f"Checked {len(items)} board items — no drift found.")
        return 0

    print(f"Checked {len(items)} board items — {len(mismatches)} mismatch(es):\n")
    for m in mismatches:
        print(f"  - {m}")
    print(
        "\nEach of these needs a look: either the board Status is stale and should "
        "move, or the issue's open/closed state doesn't actually mean what the "
        "Status implies for this item (say why, don't just silence the finding)."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
