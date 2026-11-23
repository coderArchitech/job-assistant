#!/usr/bin/env python3
"""Fail if a Room schema version has no migration test (CLAUDE.md, task T1.04).

Room exports `schemas/<db class>/<version>.json` on every build. This check makes sure that:

- the exported versions are 1..N with no gaps, and N equals `AgentDatabase.VERSION`;
- `MigrationTest` has an `@Test` named `createsVersion1`, and a `@Test migrateNToM` for every later version M.

So raising the version without a migration test (or without exporting the schema) fails CI.
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "core/data"
SCHEMAS = DATA / "schemas" / "com.coderarchitech.jobassistant.core.data.db.AgentDatabase"
DATABASE = next(DATA.glob("src/main/kotlin/**/AgentDatabase.kt"), None)
TESTS = list(DATA.glob("src/test/kotlin/**/MigrationTest.kt"))


def strip_comments(text: str) -> str:
    """Drop block and line comments, so a commented-out test does not count."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", "", text)


def has_test(name: str, text: str) -> bool:
    """True if `fun <name>()` is directly annotated with @Test."""
    return re.search(rf"@Test\s+fun {name}\s*\(", text) is not None


def main() -> int:
    errors: list[str] = []

    versions = sorted(int(p.stem) for p in SCHEMAS.glob("*.json") if p.stem.isdigit())
    if not versions:
        errors.append(f"{SCHEMAS}: no exported schema JSON found")
    else:
        expected = list(range(1, versions[-1] + 1))
        if versions != expected:
            errors.append(f"{SCHEMAS}: schema versions {versions} must be {expected[0]}..{expected[-1]} with no gaps")

    declared = None
    if DATABASE is None:
        errors.append("AgentDatabase.kt not found")
    else:
        m = re.search(r"const val VERSION\s*=\s*(\d+)", DATABASE.read_text(encoding="utf-8"))
        declared = int(m.group(1)) if m else None
        if declared is None:
            errors.append(f"{DATABASE}: cannot find `const val VERSION = <n>`")
    if versions and declared is not None and declared != versions[-1]:
        errors.append(
            f"AgentDatabase.VERSION is {declared} but the newest exported schema is {versions[-1]}.json "
            "(build the module so Room exports it, and commit the file)"
        )

    if len(TESTS) != 1:
        errors.append(f"expected exactly one MigrationTest.kt under {DATA}/src/test, found {len(TESTS)}")
    else:
        text = strip_comments(TESTS[0].read_text(encoding="utf-8"))
        if not has_test("createsVersion1", text):
            errors.append(f"{TESTS[0]}: missing test `createsVersion1`")
        for v in versions[1:]:
            name = f"migrate{v - 1}To{v}"
            if not has_test(name, text):
                errors.append(f"{TESTS[0]}: schema version {v} has no `@Test fun {name}()`")

    for e in errors:
        print(f"::error::{e}" if "GITHUB_ACTIONS" in os.environ else f"ERROR: {e}")
    if errors:
        return 1
    print(f"Migration tests OK: schema versions {versions}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
