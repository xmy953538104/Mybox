#!/usr/bin/env bash
set -euo pipefail
mapfile -t targets < <(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1}')
if [[ ${#targets[@]} -ne 1 ]]; then
  echo 'Exactly one emulator is required; physical devices are never selected.' >&2
  exit 1
fi
target="${targets[0]}"
adb -s "$target" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$target" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p work/runtime
adb -s "$target" shell am instrument -w -r \
  -e package io.github.xmy953538104.mybox \
  io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee work/runtime/instrumentation.txt
adb -s "$target" logcat -d -v brief 'AndroidRuntime:E' 'MyboxEngine:V' 'MyboxNative:V' '*:S' > work/runtime/engine-log.txt
grep -q 'OK ([0-9].*test' work/runtime/instrumentation.txt
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' work/runtime/instrumentation.txt; then exit 1; fi
