#!/usr/bin/env bash
# Task T1.08 "Done when" check (decision D03): a bmgr backup and restore on a connected emulator
# or device brings back non-secret data (settings.pb included) and never the vault, WebView
# cookies, apply proofs or the auto-send emails consent, which lives only in no_backup/ so a
# restored install starts with auto-send off.
#
# What it exercises: on API 29-30 the backup uses res/xml/backup_rules.xml (fullBackupContent).
# On API 31+ `bmgr backupnow` with the local transport is a cloud backup, so it applies the
# <cloud-backup> section of res/xml/data_extraction_rules.xml; the <device-transfer> section is
# not exercised on a device. BackupRulesTest in :app keeps the two sections identical.
#
# <cloud-backup> sets disableIfNoEncryptionCapabilities="true", and the local transport reports no
# client-side encryption by default, so on API 31+ the backup would be skipped. The script sets the
# local transport's test parameters (secure setting backup_local_transport_parameters:
# fake_encryption_flag and is_encrypted) so it reports encryption, and puts the earlier value back
# on exit. API 29-30 ignore them.
#
# Usage: scripts/ci/backup_restore_check.sh <applicationId>
#   Run `./gradlew installDevDebug` first, for example:
#   ./gradlew installDevDebug && scripts/ci/backup_restore_check.sh com.coderarchitech.jobassistant.dev
#   With several devices attached, pick one with ANDROID_SERIAL.
#
# It changes the device's backup settings (enables backup, selects the local transport) and clears
# the app's data. Every planted file holds obviously fake values.
set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
  echo "Usage: $0 <applicationId>" >&2
  exit 2
fi
app_id="$1"

work="$(mktemp -d)"
transport_params_key="backup_local_transport_parameters"
transport_params_saved=""
cleanup() {
  if [[ -n "$transport_params_saved" ]]; then
    if [[ "$transport_params_saved" == "null" ]]; then
      adb shell settings delete secure "$transport_params_key" </dev/null >/dev/null 2>&1 || true
    else
      adb shell settings put secure "$transport_params_key" "'$transport_params_saved'" </dev/null >/dev/null 2>&1 || true
    fi
  fi
  rm -rf "$work"
}
trap cleanup EXIT

# Paths relative to the app's data directory (run-as starts there).
keep_file="files/keep.txt"
settings_file="files/datastore/settings.pb"
vault_file="files/datastore/vault.pb"
vault_tmp_file="files/datastore/vault.pb.tmp"
cookies_file="app_webview/Default/Cookies"
proof_file="files/proofs/proof1.png"
consent_file="no_backup/auto_send_emails_consent"

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
# The bytes travel base64-encoded inside one `adb shell` command and are decoded on the device with
# toybox base64 (Android 10+). Streaming stdin through `adb exec-in` into run-as failed on the CI
# emulators (API 29 and 36), and its errors were lost; this way adb's output is shown on failure.
plant() {
  local src="$1" dest="$2" dir encoded out
  dir="$(dirname "$dest")"
  encoded="$(base64 <"$src" | tr -d '\n')"
  out="$(adb shell "run-as $app_id sh -c 'mkdir -p $dir && echo $encoded | base64 -d > $dest'" </dev/null 2>&1)" || true
  app_has "$dest" || fail "could not plant $dest: ${out:-no output from adb}"
  [[ "$(as_app "sh -c 'wc -c < $dest'" | tr -d '\r ')" == "$(wc -c <"$src" | tr -d ' ')" ]] ||
    fail "planted $dest has the wrong size"
}

list_app_files() {
  as_app "find files app_webview databases no_backup -type f" 2>/dev/null | tr -d '\r' | sed 's/^/    /' || true
}

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

# Make the local transport report client-side encryption (see the header).
transport_params_saved="$(dev settings get secure "$transport_params_key")"
[[ -n "$transport_params_saved" ]] || transport_params_saved="null"
dev settings put secure "$transport_params_key" "fake_encryption_flag=true,is_encrypted=true"
[[ "$(dev settings get secure "$transport_params_key")" == *fake_encryption_flag=true* ]] ||
  fail "could not set $transport_params_key"

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
# Drop data from earlier runs, before this run's backup, so the transport holds exactly one
# restore set for the app: the one this run writes.
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
app_alive() { [[ -n "$(dev pidof "$app_id" || true)" ]]; }
for _ in $(seq 1 10); do
  app_alive || break
  sleep 1
done
# A live process could rewrite files after they are planted, so the check would not test the rules.
app_alive && fail "$app_id is still running after am kill"

log "Planting fixtures (fake values only)"
printf 'control file: must survive backup and restore\n' >"$work/keep.txt"
printf 'FAKE-VAULT-BYTES-not-a-secret\n' >"$work/vault.pb"
printf 'FAKE-COOKIE-STORE-not-a-secret\n' >"$work/Cookies"
printf 'FAKE-PROOF-not-an-image\n' >"$work/proof1.png"
# Not a valid settings message: the check is only that the file is backed up.
printf 'FAKE-SETTINGS-BYTES-not-a-secret\n' >"$work/settings.pb"
# The marker is empty in the app; any content proves the same.
printf 'FAKE-CONSENT-MARKER\n' >"$work/consent"
plant "$work/keep.txt" "$keep_file"
plant "$work/settings.pb" "$settings_file"
plant "$work/vault.pb" "$vault_file"
plant "$work/vault.pb" "$vault_tmp_file"
plant "$work/Cookies" "$cookies_file"
plant "$work/proof1.png" "$proof_file"
plant "$work/consent" "$consent_file"
echo "  Planted files:"
list_app_files

log "Backing up"
backup_out="$(dev bmgr backupnow "$app_id")"
echo "$backup_out"
grep -q "Package $app_id with result: Success" <<<"$backup_out" || fail "backup of $app_id did not succeed (on API 31+, check that the transport reports encryption)"

log "Clearing app data"
dev pm clear "$app_id" | grep -q 'Success' || fail "pm clear $app_id failed"
app_has "$keep_file" && fail "pm clear left $keep_file behind"

log "Restoring"
restore_out="$(dev bmgr restore "$app_id")"
echo "$restore_out"
if ! grep -q 'restoreFinished: 0' <<<"$restore_out"; then
  # Some releases restore only from a named set. The wipe before the backup leaves exactly one
  # set; with more, picking one could restore data from another run, so stop instead.
  sets="$(dev bmgr list sets)"
  echo "$sets"
  tokens="$(sed -n 's/^[[:space:]]*\([0-9a-fA-F][0-9a-fA-F]*\)[[:space:]]*:.*/\1/p' <<<"$sets")"
  [[ -n "$tokens" ]] || fail "restore of $app_id failed and no restore set is listed"
  [[ "$(wc -l <<<"$tokens")" -eq 1 ]] || fail "restore of $app_id failed and more than one restore set is listed"
  token="$tokens"
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
check "$consent_file not restored (auto-send emails starts off)" absent "$consent_file"

if [[ "$failures" -ne 0 ]]; then
  fail "$failures backup and restore check(s) failed on API $api"
fi
log "Backup and restore check passed on API $api"
