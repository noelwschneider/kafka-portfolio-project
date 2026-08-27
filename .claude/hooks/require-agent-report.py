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
CONTRACT_HEADING = "## Frozen contract impact"
# Flyway migrations that touch a table docs/db-ownership.md documents (its own unique/check
# constraints, column shapes, etc.) are a frozen-contract change per .claude/CLAUDE.md's
# coordination protocol. Sprint 5 shipped exactly this kind of change (scenario-service's events
# table dedupe key) with no db-ownership.md/CHANGELOG-contracts.md update and no report even
# mentioning it — the instruction existed in the implementer preset body but was silently missed.
# This is the structural backstop: touching a migration file forces an explicit answer, the same
# way the SubagentStop gate already forces real verification evidence instead of trusting prose.
MIGRATION_GLOB = "**/db/migration/*.sql"
CONTRACT_DOCS = ("docs/db-ownership.md", "docs/CHANGELOG-contracts.md")
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


def migration_touched(project: Path, since: float) -> bool:
    for path in project.glob(MIGRATION_GLOB):
        try:
            if path.stat().st_mtime >= since:
                return True
        except OSError:
            continue
    return False


def contract_doc_touched(project: Path, since: float) -> bool:
    for rel in CONTRACT_DOCS:
        try:
            if (project / rel).stat().st_mtime >= since:
                return True
        except OSError:
            continue
    return False


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

    # An agent working in its own git worktree (a different directory from the orchestrating
    # session's) must be checked against *its own* docs/agent-reports/, not the main checkout's -
    # otherwise a correctly-committed report on the agent's own branch never satisfies this gate,
    # and the agent ends up writing a redundant, untracked duplicate into the main checkout just to
    # pass. payload["cwd"] is the agent's actual working directory; CLAUDE_PROJECT_DIR is the
    # orchestrating session's fixed project dir and is used only when the payload has none.
    project_raw = payload.get("cwd") or os.environ.get("CLAUDE_PROJECT_DIR") or ""
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

    required = list(REQUIRED)
    needs_contract_note = migration_touched(project, since) and not contract_doc_touched(
        project, since
    )
    if needs_contract_note:
        required.append(CONTRACT_HEADING)

    missing = [h for h in required if not re.search(rf"^{re.escape(h)}\s*$", text, re.M)]
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
    if CONTRACT_HEADING in missing:
        out += [
            "You modified a Flyway migration under a db/migration/ directory without touching",
            "docs/db-ownership.md or docs/CHANGELOG-contracts.md. If that migration changes the",
            "shape of a table docs/db-ownership.md documents (a column, a unique/check constraint,",
            "etc.), that is a frozen-contract change - follow the coordination protocol in",
            ".claude/CLAUDE.md: propose the change in db-ownership.md, then log it in",
            "docs/CHANGELOG-contracts.md.",
            "",
            f"Add '{CONTRACT_HEADING}' to your report either way, stating one of:",
            "  - which table/constraint changed and that db-ownership.md and",
            "    CHANGELOG-contracts.md were updated to match, or",
            "  - why this migration does not touch anything db-ownership.md documents",
            "    (e.g. a brand-new table, or a change to an undocumented internal detail).",
            "",
        ]
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
