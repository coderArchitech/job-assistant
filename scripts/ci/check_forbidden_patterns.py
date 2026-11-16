#!/usr/bin/env python3
"""Fail if code breaks one of the never-allowed rules in CLAUDE.md.

Scans tracked files (not docs) for patterns the app must never contain.
A line can opt out of one rule with a trailing comment `ci-allow: <rule-id>`
plus a reason, for example the LinkedIn host blocklist itself:

    val BLOCKED_HOSTS = setOf("linkedin.com") // ci-allow: linkedin-host (blocklist)
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

# (rule id, regex, path filter, message)
RULES: list[tuple[str, re.Pattern[str], re.Pattern[str] | None, str]] = [
    ("read-sms", re.compile(r"android\.permission\.(READ_SMS|RECEIVE_SMS)"), None,
     "SMS permissions are not allowed; use the SMS User Consent API (CLAUDE.md rule 10)."),
    ("accessibility", re.compile(r"BIND_ACCESSIBILITY_SERVICE|\bAccessibilityService\b"), None,
     "The Accessibility API is not allowed (CLAUDE.md rule 10)."),
    ("js-interface", re.compile(r"\baddJavascriptInterface\s*\("), None,
     "Use origin-restricted addWebMessageListener, never addJavascriptInterface (design: Portal automation)."),
    ("webview-file-access", re.compile(r"setAllow(File|Content|FileAccessFromFile|UniversalAccessFromFile)\w*\(\s*true"
                                       r"|allow(File|Content)Access\s*=\s*true"), None,
     "WebView file and content access must stay off (design: Portal automation)."),
    ("cleartext", re.compile(r"(cleartextTrafficPermitted|usesCleartextTraffic)\s*=\s*\"true\""), None,
     "Cleartext traffic is not allowed (design: Security and privacy)."),
    ("linkedin-host", re.compile(r"linkedin\.com", re.IGNORECASE), re.compile(r"(^|/)portals/"),
     "LinkedIn is never automated; LinkedIn hosts must not appear in the portal runtime (CLAUDE.md rule 7)."),
    ("force-unwrap", re.compile(r"[\w)\]]!!(?!=)"), re.compile(r"/src/main/.*\.kts?$"),
     "No `!!` in production code; use safe calls, requireNotNull with a message, or a sealed result (CLAUDE.md)."),
    ("http-body-logging", re.compile(r"HttpLoggingInterceptor\.Level\.BODY"), re.compile(r"^(?!.*/src/debug/)"),
     "Request body logging is only allowed in debug source sets (CLAUDE.md rule 2)."),
]

SKIP_SUFFIXES = {".md", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".pdf", ".jar", ".zip"}
SKIP_PREFIXES = ("scripts/ci/", ".github/", ".claude/")
ALLOW = re.compile(r"ci-allow:\s*([\w-]+)")


def tracked_files() -> list[str]:
    out = subprocess.run(["git", "ls-files"], check=True, capture_output=True, text=True).stdout
    return [f for f in out.splitlines() if f]


def main(argv: list[str]) -> int:
    # With paths (the Claude Code edit hook), check only those files; otherwise every tracked file.
    paths = [str(Path(a).resolve().relative_to(Path.cwd().resolve())) for a in argv] if argv else tracked_files()
    failures = []
    for path in paths:
        if path.startswith(SKIP_PREFIXES) or Path(path).suffix.lower() in SKIP_SUFFIXES:
            continue
        try:
            text = Path(path).read_text(encoding="utf-8")
        except (UnicodeDecodeError, FileNotFoundError):
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            allowed = set(ALLOW.findall(line))
            for rule_id, pattern, path_filter, message in RULES:
                if path_filter is not None and not path_filter.search(path):
                    continue
                if rule_id in allowed:
                    continue
                if pattern.search(line):
                    failures.append(f"{path}:{lineno}: [{rule_id}] {message}")
    if failures:
        print("Forbidden patterns found:\n")
        print("\n".join(failures))
        print("\nIf a match is intentional, add `// ci-allow: <rule-id> (reason)` to that line.")
        return 1
    print(f"No forbidden patterns ({len(RULES)} rules checked).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
