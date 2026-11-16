#!/usr/bin/env python3
"""Check relative links in tracked Markdown files.

Every relative link must point to a file that exists, and every `#anchor`
must match a heading in the target Markdown file (GitHub's slug rules).
External links (http, https, mailto) are not checked here.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
HEADING = re.compile(r"^#{1,6}\s+(.*?)\s*#*\s*$")
FENCE = re.compile(r"^(```|~~~)")


def slug(text: str) -> str:
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = text.strip().lower()
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


def anchors(path: Path) -> set[str]:
    seen: dict[str, int] = {}
    result = set()
    in_fence = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        m = HEADING.match(line)
        if m:
            base = slug(m.group(1))
            n = seen.get(base, 0)
            seen[base] = n + 1
            result.add(base if n == 0 else f"{base}-{n}")
    return result


def links(path: Path):
    in_fence = False
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        line = re.sub(r"`[^`]*`", "", line)
        for m in LINK.finditer(line):
            yield lineno, m.group(1)


def main() -> int:
    files = subprocess.run(["git", "ls-files", "*.md"], check=True, capture_output=True, text=True).stdout.split()
    cache: dict[Path, set[str]] = {}
    errors = []
    count = 0
    for name in files:
        src = Path(name)
        for lineno, target in links(src):
            if re.match(r"^[a-z][a-z0-9+.-]*:", target):
                continue
            count += 1
            file_part, _, anchor = target.partition("#")
            dest = (src.parent / file_part).resolve() if file_part else src.resolve()
            if not dest.exists():
                errors.append(f"{name}:{lineno}: broken link {target} (no such file)")
                continue
            if anchor and dest.suffix == ".md":
                if dest not in cache:
                    cache[dest] = anchors(dest)
                if anchor not in cache[dest]:
                    errors.append(f"{name}:{lineno}: broken anchor {target}")
    if errors:
        print("\n".join(errors))
        return 1
    print(f"Links OK: {count} relative links in {len(files)} Markdown files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
