#!/usr/bin/env bash
# Task T0.06 "Done when" check: the dev and prod flavors install side by side on one device.
#
# The dev flavor adds the `.dev` application ID suffix and its own launcher label ("Job Assistant Dev"),
# so both can be installed and told apart. This installs both debug builds, checks both packages are
# present at once, starts each and waits for its first screen, then checks both are still alive a few
# seconds later, so a flavor that crashes on start fails the check.
#
# Usage: scripts/ci/flavors_side_by_side_check.sh
#   Needs a connected emulator or device; with several attached, pick one with ANDROID_SERIAL.
#   It installs com.coderarchitech.jobassistant and com.coderarchitech.jobassistant.dev and leaves them installed.
set -euo pipefail

readonly PROD="com.coderarchitech.jobassistant"
readonly DEV="${PROD}.dev"
readonly SETTLE_SECONDS=5

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# adb's stdin is closed so it never reads the caller's; output is read whole before matching, because
# grep -q under pipefail can kill adb with SIGPIPE (#32).
on_device() { adb shell "$@" </dev/null | tr -d '\r'; }

installed() { grep -qx "package:$1" <<<"$(on_device pm list packages)"; }

alive() { [[ -n "$(on_device pidof "$1" || true)" ]]; }

start() {
  local activity output
  activity="$(on_device cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$1" | tail -n 1)"
  [[ "$activity" == */* ]] || fail "no launcher activity for $1 (got: $activity)"
  output="$(on_device am start -W -n "$activity")" || fail "could not start $activity: $output"
  grep -q "Status: ok" <<<"$output" || fail "$activity did not start: $output"
}

./gradlew --quiet installDevDebug installProdDebug

for pkg in "$PROD" "$DEV"; do
  installed "$pkg" || fail "$pkg is not installed after installing both flavors"
done
echo "OK: both $PROD and $DEV are installed"

start "$PROD"
start "$DEV"
sleep "$SETTLE_SECONDS"
for pkg in "$PROD" "$DEV"; do
  alive "$pkg" || fail "$pkg stopped after starting; it may have crashed (see logcat)"
done
echo "OK: both flavors started and are running side by side"
