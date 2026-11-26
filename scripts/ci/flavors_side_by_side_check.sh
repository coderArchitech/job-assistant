#!/usr/bin/env bash
# Task T0.06 "Done when" check: the dev and prod flavors install side by side on one device.
#
# The dev flavor adds the `.dev` application ID suffix and its own launcher label ("Job Assistant Dev"),
# so both can be installed and told apart. This installs both debug builds, checks both packages are
# present at once, launches each, and checks each is still running, which shows neither replaced the other.
#
# Usage: scripts/ci/flavors_side_by_side_check.sh
#   Needs a connected emulator or device; with several attached, pick one with ANDROID_SERIAL.
#   It installs com.coderarchitech.jobassistant and com.coderarchitech.jobassistant.dev and leaves them installed.
set -euo pipefail

readonly PROD="com.coderarchitech.jobassistant"
readonly DEV="${PROD}.dev"

# Read the whole output before matching: grep -q under pipefail can kill adb with SIGPIPE (see #32).
installed() {
  local packages
  packages="$(adb shell pm list packages)"
  grep -qx "package:$1" <<<"${packages//$'\r'/}"
}

running() {
  local pid
  pid="$(adb shell pidof "$1" || true)"
  [[ -n "${pid//[[:space:]]/}" ]]
}

./gradlew --quiet installDevDebug installProdDebug

for pkg in "$PROD" "$DEV"; do
  if ! installed "$pkg"; then
    echo "FAIL: $pkg is not installed after installing both flavors" >&2
    exit 1
  fi
done
echo "OK: both $PROD and $DEV are installed"

for pkg in "$PROD" "$DEV"; do
  adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null
done

# Give both a moment to start, then check both are alive at the same time.
for _ in $(seq 1 20); do
  if running "$PROD" && running "$DEV"; then
    echo "OK: both flavors are running side by side"
    exit 0
  fi
  sleep 1
done
echo "FAIL: both flavors did not run at the same time" >&2
exit 1
