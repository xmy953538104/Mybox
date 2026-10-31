#!/usr/bin/env bash
set -euo pipefail
mkdir -p work/runtime
python3 - <<'PY'
from pathlib import Path
source = Path('scripts/test-reconstruction-runtime.sh').read_text()
body = 'adb() {\n' + source.split('\nadb() {\n', 1)[1].split('\n}\n', 1)[0] + '\n}\n'
Path('work/adb-control-helper.sh').write_text(body)
PY
MYLITE_ADB_BINARY=$(type -P adb)
readonly MYLITE_ADB_BINARY
MYLITE_RUNTIME_PHASE=control-bootstrap
source work/adb-control-helper.sh
mapfile -t targets < <(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1}')
[[ ${#targets[@]} == 1 ]]
target="${targets[0]}"
packages=$(adb -s "$target" shell cmd package list packages -U)
if [[ "$packages" == *io.github.xmy953538104.mybox* || "$packages" == *uid:19999* ]]; then
  echo 'The independent control needs a clean image and an unused quiet UID.' >&2
  exit 1
fi
printf '{"completeControl":false,"myboxInstalled":false,"physicalDeviceSelected":false}\n' > work/runtime/control-result.json
filters=('GmsServicesProvider:S' '*:V')
"$MYLITE_ADB_BINARY" -s "$target" logcat --uid=2000 -v threadtime "${filters[@]}" > work/runtime/control-live-preboot.txt 2>&1 &
log_pid=$!
trap 'kill "$log_pid" 2>/dev/null || true' EXIT
for round in $(seq 1 30); do
  if [[ "$round" == 16 ]]; then
    MYLITE_RUNTIME_PHASE=control-planned-reboot
    kill "$log_pid" 2>/dev/null || true
    printf '{"requested":true,"ackObserved":false}\n' > work/runtime/control-reboot.json
    started=$SECONDS
    adb -s "$target" reboot
    printf '{"requested":true,"ackObserved":true,"commandSeconds":%s}\n' "$((SECONDS-started))" > work/runtime/control-reboot.json
    timeout --kill-after=5s 90s "$MYLITE_ADB_BINARY" -s "$target" wait-for-device
    boot=''
    for attempt in $(seq 1 45); do
      boot=$(adb -s "$target" shell getprop sys.boot_completed | tr -d '\r')
      if [[ "$boot" == 1 ]]; then break; fi
      sleep 2
    done
    [[ "$boot" == 1 ]]
    "$MYLITE_ADB_BINARY" -s "$target" logcat --uid=2000 -v threadtime "${filters[@]}" > work/runtime/control-live-postboot.txt 2>&1 &
    log_pid=$!
  fi
  MYLITE_RUNTIME_PHASE="control-round-${round}"
  marker="round-${round}-complete"
  adb -s "$target" shell log -p i -t MyboxAdbCtrl "$marker"
  adb -s "$target" logcat -d --uid=2000 -v threadtime "${filters[@]}" > "work/runtime/control-shell-${round}.txt"
  grep -Fq "$marker" "work/runtime/control-shell-${round}.txt"
  adb -s "$target" logcat -d --uid=19999 -v threadtime "${filters[@]}" > "work/runtime/control-quiet-${round}.txt"
  if grep -Evq '^(-+ beginning of .+)?$' "work/runtime/control-quiet-${round}.txt"; then
    echo 'Unexpected messages for the unused control UID.' >&2
    exit 1
  fi
  printf '{"round":%s,"markerObserved":true,"quietUidEmpty":true}\n' "$round" >> work/runtime/control-rounds.jsonl
done
printf '{"completeControl":true,"rounds":30,"plannedReboots":1,"myboxInstalled":false,"physicalDeviceSelected":false}\n' > work/runtime/control-result.json
