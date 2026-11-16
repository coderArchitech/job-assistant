#!/usr/bin/env python3
"""Check TASK.md is internally consistent.

- Task IDs (T1.07) and decision IDs (D04) are unique.
- Every "Depends on" reference points to a task or decision that exists.
- Every ticked task links the PR that finished it.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

TASK_LINE = re.compile(r"^- \[( |x|X)\] \*\*(T\d+\.\d{2}) ")
DECISION_ROW = re.compile(r"^\| (D\d{2}) \|")
REF = re.compile(r"\b(T\d+\.\d{2}|D\d{2})\b")
PR_LINK = re.compile(r"(#\d+|github\.com/[^/\s]+/[^/\s]+/pull/\d+)")


def main(path: str = "TASK.md") -> int:
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    errors: list[str] = []

    tasks: dict[str, int] = {}
    decisions: dict[str, int] = {}
    blocks: list[tuple[str, bool, int, list[str]]] = []  # id, ticked, line, block lines

    current = None
    for i, line in enumerate(lines, 1):
        m = TASK_LINE.match(line)
        if m:
            tid, ticked = m.group(2), m.group(1).lower() == "x"
            if tid in tasks:
                errors.append(f"{path}:{i}: duplicate task ID {tid} (first on line {tasks[tid]})")
            tasks[tid] = i
            current = (tid, ticked, i, [line])
            blocks.append(current)
            continue
        d = DECISION_ROW.match(line)
        if d:
            did = d.group(1)
            if did in decisions:
                errors.append(f"{path}:{i}: duplicate decision ID {did} (first on line {decisions[did]})")
            decisions[did] = i
        if current and (line.startswith("  ") or line == ""):
            current[3].append(line)
        else:
            current = None

    known = set(tasks) | set(decisions)
    for tid, ticked, lineno, block in blocks:
        text = "\n".join(block)
        dep = re.search(r"Depends on ([^\n]+)", text)
        if dep:
            for ref in REF.findall(dep.group(1)):
                if ref not in known:
                    errors.append(f"{path}:{lineno}: {tid} depends on unknown {ref}")
        if ticked and not PR_LINK.search(text):
            errors.append(f"{path}:{lineno}: {tid} is ticked but links no PR")

    if not tasks:
        errors.append(f"{path}: no tasks found")

    if errors:
        print("\n".join(errors))
        return 1
    done = sum(1 for _, t, _, _ in blocks if t)
    print(f"TASK.md OK: {len(tasks)} tasks ({done} done), {len(decisions)} decisions.")
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
