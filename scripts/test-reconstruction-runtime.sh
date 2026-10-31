#!/usr/bin/env bash
set -euo pipefail
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--bundled-google" ) ]]; then
  echo 'Usage: test-reconstruction-runtime.sh [--bundled-google]' >&2
  exit 2
fi
MYLITE_ADB_BINARY=$(type -P adb)
readonly MYLITE_ADB_BINARY
MYLITE_RUNTIME_PHASE=bootstrap
# Bounded one-shot commands only; the controlled live stream below uses the real binary directly.
adb() {
  local status=0 phase="$MYLITE_RUNTIME_PHASE" operation=unknown budget=30s
  case "${3:-}" in install|push|pull) budget=120s;; esac
  timeout --kill-after=5s "$budget" "$MYLITE_ADB_BINARY" "$@" || status=$?
  if [[ $status == 124 || $status == 137 ]]; then
    [[ "$phase" =~ ^[A-Za-z0-9_-]{1,100}$ ]] || phase=unknown
    case "${3:-}" in logcat|shell|exec-out|install|push|get-state) operation="$3";; esac
    mkdir -p work/runtime
    printf '{"schema":1,"phase":"%s","operation":"%s","exitStatus":%s,"completeBatch":false}\n' "$phase" "$operation" "$status" > work/runtime/adb-timeout.json
    # Read-only health probes cannot turn a timed-out cohort into a pass or touch a physical device.
    if [[ "${target:-}" =~ ^emulator-[0-9]+$ ]]; then
      for probe in transport uptime logd boot_count log_buffers; do
        local probe_status=0
        case "$probe" in
          transport) timeout --kill-after=1s 5s "$MYLITE_ADB_BINARY" -s "$target" get-state > work/runtime/adb-probe-transport.txt 2>&1 || probe_status=$?;;
          uptime) timeout --kill-after=1s 5s "$MYLITE_ADB_BINARY" -s "$target" shell cat /proc/uptime > work/runtime/adb-probe-uptime.txt 2>&1 || probe_status=$?;;
          logd) timeout --kill-after=1s 5s "$MYLITE_ADB_BINARY" -s "$target" shell pidof logd > work/runtime/adb-probe-logd.txt 2>&1 || probe_status=$?;;
          boot_count) timeout --kill-after=1s 5s "$MYLITE_ADB_BINARY" -s "$target" shell settings get global boot_count > work/runtime/adb-probe-boot-count.txt 2>&1 || probe_status=$?;;
          log_buffers) timeout --kill-after=1s 5s "$MYLITE_ADB_BINARY" -s "$target" logcat -g > work/runtime/adb-probe-log-buffers.txt 2>&1 || probe_status=$?;;
        esac
        printf '{"schema":1,"probe":"%s","exitStatus":%s}\n' "$probe" "$probe_status" >> work/runtime/adb-probes.jsonl
      done
    fi
    echo 'One-shot ADB timed out; stopping the incomplete cloud cohort with retained evidence.' >&2
    exit "$status"
  fi
  return "$status"
}
mapfile -t targets < <(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1}')
if [[ ${#targets[@]} -ne 1 ]]; then
  echo 'Exactly one emulator is required; physical devices are never selected.' >&2
  exit 1
fi
target="${targets[0]}"
adb -s "$target" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$target" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p work/runtime
host_uid=$(adb -s "$target" shell cmd package list packages -U | awk '$1 == "package:io.github.xmy953538104.mybox" {sub(/^uid:/,"",$2); print $2}')
if [[ ! "$host_uid" =~ ^[0-9]+$ ]]; then echo 'Cannot identify the installed host UID.' >&2; exit 1; fi
# Older approved service builds log raw response settings under this tag. Never retain those values.
private_log_filters=('GmsServicesProvider:S' '*:V')
collect_runtime() {
  local label="$1"
  MYLITE_RUNTIME_PHASE="collect-${label:-core}"
  adb -s "$target" logcat -d --uid="$host_uid" -v threadtime "${private_log_filters[@]}" > "work/runtime/engine-log${label}.txt" || true
  adb -s "$target" logcat -d -v threadtime 'ActivityTaskManager:I' 'ActivityManager:I' 'CachedAppOptimizer:D' 'Zygote:I' 'lmkd:V' 'lowmemorykiller:V' 'libc:F' 'DEBUG:F' '*:S' > "work/runtime/system-log${label}.txt" || true
  if ! adb -s "$target" logcat -b crash -d --uid="$host_uid" -v threadtime "${private_log_filters[@]}" > "work/runtime/crash-log${label}.txt"; then
    echo "Cannot verify owned-process crashes for ${label}" >&2
    core_status=1
  fi
  if grep -Eq 'FATAL EXCEPTION:|Fatal signal [0-9]+|Abort message:' "work/runtime/crash-log${label}.txt"; then
    echo "Unexpected owned-process crash recorded in crash-log${label}.txt" >&2
    core_status=1
  fi
  adb -s "$target" shell dumpsys activity exit-info io.github.xmy953538104.mybox > "work/runtime/process-exits${label}.txt" || true
  if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f files/installer-diagnostic-export.zip >/dev/null 2>&1; then
    adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat files/installer-diagnostic-export.zip > "work/runtime/installer-diagnostics${label}.zip"
  fi
  adb -s "$target" shell dumpsys activity activities > "work/runtime/activities${label}.txt" || true
  for role in executor server; do
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/job-recovery-failure-${role}.txt" >/dev/null 2>&1; then
      adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat "files/job-recovery-failure-${role}.txt" > "work/runtime/job-recovery-failure-${role}${label}.txt" 2>/dev/null || true
    fi
  done
  if [[ -z "$label" || "$label" == -round2 || "$label" == -round3 ]]; then
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f files/media-router-runtime-result.json >/dev/null 2>&1; then
      adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat files/media-router-runtime-result.json > "work/runtime/media-router${label}.json"
    fi
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f files/framework-diagnostic-export.zip >/dev/null 2>&1; then
      adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat files/framework-diagnostic-export.zip > "work/runtime/framework-diagnostics${label}.zip"
    fi
    for policy in allowed restricted; do
      if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/network-policy-${policy}.json" >/dev/null 2>&1; then
        adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat "files/network-policy-${policy}.json" > "work/runtime/network-policy-${policy}${label}.json"
      fi
    done
  fi
  if [[ "${2:-true}" == "true" ]]; then
    adb -s "$target" exec-out screencap -p > "work/runtime/screen${label}.png" || true
  fi
  if [[ "$label" =~ ^-alarm-round[123]$ ]]; then
    for diagnostic in periodic-alarm-before-death.txt periodic-alarm-after-death.txt periodic-alarm-final.txt periodic-alarm-witness.json; do
      if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/$diagnostic" >/dev/null 2>&1; then
        adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat "files/$diagnostic" > "work/runtime/${diagnostic%.*}${label}.${diagnostic##*.}"
      fi
    done
  fi
  MYLITE_RUNTIME_PHASE=between-groups
}
run_play_journey() {
  adb -s "$target" shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
  adb -s "$target" shell getprop qemu.hw.mainkeys > work/runtime/play-emulator-mainkeys.txt
  adb -s "$target" shell settings get secure navigation_mode > work/runtime/play-navigation-mode-before.txt
  adb -s "$target" shell dumpsys window displays > work/runtime/play-displays-before.txt
  adb -s "$target" shell dumpsys input > work/runtime/play-input-before.txt
  adb -s "$target" shell dumpsys activity service com.android.systemui/.SystemUIService > work/runtime/play-systemui-before.txt
  adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  set +e
  timeout 240s adb -s "$target" shell am instrument -w -r \
    -e class io.github.xmy953538104.googlecheck.GooglePlayJourneyTest \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee work/runtime/instrumentation-play-journey.txt
  journey_status=${PIPESTATUS[0]}
  set -e
  adb -s "$target" shell settings get secure navigation_mode > work/runtime/play-navigation-mode-after.txt
  adb -s "$target" shell dumpsys window displays > work/runtime/play-displays-after.txt
  adb -s "$target" shell dumpsys input > work/runtime/play-input-after.txt
  adb -s "$target" shell dumpsys activity service com.android.systemui/.SystemUIService > work/runtime/play-systemui-after.txt
  if ! python3 scripts/capture-play-analysis.py --serial "$target"; then
    echo '::warning::Encrypted Play analysis capture failed; the business result remains unchanged. No plaintext APK is an artifact.' >&2
  fi
  collect_runtime '-play-journey'
  local disk_failure=false identity_failure=false gcm_identity_accepted=false
  if grep -Fq 'Failed to collect the device disk space info.' work/runtime/engine-log-play-journey.txt; then
    disk_failure=true; core_status=1
    echo 'Actual Play device storage collection still failed.' >&2
  fi
  if grep -Fq 'Caller PID is unavailable' work/runtime/engine-log-play-journey.txt; then
    identity_failure=true; core_status=1
    echo 'Actual Google startup still dropped a caller identity; interaction alone is insufficient.' >&2
  fi
  if grep -Eq 'GmsGcmRegister:.*handleMessage: package=com\.android\.vending what=1 ' work/runtime/engine-log-play-journey.txt; then
    gcm_identity_accepted=true
  else
    core_status=1
    echo 'No actual Play GCM request reached the checked package/UID path.' >&2
  fi
  printf '{"diskSpaceErrorObserved":%s,"callerPidUnavailableObserved":%s,"playGcmIdentityAcceptedObserved":%s,"scope":"Startup identity only; not account authentication, registration token, delivery or download acceptance."}\n' "$disk_failure" "$identity_failure" "$gcm_identity_accepted" > work/runtime/play-runtime-blockers.json
  for phase in paste-menu failure; do
    for service in input window; do
      diagnostic="play-journey-${phase}-${service}.txt"
      if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/$diagnostic" >/dev/null 2>&1; then
        adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat "files/$diagnostic" > "work/runtime/$diagnostic"
      fi
    done
  done
  for phase in google-editor-keyboard google-editor-paste-menu google-editor-pasted store-returned failure; do
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/play-journey-${phase}.png" >/dev/null 2>&1; then
      adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat "files/play-journey-${phase}.png" > "work/runtime/play-journey-${phase}.png"
    fi
  done
  if [[ $journey_status -ne 0 ]] || ! grep -q 'OK (1 test)' work/runtime/instrumentation-play-journey.txt \
    || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' work/runtime/instrumentation-play-journey.txt; then core_status=1; fi
}
if [[ "${MYLITE_PLAY_JOURNEY_ONLY:-false}" == "true" ]]; then
  if [[ "${1:-}" != "--bundled-google" ]]; then echo 'Play journey requires the pinned bundle.' >&2; exit 2; fi
  core_status=0
  printf '%s\n' '{"scope":"play-journey-only","fullAndroidRegression":false}' > work/runtime/probe-scope.json
  run_play_journey
  exit "$core_status"
fi
test_source=app/src/reconstructionTest/java
classes=()
while IFS= read -r source; do
  test_class="${source#"$test_source/"}"
  test_class="${test_class%.java}"
  classes+=("${test_class//\//.}")
done < <(find "$test_source/io/github/xmy953538104/mybox" -name '*Test.java' ! -name 'AccountUiTicketRuntimeTest.java' ! -name 'ActivityDeferredRuntimeTest.java' ! -name 'BroadcastPermissionRuntimeTest.java' ! -name 'AlarmRuntimeTest.java' ! -name 'ClipboardRuntimeTest.java' ! -name 'WebInputRuntimeTest.java' ! -name 'ProcessCapacityRuntimeTest.java' ! -name 'GoogleMessagingRuntimeTest.java' -print | LC_ALL=C sort)
if [[ ${#classes[@]} -eq 0 ]]; then echo 'No core instrumentation test classes found.' >&2; exit 1; fi
class_list=$(IFS=,; printf '%s' "${classes[*]}")
# Explicit classes avoid the legacy runner recursively loading every APK class during discovery.
"$MYLITE_ADB_BINARY" -s "$target" logcat --uid="$host_uid" -v threadtime "${private_log_filters[@]}" > work/runtime/live-log.txt 2>&1 &
log_pid=$!
trap 'kill "$log_pid" 2>/dev/null || true' EXIT
core_status=0
shortcut_artifacts=(shortcut-runtime-result.json)
for phase in launcher popup focused failure; do
  shortcut_artifacts+=("shortcut-ui-${phase}.png" "shortcut-ui-${phase}.txt" "shortcut-ui-${phase}-input.txt" "shortcut-ui-${phase}-window.txt")
done
clear_shortcut_artifacts() {
  for diagnostic in "${shortcut_artifacts[@]}"; do
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/$diagnostic" >/dev/null 2>&1; then
      adb -s "$target" shell run-as io.github.xmy953538104.mybox rm "files/$diagnostic"
    fi
  done
}
collect_shortcut_artifacts() {
  local round="$1"
  for diagnostic in "${shortcut_artifacts[@]}"; do
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/$diagnostic" >/dev/null 2>&1; then
      adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat "files/$diagnostic" > "work/runtime/${diagnostic%.*}-round${round}.${diagnostic##*.}"
    fi
  done
}
if [[ "${MYLITE_SETTINGS_SHORTCUTS_ONLY:-false}" == "true" ]]; then
  printf '%s\n' '{"scope":"settings-shortcuts-only","fullAndroidRegression":false,"realGoogleBusiness":false,"expectedTestsPerRound":3}' > work/runtime/probe-scope.json
  for round in 1 2 3; do
    adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
    clear_shortcut_artifacts
    set +e
    timeout 180s adb -s "$target" shell am instrument -w -r \
      -e class 'io.github.xmy953538104.mybox.ReconstructionRuntimeTest#testGuestFilesSqliteAndNativePath,io.github.xmy953538104.mybox.engine.ContentObserverRuntimeTest#testBoundSettingsDeathRetiresGuestBeforeAnUncaughtMainThreadError,io.github.xmy953538104.mybox.engine.AlarmRuntimeTest#testShortcutRealOsNamespaceAndColdActivityCapability' \
      io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee "work/runtime/instrumentation-focused-round${round}.txt"
    instrument_status=${PIPESTATUS[0]}
    set -e
    collect_runtime "-focused-round${round}"
    collect_shortcut_artifacts "$round"
    if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK (3 tests)' "work/runtime/instrumentation-focused-round${round}.txt" \
        || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "work/runtime/instrumentation-focused-round${round}.txt"; then core_status=1; fi
  done
  exit "$core_status"
fi
notification_artifacts=(notification-ui-current.png notification-ui-current.txt notification-ui-failure.png notification-ui-failure.txt notification-ui-before-swipe.png notification-ui-before-swipe.txt notification-before-swipe-input.txt notification-before-swipe-window.txt)
for test_case in testPhysicalNotificationContentAndActionOpenGuest testSystemUiBroadcastCancelOneShotAndSwipeDelete testSystemUiBroadcastColdServerRecoveryAndSamePhysicalSenderRepost testSystemUiRevokedAndOldInstallBroadcastsCannotDeliver; do
  notification_artifacts+=("notification-ui-touch-${test_case}.png" "notification-ui-touch-${test_case}.txt" "notification-touch-${test_case}-input.txt" "notification-touch-${test_case}-window.txt")
  notification_artifacts+=("notification-ui-before-swipe-${test_case}.png" "notification-ui-before-swipe-${test_case}.txt" "notification-before-swipe-${test_case}-input.txt" "notification-before-swipe-${test_case}-window.txt")
  notification_artifacts+=("notification-ui-failure-${test_case}.png" "notification-ui-failure-${test_case}.txt" "notification-failure-${test_case}-input.txt" "notification-failure-${test_case}-window.txt" "notification-state-failure-${test_case}.json")
done
notification_artifacts+=(foreground-failure-notifications.txt foreground-failure-services.txt)
# Retain every round: a later success must never erase a startup/recovery failure.
for round in 1 2 3; do
  label="-round$round"
  if [[ $round == 1 ]]; then label=''; fi
  adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  # Export only windows captured in this round; an earlier screenshot cannot prove a failed round.
  if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f files/media-router-runtime-result.json >/dev/null 2>&1; then
    adb -s "$target" shell run-as io.github.xmy953538104.mybox rm files/media-router-runtime-result.json
  fi
  if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f files/framework-diagnostic-export.zip >/dev/null 2>&1; then
    adb -s "$target" shell run-as io.github.xmy953538104.mybox rm files/framework-diagnostic-export.zip
  fi
  for policy in allowed restricted; do
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/network-policy-${policy}.json" >/dev/null 2>&1; then
      adb -s "$target" shell run-as io.github.xmy953538104.mybox rm "files/network-policy-${policy}.json"
    fi
  done
  for phase in popup dialog toast toast-hidden toast-timeout; do
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/window-${phase}.png" >/dev/null 2>&1; then
      adb -s "$target" shell run-as io.github.xmy953538104.mybox rm "files/window-${phase}.png"
    fi
  done
  for diagnostic in "${notification_artifacts[@]}"; do
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/$diagnostic" >/dev/null 2>&1; then
      adb -s "$target" shell run-as io.github.xmy953538104.mybox rm "files/$diagnostic"
    fi
  done
  set +e
  # Keep individual test deadlines and require actual completion of the complete selected aggregate.
  timeout 360s adb -s "$target" shell am instrument -w -r \
    -e class "$class_list" \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee "work/runtime/instrumentation${label}.txt"
  instrument_status=${PIPESTATUS[0]}
  set -e
  printf '{"exitStatus":%s,"outerBudgetSeconds":360}\n' "$instrument_status" > "work/runtime/core-process-round${round}.json"
  collect_runtime "$label"
  if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f files/storage-runtime-result.json >/dev/null 2>&1; then
    adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat files/storage-runtime-result.json > "work/runtime/storage-result-round${round}.json"
  fi
  for phase in popup dialog toast toast-hidden toast-timeout; do
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/window-${phase}.png" >/dev/null 2>&1; then
      adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat "files/window-${phase}.png" > "work/runtime/window-${phase}-round${round}.png"
    fi
  done
  for diagnostic in "${notification_artifacts[@]}"; do
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/$diagnostic" >/dev/null 2>&1; then
      adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat "files/$diagnostic" > "work/runtime/${diagnostic%.*}-round${round}.${diagnostic##*.}"
    fi
  done
  if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK ([0-9].*test' "work/runtime/instrumentation${label}.txt" \
      || ! grep -q '^INSTRUMENTATION_CODE: -1' "work/runtime/instrumentation${label}.txt" \
      || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "work/runtime/instrumentation${label}.txt"; then core_status=1; fi
  # Independent consumer/authenticator processes need their own process-slot group.
  adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  set +e
  timeout 240s adb -s "$target" shell am instrument -w -r \
    -e class io.github.xmy953538104.mybox.engine.AccountUiTicketRuntimeTest \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee "work/runtime/instrumentation-account-ui-round${round}.txt"
  instrument_status=${PIPESTATUS[0]}
  set -e
  collect_runtime "-account-ui-round${round}"
  if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK ([0-9].*test' "work/runtime/instrumentation-account-ui-round${round}.txt" \
      || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "work/runtime/instrumentation-account-ui-round${round}.txt"; then core_status=1; fi
  # Permission fixtures use sender plus three independently installed receiver packages.
  adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  set +e
  timeout 240s adb -s "$target" shell am instrument -w -r \
    -e class io.github.xmy953538104.mybox.BroadcastPermissionRuntimeTest \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee "work/runtime/instrumentation-broadcast-permission-round${round}.txt"
  instrument_status=${PIPESTATUS[0]}
  set -e
  collect_runtime "-broadcast-permission-round${round}"
  if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK ([0-9].*test' "work/runtime/instrumentation-broadcast-permission-round${round}.txt" \
      || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "work/runtime/instrumentation-broadcast-permission-round${round}.txt"; then core_status=1; fi
  # Clipboard focus and its synthetic OS record need an independent guest process group.
  adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  set +e
  timeout 240s adb -s "$target" shell am instrument -w -r \
    -e class io.github.xmy953538104.mybox.engine.ClipboardRuntimeTest,io.github.xmy953538104.mybox.engine.WebInputRuntimeTest \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee "work/runtime/instrumentation-clipboard-round${round}.txt"
  instrument_status=${PIPESTATUS[0]}
  set -e
  collect_runtime "-clipboard-round${round}"
  if grep -Eq 'AutofillManager:.*SyncResultReceiver.*(Timeout|5000ms)' "work/runtime/engine-log-clipboard-round${round}.txt"; then
    echo 'Real WebView input blocked waiting for an autofill result.' >&2
    core_status=1
  fi
  for phase in keyboard paste-menu pasted; do
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/web-input-${phase}.png" >/dev/null 2>&1; then
      adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat "files/web-input-${phase}.png" > "work/runtime/web-input-${phase}-round${round}.png"
    fi
  done
  for service in input window; do
    diagnostic="web-input-paste-menu-${service}.txt"
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/$diagnostic" >/dev/null 2>&1; then
      adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat "files/$diagnostic" > "work/runtime/web-input-paste-menu-${service}-round${round}.txt"
    fi
  done
  if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK ([0-9].*test' "work/runtime/instrumentation-clipboard-round${round}.txt" \
      || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "work/runtime/instrumentation-clipboard-round${round}.txt"; then core_status=1; fi
  # Saturate the real process pool without borrowing slots from other fixture groups.
  adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  set +e
  timeout 240s adb -s "$target" shell am instrument -w -r \
    -e class io.github.xmy953538104.mybox.engine.ProcessCapacityRuntimeTest \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee "work/runtime/instrumentation-capacity-round${round}.txt"
  instrument_status=${PIPESTATUS[0]}
  set -e
  collect_runtime "-capacity-round${round}"
  if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK ([0-9].*test' "work/runtime/instrumentation-capacity-round${round}.txt" \
      || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "work/runtime/instrumentation-capacity-round${round}.txt"; then core_status=1; fi
  # OS capability recovery releases its lease before killing the server. The original repeating
  # alarm needs a real 60-second interval; shortcut activation uses its unchanged persisted Intent.
  adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  clear_shortcut_artifacts
  for diagnostic in periodic-alarm-before-death.txt periodic-alarm-after-death.txt periodic-alarm-final.txt periodic-alarm-witness.json; do
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/$diagnostic" >/dev/null 2>&1; then
      adb -s "$target" shell run-as io.github.xmy953538104.mybox rm "files/$diagnostic"
    fi
  done
  set +e
  timeout 360s adb -s "$target" shell am instrument -w -r \
    -e class io.github.xmy953538104.mybox.engine.AlarmRuntimeTest \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee "work/runtime/instrumentation-alarm-round${round}.txt"
  instrument_status=${PIPESTATUS[0]}
  set -e
  collect_runtime "-alarm-round${round}"
  collect_shortcut_artifacts "$round"
  adb -s "$target" shell dumpsys alarm > "work/runtime/alarms-round${round}.txt" || true
  if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK ([0-9].*test' "work/runtime/instrumentation-alarm-round${round}.txt" \
      || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "work/runtime/instrumentation-alarm-round${round}.txt"; then core_status=1; fi
done
# One real 65-second foreground deferral covers both former lifecycle deadlines without multiplying core time.
adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
set +e
timeout 150s adb -s "$target" shell am instrument -w -r \
  -e class io.github.xmy953538104.mybox.engine.ActivityDeferredRuntimeTest \
  io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee work/runtime/instrumentation-activity-deferred.txt
instrument_status=${PIPESTATUS[0]}
set -e
printf '{"exitStatus":%s,"outerBudgetSeconds":150}\n' "$instrument_status" > work/runtime/activity-deferred-process.json
collect_runtime '-activity-deferred'
if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK (2 tests)' work/runtime/instrumentation-activity-deferred.txt \
    || ! grep -q '^INSTRUMENTATION_CODE: -1' work/runtime/instrumentation-activity-deferred.txt \
    || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' work/runtime/instrumentation-activity-deferred.txt; then core_status=1; fi
adb -s "$target" exec-out screencap -p > work/runtime/guest-screen.png
if ! timeout 180s python3 scripts/test-persisted-job-reboot.py "$target" > work/runtime/persisted-job-reboot.log 2>&1; then
  core_status=1
fi
collect_runtime '-persisted-reboot' false
if [[ "${1:-}" == "--bundled-google" ]]; then
  adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  if [[ "${MYLITE_CLEAN_DEVICE_SETUP:-false}" == "true" ]]; then
    echo '6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257  work/reference/gbox.apk' | sha256sum -c -
    adb -s "$target" shell getprop ro.build.fingerprint > work/runtime/setup-image-fingerprint.txt
    adb -s "$target" shell dumpsys webviewupdate > work/runtime/setup-webview.txt
    adb -s "$target" shell cmd package list packages > work/runtime/setup-system-packages.txt
    adb -s "$target" push work/reference/gbox.apk /sdcard/Download/Mybox-Play-source.apk
    adb -s "$target" push engine-fixture/build/outputs/apk/debug/engine-fixture-debug.apk /sdcard/Download/Mybox-invalid-source.apk
    set +e
    timeout 300s adb -s "$target" shell am instrument -w -r \
      -e class io.github.xmy953538104.googlecheck.GoogleCleanSetupRuntimeTest \
      io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee work/runtime/instrumentation-clean-setup.txt
    instrument_status=${PIPESTATUS[0]}
    set -e
    collect_runtime '-clean-setup'
    for phase in source-required picker-opened storage-root folder-opened rejected installed store-opened failure; do
      for extension in png txt; do
        diagnostic="google-setup-${phase}.${extension}"
        if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/$diagnostic" >/dev/null 2>&1; then
          adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat "files/$diagnostic" > "work/runtime/$diagnostic"
        fi
      done
    done
    if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK (1 test)' work/runtime/instrumentation-clean-setup.txt \
        || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' work/runtime/instrumentation-clean-setup.txt; then core_status=1; fi
    adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  fi
  set +e
  timeout 240s adb -s "$target" shell am instrument -w -r \
    -e class io.github.xmy953538104.googlecheck.GoogleRuntimeTest,io.github.xmy953538104.googlecheck.GoogleBoundIdentityRuntimeTest \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee work/runtime/instrumentation-google.txt
  instrument_status=${PIPESTATUS[0]}
  set -e
  collect_runtime '-google'
  adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat files/ci-microg-settings.png > work/runtime/microg-settings-running.png || true
  if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK ([0-9].*test' work/runtime/instrumentation-google.txt \
      || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' work/runtime/instrumentation-google.txt; then core_status=1; fi
  adb -s "$target" exec-out screencap -p > work/runtime/microg-screen.png
  if [[ "${MYLITE_GOOGLE_REGISTRATION_PROBE:-false}" == "true" ]]; then
    set +e
    timeout 240s adb -s "$target" shell am instrument -w -r \
      -e class io.github.xmy953538104.googlecheck.GoogleRegistrationRuntimeTest \
      io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee work/runtime/instrumentation-google-registration.txt
    instrument_status=${PIPESTATUS[0]}
    set -e
    collect_runtime '-google-registration' false
    if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK ([0-9].*test' work/runtime/instrumentation-google-registration.txt \
        || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' work/runtime/instrumentation-google-registration.txt; then core_status=1; fi
  fi
  if [[ "${MYLITE_PLAY_JOURNEY_PROBE:-false}" == "true" ]]; then run_play_journey; fi
  if [[ "${MYLITE_GOOGLE_MESSAGING_PROBE:-false}" == "true" ]]; then
    set +e
    timeout 780s adb -s "$target" shell am instrument -w -r \
      -e class io.github.xmy953538104.mybox.engine.GoogleMessagingRuntimeTest \
      io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee work/runtime/instrumentation-google-messaging.txt
    instrument_status=${PIPESTATUS[0]}
    set -e
    collect_runtime '-google-messaging' false
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f files/google-messaging-result.json >/dev/null 2>&1; then
      adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat files/google-messaging-result.json > work/runtime/google-messaging-result.json
    fi
    for service in alarm power deviceidle; do
      diagnostic="google-messaging-${service}.txt"
      if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/$diagnostic" >/dev/null 2>&1; then
        adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat "files/$diagnostic" > "work/runtime/$diagnostic"
      fi
    done
    if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK (1 test)' work/runtime/instrumentation-google-messaging.txt \
        || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' work/runtime/instrumentation-google-messaging.txt; then core_status=1; fi
  fi
fi
exit "$core_status"
