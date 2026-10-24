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
adb -s "$target" logcat -v threadtime 'AndroidRuntime:E' 'MyboxEngine:V' 'MyboxNative:V' 'MyboxFixture:V' 'TestRunner:V' 'ActivityManager:W' '*:S' > work/runtime/live-log.txt 2>&1 &
log_pid=$!
trap 'kill "$log_pid" 2>/dev/null || true' EXIT
set +e
timeout 240s adb -s "$target" shell am instrument -w -r \
  -e class "$class_list" \
  io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee work/runtime/instrumentation.txt
instrument_status=${PIPESTATUS[0]}
set -e
adb -s "$target" logcat -d -v brief 'AndroidRuntime:E' 'MyboxEngine:V' 'MyboxNative:V' 'MyboxFixture:V' '*:S' > work/runtime/engine-log.txt
if [[ $instrument_status -ne 0 ]]; then exit "$instrument_status"; fi
grep -q 'OK ([0-9].*test' work/runtime/instrumentation.txt
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' work/runtime/instrumentation.txt; then exit 1; fi
adb -s "$target" exec-out screencap -p > work/runtime/guest-screen.png
if [[ "${1:-}" == "--bundled-google" ]]; then
  adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
  set +e
  timeout 240s adb -s "$target" shell am instrument -w -r \
    -e class io.github.xmy953538104.googlecheck.GoogleRuntimeTest \
    io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee work/runtime/instrumentation-google.txt
  instrument_status=${PIPESTATUS[0]}
  set -e
  adb -s "$target" logcat -d -v brief 'AndroidRuntime:E' 'MyboxEngine:V' 'MyboxNative:V' 'MyboxFixture:V' '*:S' > work/runtime/engine-log-google.txt
  if [[ $instrument_status -ne 0 ]]; then exit "$instrument_status"; fi
  grep -q 'OK ([0-9].*test' work/runtime/instrumentation-google.txt
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' work/runtime/instrumentation-google.txt; then exit 1; fi
  adb -s "$target" exec-out screencap -p > work/runtime/microg-screen.png
fi
