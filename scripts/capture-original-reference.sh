#!/usr/bin/env bash
set -euo pipefail
mapfile -t targets < <(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1}')
if [[ ${#targets[@]} -ne 1 ]]; then echo 'Exactly one disposable emulator is required.' >&2; exit 2; fi
target="${targets[0]}"
output=work/reference-observation
mkdir -p "$output"
echo '6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257  work/reference/gbox.apk' | sha256sum -c -
for property in ro.build.fingerprint ro.build.version.sdk ro.product.cpu.abilist ro.dalvik.vm.native.bridge ro.enable.native.bridge.exec ro.enable.native.bridge.exec64; do
  value=$(adb -s "$target" shell getprop "$property" | tr -d '\r')
  printf '%s=%s\n' "$property" "$value" >> "$output/environment.txt"
done
adb -s "$target" shell dumpsys webviewupdate > "$output/webview.txt"
abi=$(adb -s "$target" shell getprop ro.product.cpu.abilist | tr -d '\r')
if [[ ",$abi," != *',arm64-v8a,'* ]]; then
  echo '{"observed":false,"reason":"Official image does not advertise ARM64 execution; no translator or ABI is forced."}' > "$output/result.json"
  exit 2
fi
adb -s "$target" install -r work/reference/gbox.apk > "$output/install.txt"
uid=$(adb -s "$target" shell cmd package list packages -U | awk '$1 == "package:com.gbox.android" {sub(/^uid:/,"",$2); print $2}')
if [[ ! "$uid" =~ ^[0-9]+$ ]]; then echo 'Cannot identify original UID.' >&2; exit 2; fi
component=$(adb -s "$target" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.gbox.android | tr -d '\r' | tail -n 1)
if [[ "$component" != com.gbox.android/* ]]; then echo 'Original launcher is unavailable.' >&2; exit 2; fi
adb -s "$target" shell am start -W -n "$component" > "$output/launch.txt"
for phase in initial settled; do
  if [[ "$phase" == settled ]]; then sleep 15; fi
  adb -s "$target" exec-out screencap -p > "$output/$phase.png"
  timeout 20s adb -s "$target" shell uiautomator dump /sdcard/gbox-reference.xml > "$output/$phase-accessibility-status.txt"
  adb -s "$target" exec-out cat /sdcard/gbox-reference.xml > "$output/$phase.xml"
done
adb -s "$target" logcat -b crash -d --uid="$uid" -v threadtime > "$output/crash.txt"
adb -s "$target" shell dumpsys activity activities > "$output/activities.txt"
adb -s "$target" shell dumpsys activity exit-info com.gbox.android > "$output/process-exits.txt"
adb -s "$target" shell pidof com.gbox.android > "$output/pid.txt" || true
if grep -Eq 'FATAL EXCEPTION:|Fatal signal [0-9]+|Abort message:' "$output/crash.txt" || [[ ! -s "$output/pid.txt" ]]; then
  echo '{"observed":true,"startupStable":false,"googleAuthenticationVerified":false,"downloadVerified":false}' > "$output/result.json"
  exit 1
fi
echo '{"observed":true,"startupStable":true,"scope":"First launch only; no consent/permission UI bypass, credentials, or guest import. ARM translation is not native ARM performance.","googleAuthenticationVerified":false,"downloadVerified":false}' > "$output/result.json"
