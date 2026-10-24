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
test_source=app/src/reconstructionTest/java
classes=()
while IFS= read -r source; do
  test_class="${source#"$test_source/"}"
  test_class="${test_class%.java}"
  classes+=("${test_class//\//.}")
done < <(find "$test_source/io/github/xmy953538104/mybox" -name '*Test.java' ! -name 'AccountUiTicketRuntimeTest.java' ! -name 'BroadcastPermissionRuntimeTest.java' ! -name 'AlarmRuntimeTest.java' ! -name 'ClipboardRuntimeTest.java' ! -name 'ProcessCapacityRuntimeTest.java' -print | LC_ALL=C sort)
if [[ ${#classes[@]} -eq 0 ]]; then echo 'No core instrumentation test classes found.' >&2; exit 1; fi
class_list=$(IFS=,; printf '%s' "${classes[*]}")
# Explicit classes avoid the legacy runner recursively loading every APK class during discovery.
adb -s "$target" logcat --uid="$host_uid" -v threadtime "${private_log_filters[@]}" > work/runtime/live-log.txt 2>&1 &
log_pid=$!
trap 'kill "$log_pid" 2>/dev/null || true' EXIT
core_status=0
# Retain every round: a later success must never erase a startup/recovery failure.
for round in 1 2 3; do
  label="-round$round"
  if [[ $round == 1 ]]; then label=''; fi
  adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  set +e
  timeout 240s adb -s "$target" shell am instrument -w -r \
    -e class "$class_list" \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee "work/runtime/instrumentation${label}.txt"
  instrument_status=${PIPESTATUS[0]}
  set -e
  collect_runtime "$label"
  if [[ $instrument_status -ne 0 ]] || ! grep -q 'OK ([0-9].*test' "work/runtime/instrumentation${label}.txt" \
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
    -e class io.github.xmy953538104.mybox.engine.ClipboardRuntimeTest \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee "work/runtime/instrumentation-clipboard-round${round}.txt"
  instrument_status=${PIPESTATUS[0]}
  set -e
  collect_runtime "-clipboard-round${round}"
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
adb -s "$target" exec-out screencap -p > work/runtime/guest-screen.png
if ! timeout 180s python3 scripts/test-persisted-job-reboot.py "$target" > work/runtime/persisted-job-reboot.log 2>&1; then
  core_status=1
fi
collect_runtime '-persisted-reboot' false
if [[ "${1:-}" == "--bundled-google" ]]; then
  adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  set +e
  timeout 240s adb -s "$target" shell am instrument -w -r \
    -e class io.github.xmy953538104.googlecheck.GoogleRuntimeTest \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee work/runtime/instrumentation-google.txt
  instrument_status=${PIPESTATUS[0]}
  set -e
  collect_runtime '-google'
  adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat files/ci-microg-settings.png > work/runtime/microg-settings-running.png || true
  if [[ $instrument_status -ne 0 ]]; then exit "$instrument_status"; fi
  grep -q 'OK ([0-9].*test' work/runtime/instrumentation-google.txt
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' work/runtime/instrumentation-google.txt; then exit 1; fi
  adb -s "$target" exec-out screencap -p > work/runtime/microg-screen.png
  if [[ "${MYLITE_GOOGLE_REGISTRATION_PROBE:-false}" == "true" ]]; then
    set +e
    timeout 240s adb -s "$target" shell am instrument -w -r \
      -e class io.github.xmy953538104.googlecheck.GoogleRegistrationRuntimeTest \
      io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee work/runtime/instrumentation-google-registration.txt
    instrument_status=${PIPESTATUS[0]}
    set -e
    collect_runtime '-google-registration' false
    if [[ $instrument_status -ne 0 ]]; then exit "$instrument_status"; fi
    grep -q 'OK ([0-9].*test' work/runtime/instrumentation-google-registration.txt
    if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' work/runtime/instrumentation-google-registration.txt; then exit 1; fi
  fi
fi
exit "$core_status"
