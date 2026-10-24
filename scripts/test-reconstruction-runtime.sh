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
collect_runtime() {
  local label="$1"
  adb -s "$target" logcat -d --uid="$host_uid" -v threadtime > "work/runtime/engine-log${label}.txt" || true
  adb -s "$target" shell dumpsys activity activities > "work/runtime/activities${label}.txt" || true
  adb -s "$target" exec-out screencap -p > "work/runtime/screen${label}.png" || true
}
test_source=app/src/reconstructionTest/java
classes=()
while IFS= read -r source; do
  test_class="${source#"$test_source/"}"
  test_class="${test_class%.java}"
  classes+=("${test_class//\//.}")
done < <(find "$test_source/io/github/xmy953538104/mybox" -name '*Test.java' -print | LC_ALL=C sort)
if [[ ${#classes[@]} -eq 0 ]]; then echo 'No core instrumentation test classes found.' >&2; exit 1; fi
class_list=$(IFS=,; printf '%s' "${classes[*]}")
# Explicit classes avoid the legacy runner recursively loading every APK class during discovery.
adb -s "$target" logcat --uid="$host_uid" -v threadtime > work/runtime/live-log.txt 2>&1 &
log_pid=$!
trap 'kill "$log_pid" 2>/dev/null || true' EXIT
set +e
timeout 240s adb -s "$target" shell am instrument -w -r \
  -e class "$class_list" \
  io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee work/runtime/instrumentation.txt
instrument_status=${PIPESTATUS[0]}
set -e
collect_runtime ''
core_status=$instrument_status
if ! grep -q 'OK ([0-9].*test' work/runtime/instrumentation.txt || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' work/runtime/instrumentation.txt; then core_status=1; fi
adb -s "$target" exec-out screencap -p > work/runtime/guest-screen.png
if [[ "${1:-}" == "--bundled-google" ]]; then
  adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  set +e
  timeout 240s adb -s "$target" shell am instrument -w -r \
    -e class io.github.xmy953538104.googlecheck.GoogleRuntimeTest \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee work/runtime/instrumentation-google.txt
  instrument_status=${PIPESTATUS[0]}
  set -e
  collect_runtime '-google'
  if [[ $instrument_status -ne 0 ]]; then exit "$instrument_status"; fi
  grep -q 'OK ([0-9].*test' work/runtime/instrumentation-google.txt
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' work/runtime/instrumentation-google.txt; then exit 1; fi
  adb -s "$target" exec-out screencap -p > work/runtime/microg-screen.png
fi
exit "$core_status"
