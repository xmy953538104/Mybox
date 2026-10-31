"""Exercise the actual shell timeout boundary using a fake command, never an ADB device."""
import json, os, pathlib, shutil, subprocess, uuid

root = pathlib.Path(__file__).resolve().parents[1]
source = (root / 'scripts/test-reconstruction-runtime.sh').read_text(encoding='utf-8')
body = 'adb() {\n' + source.split('\nadb() {\n', 1)[1].split('\n}\n', 1)[0] + '\n}\n'
assert body.count('30s') == body.count('--kill-after=5s') == 1
body = body.replace('30s', '1s').replace('--kill-after=5s', '--kill-after=0.2s')
assert '"$MYLITE_ADB_BINARY" -s "$target" logcat --uid=' in source
bash = shutil.which('bash') or r'C:\Program Files\Git\bin\bash.exe'
assert pathlib.Path(bash).is_file()
work = root / 'work/adb-wrapper-selfcheck' / str(uuid.uuid4()); work.mkdir(parents=True, exist_ok=False)
(work / 'fake.sh').write_text('case "$1" in ok) printf ok;; fail) exit 7;; hang) exec sleep 5;; esac\n', encoding='utf-8')
prefix = 'set -euo pipefail\nMYLITE_ADB_BINARY="$MYLITE_TEST_BASH"\nMYLITE_RUNTIME_PHASE=selfcheck\n'
env = {**os.environ, 'MYLITE_TEST_BASH': bash}
def run(command):
    return subprocess.run([bash, '--noprofile', '--norc', '-c', prefix + body + command],
                          env=env, cwd=work, text=True, capture_output=True, timeout=8)
normal = run('adb fake.sh ok\n'); assert normal.returncode == 0 and normal.stdout == 'ok', normal
failed = run('adb fake.sh fail\n'); assert failed.returncode == 7, failed
conditional = run('if adb fake.sh fail; then exit 99; else printf denied; fi\n')
assert conditional.returncode == 0 and conditional.stdout == 'denied', conditional
timed = run('adb fake.sh hang || true\nprintf SHOULD_NOT_CONTINUE\n')
assert timed.returncode in (124, 137) and 'SHOULD_NOT_CONTINUE' not in timed.stdout, timed
result = json.loads((work / 'work/runtime/adb-timeout.json').read_text(encoding='utf-8'))
assert result == dict(schema=1, phase='selfcheck', operation='unknown', exitStatus=timed.returncode, completeBatch=False)
# Fake only the new 5s probes; the primary hang still reaches the real timeout utility.
probe_timeout = r'''timeout() {
  if [[ "${2:-}" == 5s ]]; then
    printf '%s\n' "$*" >> probe-invocations.txt
    printf synthetic
    [[ "${6:-}" != logcat ]] || return 7
  else command timeout "$@"; fi
}
'''
with_probes = run('target=emulator-1234\n' + probe_timeout + 'adb fake.sh hang || true\nprintf SHOULD_NOT_CONTINUE\n')
assert with_probes.returncode in (124, 137) and 'SHOULD_NOT_CONTINUE' not in with_probes.stdout, with_probes
probes = [json.loads(line) for line in (work / 'work/runtime/adb-probes.jsonl').read_text().splitlines()]
assert probes == [dict(schema=1, probe=name, exitStatus=7 if name == 'log_buffers' else 0)
                  for name in ['transport', 'uptime', 'logd', 'boot_count', 'log_buffers']]
trace = (work / 'probe-invocations.txt').read_text().splitlines(); assert len(trace) == 5
assert all('-s emulator-1234 ' in line for line in trace)
physical = run('target=HA25HSZM\n' + probe_timeout + 'adb fake.sh hang || true\nprintf SHOULD_NOT_CONTINUE\n')
assert physical.returncode in (124, 137) and 'SHOULD_NOT_CONTINUE' not in physical.stdout, physical
assert (work / 'probe-invocations.txt').read_text().splitlines() == trace
print(json.dumps(dict(normalStatusPreserved=True, failureStatusPreserved=True,
                     conditionalDenialPreserved=True, timeoutCannotBeSwallowed=True,
                     timeoutProbesPreserveFailure=True, physicalTargetsNeverProbed=True, actualAdbExecuted=False)))
