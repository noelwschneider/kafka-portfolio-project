#!/usr/bin/env python3
"""SubagentStart: drop a timestamp mark so the stop gate knows when this agent began.

Always exits 0. A failure here must never block work; the stop gate falls back to a
time window when no mark exists.
"""
import json
import os
import sys
from pathlib import Path


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0

    agent_id = str(payload.get("agent_id") or "").strip()
    if not agent_id or "/" in agent_id or agent_id.startswith("."):
        return 0

    # Must match require-agent-report.py's resolution exactly, or the stop gate looks for this
    # mark in a different directory than the one it was written to. See that file's comment.
    project = payload.get("cwd") or os.environ.get("CLAUDE_PROJECT_DIR") or ""
    if not project:
        return 0

    try:
        marks = Path(project) / ".claude" / ".agent-marks"
        marks.mkdir(parents=True, exist_ok=True)
        (marks / agent_id).write_text("")
    except Exception:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
