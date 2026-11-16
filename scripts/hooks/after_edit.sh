#!/usr/bin/env bash
# Claude Code PostToolUse hook for Edit and Write.
# Runs the safety-rule check on the file that was just changed. Exit 2 sends the
# findings back to Claude so it fixes them straight away; anything else passes.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
file="$(jq -r '.tool_input.file_path // .tool_response.filePath // empty')"

# Only files inside this repository, and only ones that still exist.
[[ -n "$file" && -f "$file" ]] || exit 0
case "$(realpath "$file")" in
  "$root"/*) ;;
  *) exit 0 ;;
esac

cd "$root"
if ! out="$(python3 scripts/ci/check_forbidden_patterns.py "$file" 2>&1)"; then
  echo "$out" >&2
  exit 2
fi
