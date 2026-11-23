#!/usr/bin/env bash
# Checks run before every push: by Claude Code's PreToolUse hook on `git push`
# and, for people, by .githooks/pre-push (enable with `git config core.hooksPath .githooks`).
# The same checks run again in CI; this catches failures before a push wastes a CI run.
# Exit 2 blocks the push and sends the output back to Claude.
set -uo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

if [[ "${JOBASSISTANT_SKIP_PREPUSH:-}" == "1" ]]; then
  echo "pre-push checks skipped (JOBASSISTANT_SKIP_PREPUSH=1)" >&2
  exit 0
fi

failed=()
run() {
  local name="$1"; shift
  echo "==> $name" >&2
  if ! "$@" >&2; then failed+=("$name"); fi
}

run "Safety rules" python3 scripts/ci/check_forbidden_patterns.py
run "Migration tests exist" python3 scripts/ci/check_migration_tests.py
run "TASK.md consistency" python3 scripts/ci/check_task_md.py
run "Docs links" python3 scripts/ci/check_md_links.py

# Gradle only when something it builds changed since main (docs-only pushes stay fast).
base="$(git merge-base HEAD origin/main 2>/dev/null || echo HEAD)"
changed="$( { git diff --name-only "$base"...HEAD; git diff --name-only HEAD; } | sort -u)"
if grep -qE '\.(kt|kts|java|xml|toml|pro|properties)$|^gradle/|^build-logic/' <<<"$changed"; then
  gradle_args=(--quiet unitTest staticChecks)
  # On Windows, DataStore's FileStorage cannot rename its .tmp file over an existing file, so the
  # JVM unit tests that write DataStore files always fail there (never on Linux or Android). Skip
  # those two modules' unit tests on Windows only; CI on Linux still runs them on every PR.
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
      echo "==> Windows: skipping :core:data and :core:security unit tests (DataStore rename; CI runs them)" >&2
      gradle_args+=(-x :core:data:testDebugUnitTest -x :core:security:testDebugUnitTest)
      ;;
  esac
  run "Gradle unitTest and staticChecks" ./gradlew "${gradle_args[@]}"
else
  echo "==> Gradle skipped: no build or source files changed since main" >&2
fi

if ((${#failed[@]})); then
  printf '\nPush blocked. Failed: %s\nFix these and push again.\n' "$(IFS=,; echo "${failed[*]}")" >&2
  exit 2
fi
echo "All pre-push checks passed." >&2
