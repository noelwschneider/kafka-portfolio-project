#!/usr/bin/env python3
"""SubagentStop gate: refuse to let a delegated agent finish without a real report.

Checks the contract in .claude/skills/agent-report/SKILL.md — the four required
headings, plus at least one fenced code block under the verification heading.

This is a discipline gate, not a correctness gate. It cannot tell whether the work
is right. It guarantees the agent said what it changed, showed real command output,
and named what it did not cover.

Exit 0 allows the stop. Exit 2 blocks it and sends stderr back to the agent.
Anything unexpected fails OPEN, so a broken gate never wedges a session.
"""
import json
import os
import re
import sys
import time
from pathlib import Path

REPORT_DIR = "docs/agent-reports"
REQUIRED = [
    "## What changed",
    "## How this was verified",
    "## Judgment calls",
    "## Deliberately not covered",
]
VERIFY_HEADING = "## How this was verified"
# Used only when no start mark exists (gate added mid-session).
FALLBACK_WINDOW_SECONDS = 24 * 60 * 60

MISSING_REPORT = """BLOCKED: no agent report found.

You have not filed a report under docs/agent-reports/. Your turn cannot end until
you do. Write it now, following the agent-report contract:

  docs/agent-reports/sprint-N/<short-slug>.md

Required headings, spelled exactly:
  ## What changed
  ## How this was verified
  ## Judgment calls
  ## Deliberately not covered

'## How this was verified' must contain the real commands you ran and their actual
output in a fenced code block. If you have not run anything against a real running
system yet, do that first - it is part of the task, not optional."""


def read_payload():
    try:
        return json.load(sys.stdin)
    except Exception:
        return {}


def start_time(project: Path, agent_id: str):
    if not agent_id:
        return None
    mark = project / ".claude" / ".agent-marks" / agent_id
    try:
        return mark.stat().st_mtime
    except OSError:
        return None


def newest_report(root: Path, since: float):
    newest, newest_mtime = None, since
    for path in root.rglob("*.md"):
        try:
            mtime = path.stat().st_mtime
        except OSError:
            continue
        if mtime >= newest_mtime:
            newest, newest_mtime = path, mtime
    return newest


def verification_has_evidence(text: str) -> bool:
    lines = text.splitlines()
    inside = False
    for line in lines:
        if line.strip() == VERIFY_HEADING:
            inside = True
            continue
        if inside and line.startswith("## "):
            break
        if inside and re.match(r"^\s*```", line):
            return True
    return False


def main() -> int:
    payload = read_payload()

    project_raw = os.environ.get("CLAUDE_PROJECT_DIR") or payload.get("cwd") or ""
    if not project_raw:
        return 0
    project = Path(project_raw)

    root = project / REPORT_DIR
    if not root.is_dir():
        return 0  # convention not in use in this checkout

    agent_id = str(payload.get("agent_id") or "").strip()
    since = start_time(project, agent_id)
    if since is None:
        since = time.time() - FALLBACK_WINDOW_SECONDS

    report = newest_report(root, since)
    if report is None:
        print(MISSING_REPORT, file=sys.stderr)
        return 2

    try:
        text = report.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return 0  # cannot read it; do not block on our own failure

    missing = [h for h in REQUIRED if not re.search(rf"^{re.escape(h)}\s*$", text, re.M)]
    has_evidence = verification_has_evidence(text)

    if not missing and has_evidence:
        cleanup(project, agent_id)
        return 0

    rel = report.relative_to(project) if report.is_relative_to(project) else report
    out = [f"BLOCKED: {rel} does not meet the agent-report contract.", ""]
    if missing:
        out.append("Missing required heading(s), which must appear verbatim:")
        out.extend(f"  {h}" for h in missing)
        out.append("")
    if not has_evidence:
        out += [
            f"No command output found under '{VERIFY_HEADING}'.",
            "That section needs the actual commands you ran and their real output in a",
            "fenced code block - test runs, curl responses, kubectl status, container logs.",
            "Prose describing what you expect to happen does not satisfy this.",
            "",
            "If you have not verified against a real running system yet, do that now.",
            "",
        ]
    out.append("Fix the report, then finish.")
    print("\n".join(out), file=sys.stderr)
    return 2


def cleanup(project: Path, agent_id: str) -> None:
    if not agent_id:
        return
    try:
        (project / ".claude" / ".agent-marks" / agent_id).unlink()
    except OSError:
        pass


if __name__ == "__main__":
    sys.exit(main())
