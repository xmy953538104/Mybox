#!/usr/bin/env bash
set -euo pipefail
if [[ "${MYLITE_NETWORK_POLICY_CONTROL:-false}" != true ]]; then exit 0; fi
if [[ "${MYLITE_SETTINGS_SHORTCUTS_ONLY:-false}" == true ]]; then exit 0; fi
mapfile -t targets < <(timeout 15s adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1}')
if [[ ${#targets[@]} -ne 1 ]]; then echo 'Exactly one disposable emulator is required.' >&2; exit 1; fi
target="${targets[0]}"
result=work/network-policy-control-result
mkdir -p "$result"
old=work/network-policy-control/app-debug.apk
echo 'cb5efc193b528425497ab143cc2464fa7416d236f344de4e2c4b94e81b79ceac  work/network-policy-control/app-debug.apk' | sha256sum -c -
timeout 120s adb -s "$target" install -r "$old"
timeout 120s adb -s "$target" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
set +e
timeout 90s adb -s "$target" shell am instrument -w -r -e class io.github.xmy953538104.mybox.engine.NetworkSecurityRuntimeTest \
  io.github.xmy953538104.mybox.test/android.test.InstrumentationTestRunner | tee "$result/instrumentation.txt"
status=${PIPESTATUS[0]}
set -e
printf '{"processStatus":%s,"oldHead":"8937f1e9e4a84aead4d31ad822135320318418e1","expectedControlFailure":true}\n' "$status" > "$result/process.json"
timeout 15s adb -s "$target" exec-out run-as io.github.xmy953538104.mybox cat files/network-policy-allowed.json > "$result/allowed.json"
python3 - <<'PY'
import hashlib, json, pathlib, re
root=pathlib.Path('work/network-policy-control-result'); text=(root/'instrumentation.txt').read_text().replace('\r\n','\n')
report=json.loads((root/'allowed.json').read_text()); process=json.loads((root/'process.json').read_text())
assert process['processStatus'] == 0, 'Control did not reach an actual JUnit result'
assert 'FAILURES!!!' in text and re.search(r'Tests run: 1,\s*Failures: 1,\s*Errors: 0', text)
assert 'frameworkTrustMatchesTls' in text and 'INSTRUMENTATION_CODE: -1' in text
assert 'error' not in report and report['frameworkTrustMatchesTls'] is False
assert all(report[k] is True for k in ['hostPolicyWarmed','cachedContextReplaced','cachedHttpsReplaced',
    'customAnchorAccepted','pinRejected','untrustedRejected','defaultTlsHandshake','httpsDefaultTlsHandshake'])
out=dict(controlExposedStaleTrustManager=True, initialTlsStillPassed=True, realPlatformTlsDelegation=True,
         oldMainSha256='cb5efc193b528425497ab143cc2464fa7416d236f344de4e2c4b94e81b79ceac',
         fixtureSha256=hashlib.sha256(pathlib.Path('network-policy-fixture/build/outputs/apk/allowed/debug/network-policy-fixture-allowed-debug.apk').read_bytes()).hexdigest(),
         installedTrust=report['constructorTlsTrust'], frameworkTrust=report['constructorTrust'], physicalDeviceSelected=False)
(root/'review.json').write_text(json.dumps(out,indent=2)+'\n'); print(json.dumps(out))
PY
# Only this disposable emulator's synthetic control state exists at this point.
host_uid=$(timeout 15s adb -s "$target" shell cmd package list packages -U | awk '$1 == "package:io.github.xmy953538104.mybox" {sub(/^uid:/,"",$2); print $2}')
[[ "$host_uid" =~ ^[0-9]+$ ]]
timeout 15s adb -s "$target" logcat -b crash -d --uid="$host_uid" -v threadtime > "$result/crash-log.txt"
if grep -Eq 'FATAL EXCEPTION:|Fatal signal [0-9]+|Abort message:' "$result/crash-log.txt"; then
  echo 'Unexpected owned-process crash in the negative control; preserve it and stop.' >&2; exit 1
fi
timeout 15s adb -s "$target" shell am force-stop io.github.xmy953538104.mybox
timeout 30s adb -s "$target" shell pm clear io.github.xmy953538104.mybox | tee "$result/control-reset.txt"
grep -Fxq Success "$result/control-reset.txt"
