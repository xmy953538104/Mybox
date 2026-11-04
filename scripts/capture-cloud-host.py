"""Fixed-schema Linux CI host health; never reads command lines or guest memory."""
import json, os, pathlib, re, shutil, subprocess, sys, time

def kilobytes(text, key):
    match = re.search(r'^' + re.escape(key) + r':\s+(\d+) kB$', text, re.M)
    return int(match[1]) if match else None

def process(pid, text):
    name = re.search(r'^Name:\s+(\S+)$', text, re.M)
    if not name or not name[1].startswith(('qemu-system-', 'emulator')): return None
    parent = re.search(r'^PPid:\s+(\d+)$', text, re.M)
    state = re.search(r'^State:\s+(\w)', text, re.M)
    return dict(pid=pid, parent=int(parent[1]) if parent else None,
                state=state[1] if state else None, rss_kb=kilobytes(text, 'VmRSS'),
                virtual_kb=kilobytes(text, 'VmSize'))

if sys.argv[1:] == ['--self-test']:
    assert kilobytes('MemAvailable:  123 kB\n', 'MemAvailable') == 123
    assert kilobytes('MemAvailable: invalid\n', 'MemAvailable') is None
    assert process(3, 'Name:\tqemu-system-x86\nPPid:\t1\nState:\tS (sleeping)\nVmRSS:\t42 kB\n') == dict(pid=3,parent=1,state='S',rss_kb=42,virtual_kb=None)
    assert process(4, 'Name:\tprivate-worker\n') is None
    print('Fixed-schema cloud host parser self-check passed; no host/device inspection.')
    sys.exit(0)

if sys.platform != 'linux' or os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_OS') != 'Linux':
    raise SystemExit('Host capture is restricted to the disposable Linux CI runner')
assert len(sys.argv) == 2
result = dict(schema=1, mono_ns=time.monotonic_ns(), emulators=[])
mem = pathlib.Path('/proc/meminfo').read_text()
result.update(mem_available_kb=kilobytes(mem, 'MemAvailable'), swap_free_kb=kilobytes(mem, 'SwapFree'),
              disk_free_bytes=shutil.disk_usage('.').free)
for path in pathlib.Path('/proc').iterdir():
    if not path.name.isdecimal(): continue
    try: value = process(int(path.name), (path/'status').read_text())
    except (OSError, UnicodeError): continue
    if value: result['emulators'].append(value)
result['cgroup_memory'] = {}
for name in ('memory.current', 'memory.max', 'memory.events'):
    try:
        text = (pathlib.Path('/sys/fs/cgroup')/name).read_text()
        result['cgroup_memory'][name] = ({k:int(v) for k,v in (line.split() for line in text.splitlines())}
                                         if name == 'memory.events' else int(text) if text.strip().isdecimal() else None)
    except (OSError, ValueError): result['cgroup_memory'][name] = None
try:
    kernel = subprocess.run(['sudo','-n','dmesg','--level=err,warn'], capture_output=True, timeout=5)
    result['kernel_oom'] = dict(available=kernel.returncode == 0,
        killed_pids=[int(x) for x in re.findall(rb'Out of memory: Killed process (\d+)', kernel.stdout)] if kernel.returncode == 0 else None)
except (OSError, subprocess.TimeoutExpired): result['kernel_oom'] = dict(available=False, killed_pids=None)
pathlib.Path(sys.argv[1]).write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
