#!/usr/bin/env bash
set -euo pipefail
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--bundled-google" ) ]]; then
  echo 'Usage: test-reconstruction-runtime.sh [--bundled-google]' >&2
  exit 2
fi
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
  adb -s "$target" shell dumpsys activity activities > "work/runtime/activities${label}.txt" || true
  for role in executor server; do
    if adb -s "$target" shell run-as io.github.xmy953538104.mybox test -f "files/job-recovery-failure-${role}.txt" >/dev/null 2>&1; then
      adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat "files/job-recovery-failure-${role}.txt" > "work/runtime/job-recovery-failure-${role}${label}.txt" 2>/dev/null || true
    fi
  done
  if [[ "${2:-true}" == "true" ]]; then
    adb -s "$target" exec-out screencap -p > "work/runtime/screen${label}.png" || true
  fi
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
adb -s "$target" logcat --uid="$host_uid" -v threadtime "${private_log_filters[@]}" > work/runtime/live-log.txt 2>&1 &
log_pid=$!
trap 'kill "$log_pid" 2>/dev/null || true' EXIT
core_status=0
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
  # This aggregate now runs 128 tests; keep their individual deadlines and require actual runner completion.
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
  # OS alarm recovery deliberately kills only its server after releasing this host's provider lease.
  # The test must see the original system alarm wake it; no force-stop/re-set occurs inside that test.
  adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  set +e
  timeout 240s adb -s "$target" shell am instrument -w -r \
    -e class io.github.xmy953538104.mybox.engine.AlarmRuntimeTest \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee "work/runtime/instrumentation-alarm-round${round}.txt"
  instrument_status=${PIPESTATUS[0]}
  set -e
  collect_runtime "-alarm-round${round}"
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
if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK (1 test)' work/runtime/instrumentation-activity-deferred.txt \
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
