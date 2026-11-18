#!/usr/bin/env bash
# Task T1.08 "Done when" check (decision D03): a bmgr backup and restore on a connected emulator
# or device brings back non-secret data and never the vault, WebView cookies or apply proofs, and
# the restored settings have auto-send emails switched off.
#
# Usage: scripts/ci/backup_restore_check.sh <applicationId>
#   Run `./gradlew installDevDebug` first, for example:
#   ./gradlew installDevDebug && scripts/ci/backup_restore_check.sh com.coderarchitech.jobassistant.dev
#   With several devices attached, pick one with ANDROID_SERIAL.
#
# It changes the device's backup settings (enables backup, selects the local transport) and clears
# the app's data. Every planted file holds obviously fake values. The settings.pb fixtures are
# generated and checked by BackupCheckFixturesTest in :core:data.
set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
  echo "Usage: $0 <applicationId>" >&2
  exit 2
fi
app_id="$1"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fixtures="$script_dir/fixtures"
settings_on="$fixtures/settings-autosend-on.pb"
settings_off="$fixtures/settings-autosend-off.pb"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Paths relative to the app's data directory (run-as starts there).
keep_file="files/keep.txt"
settings_file="files/datastore/settings.pb"
vault_file="files/datastore/vault.pb"
vault_tmp_file="files/datastore/vault.pb.tmp"
cookies_file="app_webview/Default/Cookies"
proof_file="files/proofs/proof1.png"

log() { printf '==> %s\n' "$*"; }
fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

# Runs a device shell command and strips carriage returns. stdin is closed so loops keep theirs.
dev() { adb shell "$@" </dev/null | tr -d '\r'; }

# Runs a command as the app (in its data directory). Returns the command's exit status.
as_app() { adb shell "run-as $app_id $*" </dev/null; }

app_has() { as_app "ls $1" >/dev/null 2>&1; }

# Copies a host file to a path in the app's data directory, byte for byte.
plant() {
  local src="$1" dest="$2"
  adb exec-in "run-as $app_id sh -c 'mkdir -p $(dirname "$dest") && cat > $dest'" <"$src"
  app_has "$dest" || fail "could not plant $dest"
}

list_app_files() {
  as_app "find files app_webview databases -type f" 2>/dev/null | tr -d '\r' | sed 's/^/    /' || true
}

for f in "$settings_on" "$settings_off"; do
  [[ -f "$f" ]] || fail "missing fixture $f (see BackupCheckFixturesTest in :core:data)"
done
command -v adb >/dev/null || fail "adb is not on PATH"

log "Waiting for the device"
adb wait-for-device
for _ in $(seq 1 120); do
  [[ "$(dev getprop sys.boot_completed)" == "1" ]] && break
  sleep 1
done
[[ "$(dev getprop sys.boot_completed)" == "1" ]] || fail "device did not finish booting"
api="$(dev getprop ro.build.version.sdk)"
log "API level $api"

dev pm path "$app_id" | grep -q '^package:' || fail "$app_id is not installed; run ./gradlew installDevDebug first"
as_app "true" || fail "run-as $app_id failed; install a debuggable build (devDebug)"

log "Enabling backup with the local transport"
for _ in $(seq 1 60); do
  dev bmgr enabled | grep -q 'currently' && break
  sleep 1
done
dev bmgr enable true
enabled="$(dev bmgr enabled)"
echo "$enabled"
grep -q 'currently enabled' <<<"$enabled" || fail "backup manager is not enabled"

transports="$(dev bmgr list transports)"
echo "$transports"
# The local transport is com.android.localtransport/.LocalTransport on current releases; older
# images name it android/com.android.internal.backup.LocalTransport. The selected one has a '*'.
transport="$(sed -n 's/^[[:space:]*]*\([^[:space:]]*LocalTransport\)[[:space:]]*$/\1/p' <<<"$transports" | head -n 1)"
[[ -n "$transport" ]] || fail "no local transport on this device"
dev bmgr transport "$transport"
for _ in $(seq 1 30); do
  dev bmgr list transports | grep -q "^[[:space:]]*\*[[:space:]]*$transport" && break
  sleep 1
done
dev bmgr list transports | grep -q "^[[:space:]]*\*[[:space:]]*$transport" || fail "could not select $transport"
# Drop data from earlier runs so the restore can only return what this run backed up.
dev bmgr wipe "$transport" "$app_id" || true

# The backup manager skips packages in the stopped state, which a fresh install is in until it is
# launched once. Launch it, send it to the background and kill it (force-stop would stop it again).
log "Launching $app_id once"
component="$(dev cmd package resolve-activity --brief "$app_id" | tail -n 1)"
[[ "$component" == */* ]] || fail "no launcher activity for $app_id (got: $component)"
dev am start -W -n "$component" >/dev/null
sleep 2
dev input keyevent KEYCODE_HOME
sleep 1
dev am kill "$app_id"
for _ in $(seq 1 10); do
  [[ -z "$(dev pidof "$app_id" || true)" ]] && break
  sleep 1
done

log "Planting fixtures (fake values only)"
printf 'control file: must survive backup and restore\n' >"$work/keep.txt"
printf 'FAKE-VAULT-BYTES-not-a-secret\n' >"$work/vault.pb"
printf 'FAKE-COOKIE-STORE-not-a-secret\n' >"$work/Cookies"
printf 'FAKE-PROOF-not-an-image\n' >"$work/proof1.png"
plant "$work/keep.txt" "$keep_file"
plant "$settings_on" "$settings_file"
plant "$work/vault.pb" "$vault_file"
plant "$work/vault.pb" "$vault_tmp_file"
plant "$work/Cookies" "$cookies_file"
plant "$work/proof1.png" "$proof_file"
echo "  Planted files:"
list_app_files

log "Backing up"
backup_out="$(dev bmgr backupnow "$app_id")"
echo "$backup_out"
grep -q "Package $app_id with result: Success" <<<"$backup_out" || fail "backup of $app_id did not succeed"

log "Clearing app data"
dev pm clear "$app_id" | grep -q 'Success' || fail "pm clear $app_id failed"
app_has "$keep_file" && fail "pm clear left $keep_file behind"

log "Restoring"
restore_out="$(dev bmgr restore "$app_id")"
echo "$restore_out"
if ! grep -q 'restoreFinished: 0' <<<"$restore_out"; then
  # Some releases restore only from a named set: use the newest token the transport lists.
  sets="$(dev bmgr list sets)"
  echo "$sets"
  token="$(sed -n 's/^[[:space:]]*\([0-9a-fA-F][0-9a-fA-F]*\)[[:space:]]*:.*/\1/p' <<<"$sets" | head -n 1)"
  [[ -n "$token" ]] || fail "restore of $app_id failed and no restore set is listed"
  restore_out="$(dev bmgr restore "$token" "$app_id")"
  echo "$restore_out"
  grep -q 'restoreFinished: 0' <<<"$restore_out" || fail "restore of $app_id from set $token failed"
fi

echo "  Files after restore:"
list_app_files

log "Checking the restored data"
failures=0
check() {
  local what="$1"
  shift
  if "$@"; then
    printf '  ok    %s\n' "$what"
  else
    printf '  FAIL  %s\n' "$what" >&2
    failures=$((failures + 1))
  fi
}
absent() { ! app_has "$1"; }

check "$keep_file restored (non-secret data is backed up)" app_has "$keep_file"
check "$settings_file restored (datastore/ is not excluded as a whole)" app_has "$settings_file"
check "$vault_file not restored" absent "$vault_file"
check "$vault_tmp_file not restored" absent "$vault_tmp_file"
check "$cookies_file not restored" absent "$cookies_file"
check "$proof_file not restored" absent "$proof_file"

# The agent's onRestoreFinished rewrites settings.pb before the restore reports completion; poll a
# little in case the device is slow to flush.
settings_matches() {
  local i
  for i in $(seq 1 10); do
    adb exec-out "run-as $app_id cat $settings_file" >"$work/restored-settings.pb" 2>/dev/null || true
    cmp -s "$work/restored-settings.pb" "$settings_off" && return 0
    [[ "$i" -lt 10 ]] && sleep 1
  done
  echo "    expected ($settings_off):"
  od -An -tx1 "$settings_off" | sed 's/^/     /'
  echo "    found:"
  od -An -tx1 "$work/restored-settings.pb" | sed 's/^/     /'
  return 1
}
check "settings.pb has auto-send emails off and every other setting unchanged" settings_matches

if [[ "$failures" -ne 0 ]]; then
  fail "$failures backup and restore check(s) failed on API $api"
fi
log "Backup and restore check passed on API $api"
